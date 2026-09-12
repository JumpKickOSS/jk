// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerSupport.restorePackaged;
import static cc.jumpkick.runtime.PlannerSupport.storePackaged;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
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
import cc.jumpkick.runtime.base.SdkComponents;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
    static PluginBuild.@Nullable TaskDecl transformStep(PluginBuild.@Nullable Declarations decls) {
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
    static List<String> pluginRequires(PluginBuild.TaskDecl step, PluginBuild.@Nullable TaskDecl transform) {
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
                    // The shape of the entry list, in lock order. `cp:` is content only and
                    // ClasspathFingerprint.of sorts, so three things a packager writes verbatim
                    // were invisible to it: the ORDER (a boot jar's classpath.idx IS the launcher's
                    // classpath order), the SNAPSHOT flag (layers.idx partitions on it, and it is
                    // lockfile metadata, not bytes), and the FILE NAME and coordinate (the
                    // BOOT-INF/lib entry name, and what disambiguates a name collision). Reorder
                    // two dependencies with the identical resolved set and the artifact differs
                    // while the key did not.
                    StringBuilder shape = new StringBuilder();
                    for (PluginBuild.ProdEntry entry : src.runtimeEntries()) {
                        shape.append(entry.fileName())
                                .append('|')
                                .append(entry.snapshot())
                                .append('|')
                                .append(entry.group())
                                .append(':')
                                .append(entry.artifact())
                                .append(':')
                                .append(entry.version())
                                .append('\n');
                    }
                    tokens.add("entry-shape:" + Hashing.sha256Hex(shape.toString()));
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
     * arm and the packager arm share, for the same reason {@link #declaredInputTokens} exists. Both
     * arms render per-artifact {@code tool:<name>:<content>}; a combined {@code extras:} hash cannot
     * tell two tools apart by name at all.
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
            BuildPlanner.Ctx cx,
            PluginBuild.@Nullable Active declared,
            PluginBuild.TaskDecl step,
            PluginBuild.@Nullable TaskDecl transform) {
        // The step exists because this plugin declared it.
        PluginBuild.Active active = Objects.requireNonNull(declared, "active plugin");
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

                    // Manifest-contributed tool artifacts (aapt2, r8, a platform jar) — the ones
                    // this step reads, fetched into the cache, handed to the body by artifact
                    // name, keyed like any input.
                    Map<String, String> sdkPins = PluginBuild.sdkPins(in.lockFile());
                    List<PluginContributions.StepDep> tools =
                            cx.tools().forConsumer(project, in.dir(), in.lockFile(), step.name());
                    Map<String, Path> toolExtras = cx.tools().fetch(tools, project, cx.cas(), sdkPins);

                    // Action key: exactly the declared inputs, plus the very facts the body sees —
                    // the same ProjectFacts instance rides the spec below, so no fact can reach the
                    // plugin without reaching its key.
                    ProjectFacts facts = PluginBuild.facts(project, startClass);
                    List<String> tokens = new ArrayList<>(declaredInputTokens(
                            step.inputs(),
                            new InputSources(classes, classpath, prodEntries, active.config(), layout, in.dir())));
                    tokens.addAll(toolTokens(tools, toolExtras, sdkPins));
                    tokens.add("facts:" + facts.token());
                    // The JDK is handed to the body as spec.javaHome and is what its forked tools
                    // (d8, aapt2, a compiler plugin) run on and compile against — ProjectFacts
                    // carries `release`, which is a different fact entirely. Without this,
                    // switching jdk = 17 to 21 moves no plugin step key and every one of the SPI
                    // plugins restores output built against the old platform.
                    tokens.add("jdk:" + ActionKey.jdkToken(javaHome));
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

                    PathUtil.deleteRecursively(scratch); // stale outputs never survive
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
                        PluginBuild.runWorker(active, in.cache(), spec, workerEnv(ctx, in), ctx::label);
                    } catch (IOException e) {
                        ctx.error(step.name(), Errors.text(e));
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
            PluginBuild.StepTools tools,
            JkBuild project,
            Path classes,
            Path jarPath,
            PluginBuild.@Nullable Active declaredActive,
            PluginBuild.Declarations decls,
            Map<String, String> secrets)
            throws Exception {
        // A plugin packager runs because this plugin declared one.
        PluginBuild.Active active = Objects.requireNonNull(declaredActive, "active plugin");
        Lockfile lock = ctx.require(LOCKFILE);
        BuildLayout layout = ctx.require(LAYOUT);
        ClasspathResolver resolver = new ClasspathResolver(cas);
        // Key AND spec from one derivation (PackagingKeys): the facts, runtime entries and tool
        // artifacts the packager body receives below are the very objects that keyed its output,
        // so nothing can reach the plugin without reaching its key — and `jk explain` prices this
        // step by calling the same body, so it can no longer forecast the plain jar's key for a
        // module the plain packager never touches.
        PackagingKeys.PackagerKey packaging = PackagingKeys.pluginPackager(new PackagingKeys.Packager(
                project,
                in.dir(),
                in.cache(),
                in.lockFile(),
                cas,
                tools,
                layout,
                classes,
                jarPath,
                ctx.require(JAVA_HOME),
                active,
                decls,
                secrets));
        ProjectFacts facts = packaging.facts();
        List<PluginBuild.ProdEntry> entries = packaging.entries();
        Map<String, Path> extras = packaging.extras();
        List<CycloneDxSbom.Component> sbomComponents = new ArrayList<>();
        for (ClasspathResolver.Entry entry : resolver.entriesFor(lock, ClasspathResolver.RUNTIME)) {
            Lockfile.Artifact a = entry.artifact();
            sbomComponents.add(
                    new CycloneDxSbom.Component(a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex()));
        }
        String pkgTask = packaging.keyed().taskId();
        String pkgKey = packaging.keyed().key();
        if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
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
            workerLines = PluginBuild.runWorker(active, in.cache(), specFile, workerEnv(ctx, in), ctx::label);
        } catch (IOException e) {
            ctx.error("package", Errors.text(e));
            throw e;
        } finally {
            Files.deleteIfExists(specFile);
            Files.deleteIfExists(sbomFile);
        }
        PluginBuild.PackagerDecl packager = Objects.requireNonNull(decls.packager(), "packager");
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException(
                    "plugin packager " + packager.name() + " reported success but produced no " + jarPath);
        }
        // An artifact written anew is a build, whatever the compile step found cached.
        ctx.put(BUILD_OUTCOME, "built");
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
        Path outBase = Objects.requireNonNull(jarPath.getParent(), "artifact dir")
                .toAbsolutePath()
                .normalize();
        for (String line : workerLines) {
            if (!"produced".equals(Jsonl.str(line, "t"))) continue;
            Path p = Path.of(String.valueOf(Jsonl.str(line, "path")))
                    .toAbsolutePath()
                    .normalize();
            if (!p.startsWith(outBase)) {
                throw new IOException("packager declared produced path outside the artifact dir: " + p);
            }
            if (Files.isRegularFile(p)) {
                produced.add(p);
            } else if (Files.isDirectory(p)) {
                PathUtil.forEachRegularFile(p, (file, attrs) -> produced.add(file));
            }
        }
        storePackaged(
                in.cache(),
                pkgTask,
                pkgKey,
                packaging.keyed().tokens(),
                jarPath.getParent(),
                produced,
                !in.ephemeralActions());
        ctx.progress(1);
    }

    /** The resolved application entry point: declared, else the unique compiled main (when scannable). */
    static @Nullable String resolvedMain(JkBuild project, Path moduleDir, Path classes) throws IOException {
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

    /** The module's {@code [env]} policy for a plugin step's worker, {@code ${target}} being its output root. */
    private static WorkerEnv workerEnv(TaskContext ctx, BuildPlanner.Inputs in) {
        return WorkerEnv.forModule(
                ctx.require(PROJECT).build().env(),
                in.dir(),
                ctx.require(LAYOUT).moduleTargetDir());
    }
}
