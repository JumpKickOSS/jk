// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.MainClassScanner;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Plugin task wiring, packager invocation, and application SBOM.
 */
public final class PlannerPlugin {

    private PlannerPlugin() {}

    static boolean beforeCompile(PluginBuild.TaskDecl step) {
        if (step.sourceGenerating()) return true;
        if (step.testOnly() || step.packageTime()) return false;
        // Intermediate tasks with no classes input (and no package/test contributions) run after
        // resolve, not after copy-resources — otherwise they cycle with compile→resources.
        return step.inputs() == null || !step.inputs().contains("classes");
    }

    /**
     * The single classes-dir-replacing task ({@code transformsClasses}), if any. Two transforms are
     * an error; validated at BuildPlan construction.
     */
    static PluginBuild.TaskDecl transformStep(PluginBuild.Declarations decls) {
        if (decls == null) return null;
        PluginBuild.TaskDecl transform = null;
        for (PluginBuild.TaskDecl s : decls.steps()) {
            if (!s.transforms()) continue;
            if (transform != null) {
                throw new IllegalStateException("plugin tasks " + transform.name() + " and " + s.name()
                        + " both declare transformsClasses — at most one task may replace the classes dir"
                        + " (conflicts are errors, not priorities)");
            }
            if (beforeCompile(s)) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses but is source-generating — a transform rewrites"
                        + " compiled classes after compile");
            }
            if (!s.packageTime()) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses but is not package-time — the transform must"
                        + " finish before anything consumes the replaced classes");
            }
            if (!s.inputs().contains("classes")) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses but not In.classes() — the classes dir is what"
                        + " it transforms");
            }
            if (!s.contributesClasses().isEmpty()
                    || !s.contributesResources().isEmpty()
                    || !s.contributesSources().isEmpty()) {
                throw new IllegalStateException("plugin task " + s.name()
                        + " declares transformsClasses and contributes* — a transform REPLACES the"
                        + " classes dir; contributions merge, and the two don't compose");
            }
            if (!s.outputs().contains(s.transformsClasses())) {
                throw new IllegalStateException("plugin task " + s.name() + " transformsClasses(\""
                        + s.transformsClasses() + "\") must name a declared output dir");
            }
            transform = s;
        }
        return transform;
    }

    /**
     * The stage a plugin task is scheduled in, from the same predicates {@link
     * #pluginTask} uses to build its {@code requires} — inference must never contradict the
     * edges, or {@link BuildPlan} rejects a plan the planner itself produced.
     */
    static BuildStage pluginWindow(PluginBuild.TaskDecl step) {
        if (beforeCompile(step)) return BuildStage.GENERATE;
        if (step.testOnly()) return BuildStage.TEST;
        return BuildStage.COMPILE;
    }

    /**
     * The latest stage a plugin task may claim. {@code run-tests} (TEST) requires every
     * test-classpath contributor, and {@code package-jar} (PACKAGE) requires every
     * {@link PluginBuild.TaskDecl#packageTime()} task ({@link #packageRequires}) — either
     * consumer rejects a plan where the task claims a later stage than it.
     */
    static BuildStage pluginCeiling(PluginBuild.TaskDecl step) {
        boolean requiredByTests = step.testOnly()
                || (step.contributesTestClasspath() != null
                        && !step.contributesTestClasspath().isEmpty());
        if (requiredByTests) return BuildStage.TEST;
        if (step.packageTime()) return BuildStage.PACKAGE;
        return BuildStage.IMAGE;
    }

    /**
     * Product stage for a plugin task. A plugin may declare one to sharpen the UI fold (dex is
     * {@code package}, not {@code compile}), but only within the window its scheduling allows —
     * a contradiction is the plugin's error and says so.
     */
    static BuildStage pluginStage(PluginBuild.TaskDecl step) {
        BuildStage window = pluginWindow(step);
        String declared = step.stage();
        if (declared == null || declared.isBlank()) return window;
        BuildStage stage = BuildStage.fromWireExact(declared)
                .orElseThrow(() -> new IllegalStateException("plugin task " + step.name() + " declares stage `"
                        + declared + "` — expected one of " + BuildStage.wireNames()));
        BuildStage ceiling = pluginCeiling(step);
        if (stage.pipelineOrder() < window.pipelineOrder() || stage.pipelineOrder() > ceiling.pipelineOrder()) {
            throw new IllegalStateException("plugin task " + step.name() + " declares stage `" + stage.wireName()
                    + "` but is scheduled in the " + window.wireName() + " window"
                    + (ceiling == BuildStage.TEST ? " and is required by run-tests" : "")
                    + " — declare a stage between `" + window.wireName() + "` and `" + ceiling.wireName() + "`");
        }
        return stage;
    }

    /**
     * The DAG edges a declared plugin task rides: its own declarations, the window anchors, and
     * the peer/transform outputs its inputs name. Shares the {@link #beforeCompile} /
     * {@link PluginBuild.TaskDecl#testOnly()} split with {@link #pluginWindow} so stage and edges
     * cannot disagree.
     */
    static List<String> pluginRequires(PluginBuild.TaskDecl step, PluginBuild.TaskDecl transform) {
        boolean beforeCompile = beforeCompile(step);
        if (beforeCompile && step.inputs().contains("classes")) {
            throw new IllegalStateException("plugin task " + step.name()
                    + " is source-generating but declares In.classes() — generated-source tasks"
                    + " consume project files (In.projectFiles), config, or other task outputs");
        }
        List<String> requires = new ArrayList<>();
        // Explicit plugin-declared edges first.
        if (step.requires() != null) {
            for (String r : step.requires()) {
                if (r != null && !r.isBlank()) requires.add(r);
            }
        }
        if (beforeCompile) {
            requires.add(TaskNames.PARSE_BUILD);
            requires.add(TaskNames.RESOLVE_DEPS);
            requires.add(TaskNames.ENSURE_JDK);
            // Project build-logic codegen (BEFORE_COMPILE) before plugin source generators.
            requires.add(TaskNames.BUILD_LOGIC_BEFORE_COMPILE);
        } else if (step.testOnly()) {
            requires.add(TaskNames.PARSE_BUILD);
            requires.add(TaskNames.RESOLVE_DEPS);
            requires.add(TaskNames.ENSURE_JDK);
        } else {
            requires.add(TaskNames.COPY_RESOURCES);
        }
        // Peer plugin outputs (In.stepOutput) — declared inputs ARE the dependency graph.
        for (String input : step.inputs()) {
            if (input.startsWith("step:")) requires.add("plugin-" + input.substring("step:".length()));
        }
        // Classes consumers wait on the transform (dex after Hilt rewrite).
        if (transform != null
                && !step.name().equals(transform.name())
                && step.inputs().contains("classes")) {
            requires.add("plugin-" + transform.name());
        }
        return requires;
    }

    /**
     * Everything the declared-input vocabulary is fingerprinted from. Each arm fills it with what
     * its own body receives; {@link #declaredInputTokens} alone decides the spelling.
     */
    record InputSources(
            Path classes,
            List<Path> runtimeClasspath,
            List<PluginBuild.ProdEntry> runtimeEntries,
            PluginConfig config,
            BuildLayout layout,
            Path moduleDir) {}

    /**
     * The declared inputs as action-key tokens: the one renderer the step arm and the packager arm
     * both key on, one prefix per {@link In.Kind}. Two copies of this switch had already drifted to
     * {@code cp:} and {@code libs:} for the same declared input, which is a wrong-artifact restore
     * rather than a cosmetic difference. The switch is exhaustive over the closed vocabulary and
     * {@link In#fromWire} refuses an unknown spelling — an input skipped here is an input missing
     * from the key, i.e. a silently stale artifact.
     */
    static List<String> declaredInputTokens(List<String> inputs, InputSources src) throws IOException {
        List<String> tokens = new ArrayList<>();
        for (String input : inputs) {
            In declared = In.fromWire(input);
            switch (declared.kind()) {
                case CLASSES -> tokens.add("classes:" + ClasspathFingerprint.entry(src.classes()));
                case RUNTIME_CLASSPATH -> tokens.add("cp:" + ClasspathFingerprint.of(src.runtimeClasspath()));
                case RUNTIME_ENTRIES -> {
                    tokens.add("cp:" + ClasspathFingerprint.of(src.runtimeClasspath()));
                    // Container content (an AAR's res/assets/jni) is input too — an assets-only AAR
                    // bump must re-run even though no classes jar changed.
                    for (PluginBuild.ProdEntry entry : src.runtimeEntries()) {
                        if (entry.container() != null) {
                            tokens.add("container:" + entry.fileName() + ":"
                                    + ClasspathFingerprint.entry(entry.container()));
                        }
                    }
                }
                case CONFIG -> tokens.add("config:" + PluginBuild.configToken(src.config()));
                case STEP_OUTPUT ->
                    tokens.add(input + ":"
                            + ClasspathFingerprint.entry(PluginBuild.taskScratch(src.layout(), declared.step())));
                case PROJECT_FILES ->
                    tokens.add(input + ":"
                            + ClasspathFingerprint.entry(src.moduleDir().resolve(declared.step())));
            }
        }
        return tokens;
    }

    /**
     * The manifest-contributed tool artifacts as action-key tokens: the second renderer the step
     * arm and the packager arm share, for the same reason {@link #declaredInputTokens} exists. They
     * used to disagree — per-artifact {@code tool:<name>:<content>} in the step arm, one combined
     * {@code extras:} hash in the packager arm, which cannot tell two tools apart by name at all.
     *
     * <p>A fetched artifact — a jar, or a CAS-materialized transitive closure dir — <em>is</em> its
     * content, so it is fingerprinted. A step-dependency that names a whole provisioned SDK
     * component instead ({@code sdk-component} with no {@code sdk-path}) is a <b>location</b>, not
     * an artifact: android's {@code sdk-root} resolves to the managed Android SDK root, so
     * fingerprinting it walked every installed platform, system image and emulator binary on the
     * machine — tens of gigabytes — into every android step's and packager's key, on every build.
     * A component's identity is its revision, which is exactly what {@code LockPipeline.pinSdk}
     * records as {@code [[sdk]]} (and deliberately declines to record for the {@code root}
     * pseudo-component, which has none: nothing is installed <em>at</em> the root, only under it,
     * and the named components underneath are each keyed on their own).
     */
    static List<String> toolTokens(
            List<PluginContributions.StepDep> declared, Map<String, Path> extras, Map<String, String> sdkPins)
            throws IOException {
        Map<String, String> wholeComponents = new LinkedHashMap<>();
        for (PluginContributions.StepDep dep : declared) {
            if (dep.sdkComponent() != null
                    && (dep.sdkPath() == null || dep.sdkPath().isBlank())) {
                wholeComponents.put(dep.artifact(), dep.sdkComponent());
            }
        }
        List<String> tokens = new ArrayList<>(extras.size());
        for (Map.Entry<String, Path> tool : extras.entrySet()) {
            String component = wholeComponents.get(tool.getKey());
            if (component == null) {
                tokens.add("tool:" + tool.getKey() + ":" + ClasspathFingerprint.entry(tool.getValue()));
                continue;
            }
            String revision = sdkPins.get(component);
            if (revision == null) revision = SdkComponents.installedRevision(component);
            tokens.add(
                    "tool:" + tool.getKey() + ":sdk:" + component + "@" + (revision == null ? "unpinned" : revision));
        }
        return tokens;
    }

    /**
     * One declared build-plugin task: engine fingerprints inputs, restores on hit, forks on miss.
     */
    static Task pluginTask(
            BuildPlanner.Ctx cx, PluginBuild.Active active, PluginBuild.TaskDecl step, PluginBuild.TaskDecl transform) {
        BuildPlanner.Inputs in = cx.in();
        boolean beforeCompile = beforeCompile(step);
        List<String> requires = pluginRequires(step, transform);
        return Task.builder("plugin-" + step.name())
                .label(step.name())
                .kind(TaskKind.CPU)
                .stage(pluginStage(step))
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                // A plugin command forks its process and can dominate a build (d8 dex, AOT), yet its
                // static reservation is a token 1 unit — price it from the running metrics once this
                // machine has seen it run (own-project average, else host average).
                .weight(() -> EffortWeights.learnedFixedWeight(in.dir().toString(), "plugin-" + step.name(), 1))
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path javaHome = ctx.require(JAVA_HOME);
                    Path scratch = PluginBuild.taskScratch(layout, step.name());
                    // Before compile no classes exist to scan — the declared main (or null) rides.
                    String startClass =
                            beforeCompile(step) ? project.mainClass() : resolvedMain(project, in.dir(), classes);

                    List<Path> classpath =
                            PluginBuild.productionClasspath(in.dir(), in.cache(), in.lockFile(), project);
                    List<PluginBuild.ProdEntry> prodEntries = step.inputs().contains("runtime-entries")
                            ? PluginBuild.productionEntries(in.dir(), in.cache(), in.lockFile(), project)
                            : List.of();

                    // Manifest-contributed tool artifacts (aapt2, r8, a platform jar) — fetched
                    // into the cache, handed to the body by artifact name, keyed like any input.
                    Map<String, String> sdkPins = PluginBuild.sdkPins(in.lockFile());
                    Map<String, Path> toolExtras =
                            PluginBuild.fetchStepDependencies(project, in.dir(), cx.cas(), sdkPins);

                    // Action key: exactly the declared inputs, plus the very facts the body sees —
                    // the same ProjectFacts instance rides the spec below, so no fact can reach the
                    // plugin without reaching its key.
                    ProjectFacts facts = PluginBuild.facts(project, startClass);
                    List<String> tokens = new ArrayList<>(declaredInputTokens(
                            step.inputs(),
                            new InputSources(classes, classpath, prodEntries, active.config(), layout, in.dir())));
                    tokens.addAll(
                            toolTokens(PluginContributions.stepDependencies(project, in.dir()), toolExtras, sdkPins));
                    tokens.add("facts:" + facts.token());
                    // The step's CODE is an input: a changed plugin jar must re-run the
                    // step, or a plugin upgrade (or first-party dev iteration) silently restores
                    // outputs produced by the old code.
                    tokens.add("worker:" + ClasspathFingerprint.entry(PluginBuild.workerJarFor(active, in.cache())));
                    String taskId = ActionKey.qualifiedTaskId("plugin-" + step.name(), scratch);
                    String actionKey = ActionKey.forArtifact(taskId, BuildIdentity.cacheKeyVersion(), tokens);
                    ActionCache actionCache = cx.actionCache();
                    var hit = actionCache.lookup(actionKey);
                    if (hit.isPresent()) {
                        try {
                            if (actionCache.restore(hit.get(), scratch)) {
                                if (step.transforms()) {
                                    ctx.put(MAIN_CLASSES, scratch.resolve(step.transformsClasses()));
                                }
                                ctx.label(step.name() + " up-to-date");
                                ctx.progress(1);
                                return;
                            }
                            // false: missing/corrupt blob — fall through to a fresh run.
                        } catch (IOException e) {
                            // A missing CAS blob (pruned cache) falls through to a fresh run.
                        }
                    }

                    cc.jumpkick.host.PathUtil.deleteRecursively(scratch); // stale outputs never survive
                    Files.createDirectories(scratch);
                    ctx.label(step.name());
                    SpecWriter specWriter = new SpecWriter()
                            .op(
                                    PluginProtocol.OP_RUN_STEP,
                                    step.name(),
                                    active.manifest().id())
                            .configValues(active.config().values())
                            .project(facts)
                            .layout(classes, in.dir(), scratch)
                            .javaHome(javaHome)
                            .classpath(classpath, PluginProtocol.ROLE_COMPILE);
                    for (var pe : prodEntries) {
                        specWriter.entry(
                                pe.fileName(),
                                pe.jar(),
                                pe.snapshot(),
                                pe.container(),
                                pe.group(),
                                pe.artifact(),
                                pe.version());
                    }
                    for (var tool : toolExtras.entrySet()) {
                        specWriter.extra(tool.getKey(), tool.getValue());
                    }
                    for (String input : step.inputs()) {
                        if (input.startsWith("step:")) {
                            String other = input.substring("step:".length());
                            specWriter.stepOutput(other, PluginBuild.taskScratch(layout, other));
                        }
                    }
                    Path spec = specWriter.writeTempSpec();
                    try {
                        PluginBuild.runWorker(active, in.cache(), spec, ctx::label);
                    } catch (IOException e) {
                        ctx.error(step.name(), e.getMessage());
                        throw e;
                    } finally {
                        Files.deleteIfExists(spec);
                    }
                    actionCache.store(taskId, actionKey, Map.of(), scratch);
                    // A transform's output IS the classes dir from here on: re-point MAIN_CLASSES
                    // so packaging, later steps' In.classes, and the native tail read it
                    // (ordering: consumers carry a requires edge on this step).
                    if (step.transforms()) {
                        ctx.put(MAIN_CLASSES, scratch.resolve(step.transformsClasses()));
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Plugin packager instead of plain jar: engine caches on declared inputs, fetches packager
     * deps, prepares SBOM, and hands coordinate-named runtime entries to the plugin.
     */
    static void packagePlugin(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            JkBuild project,
            Path classes,
            Path jarPath,
            PluginBuild.Active active,
            PluginBuild.Declarations decls,
            Map<String, String> secrets)
            throws Exception {
        Lockfile lock = ctx.require(LOCKFILE);
        BuildLayout layout = ctx.require(LAYOUT);
        ClasspathResolver resolver = new ClasspathResolver(cas);
        String startClass = resolvedMain(project, in.dir(), classes);
        // The same facts the packager body receives below — key and spec cannot diverge.
        ProjectFacts facts = PluginBuild.facts(project, startClass);

        // Coordinate-named runtime entries: lock artifacts + workspace sibling jars — the SAME
        // set steps see via In.runtimeEntries(). Packaging from the lock alone drops sibling
        // module jars and ships a Boot/assembly artifact that cannot start.
        List<PluginBuild.ProdEntry> entries =
                PluginBuild.productionEntries(in.dir(), in.cache(), in.lockFile(), project);
        List<CycloneDxSbom.Component> sbomComponents = new ArrayList<>();
        for (ClasspathResolver.Entry entry : resolver.entriesFor(lock, ClasspathResolver.RUNTIME)) {
            Lockfile.Artifact a = entry.artifact();
            sbomComponents.add(
                    new CycloneDxSbom.Component(a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex()));
        }
        // Packagers get the packager-dependency artifacts AND the step-dependency tools (the
        // same artifacts commands receive — an AAB packager forks bundletool exactly like a step
        // forks aapt2). A packager-dependency wins a name collision.
        Map<String, String> sdkPins = PluginBuild.sdkPins(in.lockFile());
        Map<String, Path> extras =
                new LinkedHashMap<>(PluginBuild.fetchStepDependencies(project, in.dir(), cas, sdkPins));
        extras.putAll(PluginBuild.fetchPackagerDependencies(project, in.dir(), cas));

        // Action key from the declared inputs + facts — any config, classes, dependency-set,
        // step-output, extra-artifact, or manifest change re-packages; nothing else does. The
        // packager's runtime view IS its entry jars, so that is what the shared renderer keys.
        List<Path> entryJars = new ArrayList<>(entries.size());
        for (PluginBuild.ProdEntry e : entries) {
            if (e.jar() != null) entryJars.add(e.jar());
        }
        List<String> tokens = new ArrayList<>(declaredInputTokens(
                decls.packager().inputs(),
                new InputSources(classes, entryJars, entries, active.config(), layout, in.dir())));
        tokens.addAll(toolTokens(PluginContributions.stepDependencies(project, in.dir()), extras, sdkPins));
        if (!secrets.isEmpty()) {
            // A changed signing credential re-signs (the signature is part of the artifact);
            // the key carries only a digest — a secret value never appears anywhere readable.
            StringBuilder sb = new StringBuilder();
            for (var e : new TreeMap<>(secrets).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            tokens.add("secrets:"
                    + cc.jumpkick.host.Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)));
        }
        // [manifest] attributes ride inside the facts token — they reach the packager, so they key it.
        tokens.add("facts:" + facts.token());
        // Packager identity (e.g. shrink vs boot) so CLI packaging overrides cannot cache-collide.
        tokens.add("packaging:" + decls.packager().name());
        // The packager's CODE is an input, same as plugin steps (see pluginTask).
        tokens.add("worker:" + ClasspathFingerprint.entry(PluginBuild.workerJarFor(active, in.cache())));
        // The minified packager folds `jk train` observations into its keep rules out-of-band
        // (same path derivation as MinifiedJarPackager.produce). Absence and every content state
        // must be distinct keys — otherwise a post-train rebuild restores the pre-train jar as
        // "up-to-date" and training never reaches the shipped artifact.
        if ("minified-jar".equals(decls.packager().name())) {
            Path trainSurface = jarPath.getParent()
                    .resolve(TrainLayout.ROOT)
                    .resolve("merged")
                    .resolve(TrainLayout.SURFACE_JSON);
            tokens.add("train:"
                    + (Files.isRegularFile(trainSurface) ? ClasspathFingerprint.entry(trainSurface) : "absent"));
        }
        String pkgTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, jarPath);
        String pkgKey = ActionKey.forArtifact(pkgTask, BuildIdentity.cacheKeyVersion(), tokens);
        if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
            ctx.put(JAR_PATH, jarPath);
            ctx.label(jarPath.getFileName() + " up-to-date");
            ctx.cached();
            ctx.progress(1);
            return;
        }

        // SBOM (always on): free and deterministic straight from the lockfile.
        byte[] sbom = CycloneDxSbom.write(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                sbomComponents);
        Path sbomFile = Files.createTempFile("jk-plugin-sbom-", ".cdx.json");
        Files.write(sbomFile, sbom);

        SpecWriter spec = new SpecWriter()
                .op(PluginProtocol.OP_PACKAGE, null, active.manifest().id())
                .configValues(active.config().values())
                .project(facts)
                .layout(classes, in.dir(), layout.moduleTargetDir().resolve("plugin"))
                .javaHome(ctx.require(JAVA_HOME))
                .artifact(jarPath);
        for (PluginBuild.ProdEntry e : entries) {
            spec.entry(e.fileName(), e.jar(), e.snapshot(), e.container(), e.group(), e.artifact(), e.version());
        }
        for (var e : extras.entrySet()) spec.extra(e.getKey(), e.getValue());
        for (var e : secrets.entrySet()) spec.secret(e.getKey(), e.getValue());
        spec.extra("sbom", sbomFile);
        for (PluginBuild.TaskDecl step : decls.steps()) {
            Path scratch = PluginBuild.taskScratch(layout, step.name());
            if (Files.isDirectory(scratch)) spec.stepOutput(step.name(), scratch);
        }
        Path specFile = spec.writeTempSpec();
        // A stale conventional sibling from an earlier run must never survive a re-package.
        String staleName = jarPath.getFileName().toString();
        int staleDot = staleName.lastIndexOf('.');
        if (staleDot > 0 && !staleName.endsWith(".jar")) {
            Files.deleteIfExists(jarPath.resolveSibling(staleName.substring(0, staleDot) + ".jar"));
        }
        List<String> workerLines;
        try {
            workerLines = PluginBuild.runWorker(active, in.cache(), specFile, ctx::label);
        } catch (IOException e) {
            ctx.error("package", e.getMessage());
            throw e;
        } finally {
            Files.deleteIfExists(specFile);
            Files.deleteIfExists(sbomFile);
        }
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException(
                    "plugin packager " + decls.packager().name() + " reported success but produced no " + jarPath);
        }
        // A container packager (an AAR) may also emit the conventional classes jar next to the
        // main artifact — the host-classpath view workspace siblings compile against. Both cache
        // under the same key so a hit restores the pair.
        List<Path> produced = new ArrayList<>();
        produced.add(jarPath);
        String artifactName = jarPath.getFileName().toString();
        int dot = artifactName.lastIndexOf('.');
        if (dot > 0 && !artifactName.endsWith(".jar")) {
            Path conventional = jarPath.resolveSibling(artifactName.substring(0, dot) + ".jar");
            if (Files.isRegularFile(conventional)) produced.add(conventional);
        }
        // Packager-declared extras (PackageIo.produced — quarkus fast-jar lib/ siblings): a
        // multi-file layout must cache whole or a hit after `jk clean` restores a broken
        // artifact. Directories expand recursively; escapes of the artifact dir
        // are a packager bug.
        Path outBase = jarPath.getParent().toAbsolutePath().normalize();
        for (String line : workerLines) {
            if (!"produced".equals(cc.jumpkick.jsonl.Jsonl.str(line, "t"))) continue;
            Path p = Path.of(String.valueOf(cc.jumpkick.jsonl.Jsonl.str(line, "path")))
                    .toAbsolutePath()
                    .normalize();
            if (!p.startsWith(outBase)) {
                throw new IOException("packager declared produced path outside the artifact dir: " + p);
            }
            if (Files.isRegularFile(p)) {
                produced.add(p);
            } else if (Files.isDirectory(p)) {
                try (Stream<Path> walk = Files.walk(p)) {
                    walk.filter(Files::isRegularFile).forEach(produced::add);
                }
            }
        }
        storePackaged(in.cache(), pkgTask, pkgKey, tokens, jarPath.getParent(), produced, !in.ephemeralActions());
        ctx.put(JAR_PATH, jarPath);
        ctx.progress(1);
    }

    /** The resolved application entry point: declared, else the unique compiled main (when scannable). */
    static String resolvedMain(JkBuild project, Path moduleDir, Path classes) throws IOException {
        String main = project.mainClass();
        if ((main == null || main.isBlank())
                && PluginBuild.shape(project, moduleDir)
                        .map(sh -> sh.mainScan())
                        .orElse(false)) {
            main = MainClassScanner.scanUnique(classes);
        }
        return main;
    }

    /**
     * The application SBOM entry + manifest headers shared by every packager: the lockfile's
     * production RUNTIME components as CycloneDX (see {@link CycloneDxSbom}). Returns null when
     * there is no lockfile to speak from.
     */
    static byte[] applicationSbom(JkBuild project, Lockfile lock, Cas cas) {
        List<CycloneDxSbom.Component> components = new ArrayList<>();
        for (ClasspathResolver.Entry entry : new ClasspathResolver(cas).entriesFor(lock, ClasspathResolver.RUNTIME)) {
            Lockfile.Artifact a = entry.artifact();
            components.add(
                    new CycloneDxSbom.Component(a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex()));
        }
        return CycloneDxSbom.write(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                components);
    }

    /** SBOM path inside plain/assembly application jars (jar root = classpath root). */
    static final String SBOM_JAR_ENTRY = "META-INF/sbom/application.cdx.json";
}
