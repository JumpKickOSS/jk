// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                    java.util.Map<String, Path> toolExtras = PluginBuild.fetchStepDependencies(
                            project, in.dir(), cx.cas(), PluginBuild.sdkPins(in.lockFile()));

                    // Action key: exactly the declared inputs, plus the facts the body sees.
                    List<String> tokens = new ArrayList<>();
                    for (String input : step.inputs()) {
                        switch (input) {
                            case "classes" ->
                                tokens.add("classes:" + cc.jumpkick.task.ClasspathFingerprint.entry(classes));
                            case "runtime-classpath" ->
                                tokens.add("cp:" + cc.jumpkick.task.ClasspathFingerprint.of(classpath));
                            case "runtime-entries" -> {
                                tokens.add("cp:" + cc.jumpkick.task.ClasspathFingerprint.of(classpath));
                                for (var pe : prodEntries) {
                                    if (pe.container() != null) {
                                        tokens.add("container:" + pe.fileName() + ":"
                                                + cc.jumpkick.task.ClasspathFingerprint.entry(pe.container()));
                                    }
                                }
                            }
                            case "config" -> tokens.add("config:" + PluginBuild.configToken(active.config()));
                            default -> {
                                if (input.startsWith("step:")) {
                                    Path other = PluginBuild.taskScratch(layout, input.substring("step:".length()));
                                    tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(other));
                                } else if (input.startsWith("project:")) {
                                    Path files = in.dir().resolve(input.substring("project:".length()));
                                    tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(files));
                                }
                            }
                        }
                    }
                    for (var tool : toolExtras.entrySet()) {
                        tokens.add("tool:" + tool.getKey() + ":"
                                + cc.jumpkick.task.ClasspathFingerprint.entry(tool.getValue()));
                    }
                    tokens.add("facts:" + project.project().group() + ":"
                            + project.project().name() + ":"
                            + project.project().version() + ":"
                            + project.project().javaRelease() + ":"
                            + startClass);
                    // The step's CODE is an input: a changed plugin jar must re-run the
                    // step, or a plugin upgrade (or first-party dev iteration) silently restores
                    // outputs produced by the old code.
                    tokens.add("worker:"
                            + cc.jumpkick.task.ClasspathFingerprint.entry(
                                    PluginBuild.workerJarFor(active, in.cache())));
                    String taskId = ActionKey.qualifiedTaskId("plugin-" + step.name(), scratch);
                    String actionKey =
                            ActionKey.forArtifact(taskId, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), tokens);
                    cc.jumpkick.task.ActionCache actionCache = cx.actionCache();
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

                    cc.jumpkick.util.PathUtil.deleteRecursively(scratch); // stale outputs never survive
                    Files.createDirectories(scratch);
                    ctx.label(step.name());
                    PluginBuild.SpecWriter specWriter = new PluginBuild.SpecWriter()
                            .op("run-step", step.name(), active.manifest().id())
                            .config(active.config())
                            .project(project, startClass)
                            .layout(classes, in.dir(), scratch)
                            .javaHome(javaHome)
                            .classpath(classpath);
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
                    Path spec = specWriter.write();
                    try {
                        PluginBuild.runWorker(active, in.cache(), spec, ctx::label);
                    } catch (IOException e) {
                        ctx.error(step.name(), e.getMessage());
                        throw e;
                    } finally {
                        Files.deleteIfExists(spec);
                    }
                    actionCache.store(taskId, actionKey, java.util.Map.of(), scratch);
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

        // Coordinate-named runtime entries: lock artifacts + workspace sibling jars — the SAME
        // set steps see via In.runtimeEntries(). Packaging from the lock alone drops sibling
        // module jars and ships a Boot/assembly artifact that cannot start (JK-1415).
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
        java.util.Map<String, Path> extras = new LinkedHashMap<>(
                PluginBuild.fetchStepDependencies(project, in.dir(), cas, PluginBuild.sdkPins(in.lockFile())));
        extras.putAll(PluginBuild.fetchPackagerDependencies(project, in.dir(), cas));

        // Action key from the declared inputs + facts — any config, classes, dependency-set,
        // step-output, extra-artifact, or manifest change re-packages; nothing else does.
        List<Path> entryJars = new ArrayList<>(entries.size());
        for (PluginBuild.ProdEntry e : entries) {
            if (e.jar() != null) entryJars.add(e.jar());
        }
        List<String> tokens = new ArrayList<>();
        for (String input : decls.packager().inputs()) {
            switch (input) {
                case "classes" -> tokens.add("classes:" + cc.jumpkick.task.ClasspathFingerprint.entry(classes));
                case "runtime-classpath", "runtime-entries" -> {
                    tokens.add("libs:" + cc.jumpkick.task.ClasspathFingerprint.of(entryJars));
                    // Container content (an AAR's res/assets/jni) is packaged input too — an
                    // assets-only AAR bump must re-package even though no classes jar changed.
                    for (PluginBuild.ProdEntry e : entries) {
                        if (e.container() != null) {
                            tokens.add("container:" + e.fileName() + ":"
                                    + cc.jumpkick.task.ClasspathFingerprint.entry(e.container()));
                        }
                    }
                }
                case "config" -> tokens.add("config:" + PluginBuild.configToken(active.config()));
                default -> {
                    if (input.startsWith("step:")) {
                        Path other = PluginBuild.taskScratch(layout, input.substring("step:".length()));
                        tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(other));
                    } else if (input.startsWith("project:")) {
                        Path files = in.dir().resolve(input.substring("project:".length()));
                        tokens.add(input + ":" + cc.jumpkick.task.ClasspathFingerprint.entry(files));
                    }
                }
            }
        }
        List<Path> extraJars = new ArrayList<>(extras.values());
        tokens.add("extras:" + cc.jumpkick.task.ClasspathFingerprint.of(extraJars));
        if (!secrets.isEmpty()) {
            // A changed signing credential re-signs (the signature is part of the artifact);
            // the key carries only a digest — a secret value never appears anywhere readable.
            StringBuilder sb = new StringBuilder();
            for (var e : new java.util.TreeMap<>(secrets).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            tokens.add("secrets:"
                    + cc.jumpkick.util.Hashing.sha256Hex(
                            sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        tokens.add("facts:" + project.project().group() + ":"
                + project.project().name() + ":" + project.project().version() + ":" + startClass);
        tokens.add("manifest:" + project.manifest());
        // Packager identity (e.g. shrink vs boot) so CLI packaging overrides cannot cache-collide.
        tokens.add("packaging:" + decls.packager().name());
        // The packager's CODE is an input, same as plugin steps (see pluginTask).
        tokens.add(
                "worker:" + cc.jumpkick.task.ClasspathFingerprint.entry(PluginBuild.workerJarFor(active, in.cache())));
        // The minified packager folds `jk train` observations into its keep rules out-of-band
        // (same path derivation as MinifiedJarPackager.produce). Absence and every content state
        // must be distinct keys — otherwise a post-train rebuild restores the pre-train jar as
        // "up-to-date" and training never reaches the shipped artifact (JK-1751).
        if ("minified-jar".equals(decls.packager().name())) {
            Path trainSurface = jarPath.getParent()
                    .resolve(cc.jumpkick.surface.TrainLayout.ROOT)
                    .resolve("merged")
                    .resolve(cc.jumpkick.surface.TrainLayout.SURFACE_JSON);
            tokens.add("train:"
                    + (Files.isRegularFile(trainSurface)
                            ? cc.jumpkick.task.ClasspathFingerprint.entry(trainSurface)
                            : "absent"));
        }
        String pkgTask = ActionKey.qualifiedTaskId(TaskNames.PACKAGE_JAR, jarPath);
        String pkgKey = ActionKey.forArtifact(pkgTask, cc.jumpkick.model.BuildIdentity.cacheKeyVersion(), tokens);
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

        PluginBuild.SpecWriter spec = new PluginBuild.SpecWriter()
                .op("package", null, active.manifest().id())
                .config(active.config())
                .project(project, startClass)
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
        Path specFile = spec.write();
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
            if (!"produced".equals(cc.jumpkick.plugin.protocol.Jsonl.str(line, "t"))) continue;
            Path p = Path.of(String.valueOf(cc.jumpkick.plugin.protocol.Jsonl.str(line, "path")))
                    .toAbsolutePath()
                    .normalize();
            if (!p.startsWith(outBase)) {
                throw new IOException("packager declared produced path outside the artifact dir: " + p);
            }
            if (Files.isRegularFile(p)) {
                produced.add(p);
            } else if (Files.isDirectory(p)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(p)) {
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
            main = cc.jumpkick.layout.MainClassScanner.scanUnique(classes);
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
