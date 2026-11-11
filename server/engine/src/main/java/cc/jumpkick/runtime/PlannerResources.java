// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.BuildLogicAnchor;
import cc.jumpkick.runtime.base.FilteredResources;
import cc.jumpkick.runtime.base.ProcessedResources;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.MirroredOutputs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * copy-resources and stem-script build-logic anchors around compile / package. The resources are
 * copied before the compile, so {@code after-resources} scripts run before it too.
 */
public final class PlannerResources {

    private PlannerResources() {}

    /**
     * copy-resources: the main resources into the classes tree, then the {@code after-resources}
     * stem scripts. It runs ahead of every language compile, as Maven's {@code process-resources}
     * runs ahead of {@code compile}: an annotation processor reads the copies through the Filer, and
     * a file it writes at a copy's path replaces the copy — the compile's output wins.
     */
    static Task copyResourcesStep(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        Supplier<EffortWeights.Plan> plan = cx.plan();
        return Task.builder(TaskNames.COPY_RESOURCES)
                .forecast(StepForecast.priced(s -> forecastCopy(ForecastScope.of(s))))
                .stage(BuildLogicAnchor.AFTER_RESOURCES.stage())
                .label("Resources")
                .kind(TaskKind.CPU)
                // Before the compiles, after what lays down resource roots: codegen, the KSP round
                // and the plugin source generators.
                .requires(copyResourcesRequires(pluginDecls, cx.ksp()))
                .weight(() -> plan.get().fullyCached() ? 0 : W_RESOURCES)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes);
                    boolean copied = mirrorMainResources(ctx, cx.in().dir(), cx.compact(), classes);
                    // Test-classpath fixtures must not ride main classes into the jar. Leftover
                    // extra-resources copies of *.jk-plugin.toml (and scaffold trees) are stripped
                    // after the merge so a skipped copy step cannot keep poisoning PluginTableRegistry.
                    boolean stripped = stripFlattenedPluginCatalog(
                            classes, name -> ctx.warn("resources", "stripped leftover plugin-catalog copy: " + name));
                    if (stripped) ctx.label("stripped leftover plugin catalog");
                    boolean logicRan = runAnchor(ctx, cx, classes, BuildLogicAnchor.AFTER_RESOURCES);
                    if (!copied && !logicRan && !stripped) ctx.cached(); // SKIPPED — nothing to copy, no logic
                    ctx.progress(1);
                })
                .build();
    }

    /** copy-resources waits for every step that writes a root it copies, and for nothing that compiles. */
    static String[] copyResourcesRequires(@Nullable PluginDeclarations pluginDecls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        if (ksp) requires.add(TaskNames.KSP);
        requires.addAll(PlannerKsp.sourceGenStepSteps(pluginDecls));
        return requires.toArray(String[]::new);
    }

    /**
     * Mirror the main resource roots into {@code classes}: the KSP round's output first, then the
     * layout root, the extra and filtered roots and the plugin-contributed ones, so a hand-written
     * resource at the same path wins; then a module-root plugin manifest. Records which resource
     * each copy came from, for the compile that follows. Answers whether a file was written or
     * removed.
     */
    private static boolean mirrorMainResources(TaskContext ctx, Path dir, boolean compact, Path classes)
            throws IOException {
        BuildLayout layout = ctx.require(LAYOUT);
        Set<String> unresolved = new TreeSet<>();
        List<Path> resDirs = new ArrayList<>(PlannerKsp.outputRoots(PlannerKsp.kspOutBase(layout)));
        resDirs.addAll(FilteredResources.mainRoots(dir, ctx.require(PROJECT), layout, compact, unresolved));
        warnUnresolved(ctx, unresolved);
        for (var root : ModuleLayoutPlugins.pluginContributedRoots(dir)) {
            if (!root.resource()) continue;
            Path rootDir = dir.resolve(root.relative());
            if (Files.isDirectory(rootDir)) resDirs.add(rootDir);
        }
        // A manifest deleted (or renamed away) at the module root leaves the classes tree with
        // the rest of the mirror, or the jar keeps describing a plugin that no longer exists.
        Path pluginManifest = dir.resolve(ManifestPaths.PLUGIN_MANIFEST);
        Map<String, Path> files =
                Files.isRegularFile(pluginManifest) ? Map.of(ManifestPaths.PLUGIN_MANIFEST, pluginManifest) : Map.of();
        if (files.isEmpty()) Files.deleteIfExists(classes.resolve(ManifestPaths.PLUGIN_MANIFEST));
        // Mirrored through a ledger even when no root is left: the copy of a resource deleted
        // since the last build is removed before anything is copied.
        Path incremental = layout.buildDir().resolve("incremental");
        Path ledger = incremental.resolve(ResourceMirror.LEDGER);
        ctx.label(resDirs.isEmpty() && files.isEmpty() ? "no static resources" : "copy resources");
        ResourceMirror.Main mirrored =
                ResourceMirror.syncMain(resDirs, files, classes, ledger, ProcessedResources.read(incremental));
        MirroredOutputs.writeSources(incremental, mirrored.sources());
        // A tree whose files all match their copies is not work: the step reports SKIPPED.
        return mirrored.changes() > 0;
    }

    /** Run {@code anchor}'s stem scripts over {@code classes}; whether any ran. */
    private static boolean runAnchor(TaskContext ctx, BuildPlanner.Ctx cx, Path classes, BuildLogicAnchor anchor)
            throws IOException {
        try {
            boolean ran = BuildLogicSupport.run(
                    cx.in().dir(),
                    ctx.require(LAYOUT),
                    cx.actionCache(),
                    classes,
                    anchor,
                    cx.buildLogicScope(),
                    ctx::label,
                    ctx::output,
                    cx.buildLogicInputTokens(),
                    ctx::cancelled);
            if (ran) ctx.label("build-logic applied");
            return ran;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("build-logic interrupted", e);
        }
    }

    /** One warning per filtered-resource reference that had no value. */
    static void warnUnresolved(TaskContext ctx, Set<String> unresolved) {
        for (String name : unresolved) {
            ctx.warn(
                    "resources",
                    "${" + name + "} has no value and is copied as written; set it under [resources.properties]");
        }
    }

    /**
     * Delete jk's OWN flattened catalog fixtures — the {@code BUILT_IN} manifest names and their
     * scaffold trees — from main classes. Those files are test-classpath fixtures; production jars
     * must not bake a flattened catalog. Restricted to the built-in names on purpose: a user
     * project may legitimately ship resources under {@code cc/jumpkick/plugin/manifest/}, and
     * stripping those would keep them out of the jar while re-running resources every build.
     * Every deletion is reported through {@code warn}.
     */
    static boolean stripFlattenedPluginCatalog(Path classesDir, Consumer<String> warn) throws IOException {
        Path catalog = classesDir.resolve(Path.of("cc", "jumpkick", "plugin", "manifest"));
        if (!Files.isDirectory(catalog)) return false;
        List<String> builtIn = PluginTableRegistry.builtInManifestNames();
        List<Path> files = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        PathUtil.forEachChild(catalog, (p, attrs) -> {
            String name = p.getFileName().toString();
            if (attrs.isRegularFile() && builtIn.contains(name)) files.add(p);
            else if (attrs.isDirectory() && builtIn.contains(name + ".jk-plugin.toml")) dirs.add(p);
            return true;
        });
        boolean stripped = false;
        for (Path p : files) {
            Files.delete(p);
            warn.accept(p.getFileName().toString());
            stripped = true;
        }
        for (Path p : dirs) {
            if (PathUtil.anyRegularFile(
                    p, d -> false, f -> f.getFileName().toString().endsWith(".class"))) continue;
            PathUtil.deleteRecursivelyOrThrow(p);
            warn.accept(p.getFileName() + "/");
            stripped = true;
        }
        return stripped;
    }

    /**
     * Anchor {@code BEFORE_COMPILE}: stem-script tasks before main language compile (codegen).
     * Product stage {@link BuildStage#GENERATE}.
     */
    static Task buildLogicBeforeCompileStep(BuildPlanner.Ctx cx) {
        return classesAnchorStep(
                cx,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE,
                "Build logic (before compile)",
                BuildLogicAnchor.BEFORE_COMPILE,
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK);
    }

    /**
     * Anchor {@code AFTER_COMPILE}: stem-script tasks after main classes exist. The resources were
     * copied before the compile, so this is the step after which the main classes tree is whole
     * but for {@code build-info} and {@code module-services}; it waits for the copy too, which a
     * module with no compile does not reach through one.
     */
    static Task buildLogicAfterCompileStep(BuildPlanner.Ctx cx) {
        return classesAnchorStep(
                cx,
                TaskNames.BUILD_LOGIC_AFTER_COMPILE,
                "Build logic (after compile)",
                BuildLogicAnchor.AFTER_COMPILE,
                cx.mainCompile(),
                TaskNames.COPY_RESOURCES);
    }

    /** Anchor {@code BEFORE_PACKAGE}: stem-script tasks immediately before jar/image. */
    static Task buildLogicBeforePackageStep(BuildPlanner.Ctx cx, boolean buildInfo, boolean moduleServices) {
        return classesAnchorStep(
                cx,
                TaskNames.BUILD_LOGIC_BEFORE_PACKAGE,
                "Build logic (before package)",
                BuildLogicAnchor.BEFORE_PACKAGE,
                beforePackageRequires(buildInfo, moduleServices));
    }

    /** A module anchor's step: its stem scripts over the main classes tree, SKIPPED when none ran. */
    private static Task classesAnchorStep(
            BuildPlanner.Ctx cx, String name, String label, BuildLogicAnchor anchor, String... requires) {
        Supplier<EffortWeights.Plan> plan = cx.plan();
        return Task.builder(name)
                .forecast(StepForecast.RIDES)
                .stage(anchor.stage())
                .label(label)
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    if (!runAnchor(ctx, cx, ctx.require(MAIN_CLASSES), anchor))
                        ctx.cached(); // SKIPPED — no logic this run
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Anchor {@code AFTER_BUILD}: the workspace root's stem scripts, after every member module has
     * built.
     *
     * <p>The root unit's whole plan is parse / resolve / this, so there is no classes tree to pass
     * and nothing merges. Ordering comes from the graph — the sourceless root carries an edge to
     * every member — not from a `requires` here, which could only name tasks in the root's own
     * plan.
     */
    static Task buildLogicAfterBuildStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        BuildLogicInputTokens buildLogicInputTokens = cx.buildLogicInputTokens();
        return Task.builder(TaskNames.BUILD_LOGIC_AFTER_BUILD)
                .forecast(StepForecast.RIDES)
                .stage(BuildLogicAnchor.AFTER_BUILD.stage())
                .label("Build logic (after build)")
                .kind(TaskKind.CPU)
                .requires(TaskNames.RESOLVE_DEPS)
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    try {
                        boolean ran = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                /* classesDir */ null,
                                BuildLogicAnchor.AFTER_BUILD,
                                cx.buildLogicScope(),
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokens,
                                ctx::cancelled);
                        if (ran) ctx.label("build-logic applied");
                        else ctx.cached(); // SKIPPED — no workspace build logic this run
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Anchor {@code GUARD}: invocation-root stem scripts bound to {@code --guard} /
     * {@code --scripts-only}. Same cache and bindings as {@link #buildLogicAfterBuildStep}.
     */
    static Task buildLogicGateStep(BuildPlanner.Ctx cx, String... requires) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        BuildLogicInputTokens buildLogicInputTokens = cx.buildLogicInputTokens();
        return Task.builder(TaskNames.BUILD_LOGIC_GUARD)
                .forecast(StepForecast.RIDES)
                .stage(BuildLogicAnchor.GUARD.stage())
                .label("Build logic (guard)")
                .kind(TaskKind.CPU)
                .requires(requires)
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    try {
                        boolean ran = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                /* classesDir */ null,
                                BuildLogicAnchor.GUARD,
                                cx.buildLogicScope(),
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokens,
                                ctx::cancelled);
                        if (ran) ctx.label("build-logic applied");
                        else ctx.cached();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    static boolean skipJUnit(BuildPlanner.Inputs in) {
        TestSelection s =
                in.session() == null ? TestSelection.DEFAULT : in.session().testSelection();
        if (s.scriptsOnly()) return true;
        return !in.testOnly() && in.skipTests();
    }

    static boolean runGuardScripts(BuildPlanner.Inputs in) {
        return in.session() != null && in.session().testSelection().runGuardScripts();
    }

    static boolean invocationRoot(Path dir) {
        return WorkspaceScan.isWorkspaceRoot(dir) || WorkspaceScan.findRoot(dir).isEmpty();
    }

    /**
     * Append the GUARD step when this unit is the invocation root and the session asked for
     * scripts. Returns the terminal name, or {@code null} when GUARD is not on this plan.
     */
    static @Nullable String appendGate(
            BuildPlan.Builder b, BuildPlanner.Ctx cx, boolean includeTests, boolean testOnly, boolean afterBuild) {
        BuildPlanner.Inputs in = cx.in();
        if (!runGuardScripts(in) || !invocationRoot(in.dir())) return null;
        if (in.session().testSelection().scriptsOnly() && !BuildLogicToml.hasStem(in.dir(), "guard")) {
            throw new IllegalArgumentException(BuildLogicToml.NO_GUARD_SCRIPTS);
        }
        String[] req;
        if (afterBuild) {
            req = new String[] {TaskNames.BUILD_LOGIC_AFTER_BUILD};
        } else if (testOnly) {
            req = new String[] {includeTests ? TaskNames.RUN_TESTS : CLASSES_WHOLE};
        } else if (includeTests) {
            req = new String[] {TaskNames.RUN_TESTS, TaskNames.PACKAGE_JAR};
        } else {
            req = new String[] {TaskNames.PACKAGE_JAR};
        }
        b.addTask(buildLogicGateStep(cx, req));
        return TaskNames.BUILD_LOGIC_GUARD;
    }

    /**
     * The step after which the main classes tree holds the compile's output and the resources:
     * the copy runs before the compile and the {@code after-compile} scripts after it, waiting for
     * both. Only {@code build-info} and {@code module-services} write the tree later.
     */
    static final String CLASSES_WHOLE = TaskNames.BUILD_LOGIC_AFTER_COMPILE;

    /**
     * BEFORE_PACKAGE waits on the classes tree only — never on tests. Packaging needs a complete
     * classes tree, which tests do not contribute to. A failing suite still fails the build;
     * the artifact is built concurrently. {@code [build-info]} and {@code [build] module-services}
     * write that tree after the compile, each its own files, so either one present is a last writer
     * to wait for; without them {@link #CLASSES_WHOLE} is.
     */
    static String[] beforePackageRequires(boolean buildInfo, boolean moduleServices) {
        return lastClassesWriters(buildInfo, moduleServices).toArray(String[]::new);
    }

    /**
     * The steps that finish the main classes tree: {@code build-info} and {@code module-services}
     * when planned (both follow {@link #CLASSES_WHOLE} and write disjoint files), else {@link
     * #CLASSES_WHOLE} itself.
     */
    static List<String> lastClassesWriters(boolean buildInfo, boolean moduleServices) {
        List<String> out = new ArrayList<>();
        if (buildInfo) out.add(TaskNames.BUILD_INFO);
        if (moduleServices) out.add(TaskNames.MODULE_SERVICES);
        if (out.isEmpty()) out.add(CLASSES_WHOLE);
        return out;
    }

    // ---- forecast ---------------------------------------------------------------------------

    /**
     * copy-resources' price: runs when the main resources (or the {@code [build-info]} files) drifted
     * from the classes tree. Detected once, before package-jar keys on the post-copy tree; a compile
     * that runs makes drift moot. A copy a processor rewrote is not drift while its resource is
     * unchanged ({@link ProcessedResources}).
     */
    static TaskForecast.@Nullable Task forecastCopy(ForecastScope s) throws Exception {
        ForecastScope.Prepared p = s.prepared();
        Path classes = p.layout().classesDir();
        if (s.compileDirty || !Files.isDirectory(classes)) return null;
        // A KSP round that runs mirrors output nothing knows yet into the tree.
        s.mainResourceDrift =
                s.kspRuns() || TaskForecaster.mainResourcesOutOfSync(s.dir, s.project, p.compact(), classes);
        BuildBlock.BuildInfo buildInfo = s.project.build().buildInfo();
        if (buildInfo != null) {
            // A rewritten git.properties changes the classes tree package-jar keys on, so the
            // jar is forecast as the resource drift it is.
            s.buildInfoDrift = PlannerBuildInfo.outOfSync(s.dir, s.project, buildInfo, classes, Clock.SYSTEM);
            s.mainResourceDrift |= s.buildInfoDrift;
        }
        if (s.project.build().moduleServices()) {
            // Services files written from module-info change the tree package-jar keys on, too.
            s.moduleServicesDrift =
                    PlannerModuleServices.outOfSync(classes, PlannerModuleServices.incremental(p.layout()));
            s.mainResourceDrift |= s.moduleServicesDrift;
        }
        s.knownResourceDrift = s.mainResourceDrift;
        return s.mainResourceDrift
                ? new TaskForecast.Task(TaskNames.COPY_RESOURCES, TaskForecast.Status.RUN, "resources changed", null)
                : null;
    }
}
