// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerSupport.copyResources;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.ModuleLayoutPlugins;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * copy-resources and stem-script build-logic anchors around compile / package.
 */
public final class PlannerResources {

    private PlannerResources() {}

    static Task copyResourcesStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        AtomicReference<@Nullable List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COPY_RESOURCES)
                .stage(BuildLogicAnchor.AFTER_RESOURCES.stage())
                .label("Resources")
                .kind(TaskKind.CPU)
                // After AFTER_COMPILE so generated classes land before resource merge.
                .requires(TaskNames.BUILD_LOGIC_AFTER_COMPILE)
                .weight(() -> plan.get().fullyCached() ? 0 : W_RESOURCES)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    // SIMPLE uses top-level resources/; TRADITIONAL uses src/main/resources.
                    // Plugin-contributed resource roots (grails-app/conf, i18n, views) merge after.
                    List<Path> resDirs = new ArrayList<>();
                    Path resMain = ModuleLayout.mainResourcesDir(in.dir(), compact);
                    if (Files.isDirectory(resMain)) resDirs.add(resMain);
                    for (var root : ModuleLayoutPlugins.pluginContributedRoots(in.dir())) {
                        if (!root.resource()) continue;
                        Path dir = in.dir().resolve(root.relative());
                        if (Files.isDirectory(dir)) resDirs.add(dir);
                    }
                    Path pluginManifest = in.dir().resolve(ManifestPaths.PLUGIN_MANIFEST);
                    boolean ownManifest = Files.isRegularFile(pluginManifest);
                    // Orphan reconciliation: a manifest deleted (or renamed away) at the module
                    // root must also leave the classes tree, or the jar keeps describing a
                    // plugin that no longer exists.
                    if (!ownManifest) {
                        Files.deleteIfExists(classes.resolve(ManifestPaths.PLUGIN_MANIFEST));
                    }
                    boolean copied = false;
                    if (!resDirs.isEmpty() || ownManifest) {
                        ctx.label("copy resources");
                        for (Path dir : resDirs) copyResources(dir, classes);
                        if (ownManifest) {
                            Files.copy(
                                    pluginManifest,
                                    classes.resolve(ManifestPaths.PLUGIN_MANIFEST),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                        copied = true;
                    } else {
                        ctx.label("no static resources");
                    }
                    // Test-classpath fixtures must not ride main classes into the jar. Leftover
                    // extra-resources copies of *.jk-plugin.toml (and scaffold trees) are stripped
                    // after the merge so a skipped copy step cannot keep poisoning PluginTableRegistry.
                    boolean stripped = stripFlattenedPluginCatalog(
                            classes, name -> ctx.warn("resources", "stripped leftover plugin-catalog copy: " + name));
                    if (stripped) ctx.label("stripped leftover plugin catalog");
                    // Project build logic: AFTER_RESOURCES stem scripts.
                    boolean logicRan = false;
                    try {
                        logicRan = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                BuildLogicAnchor.AFTER_RESOURCES,
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokensRef);
                        if (logicRan) ctx.label("build-logic applied");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    if (!copied && !logicRan && !stripped) ctx.cached(); // SKIPPED — nothing to copy, no logic
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Delete jk's OWN flattened catalog fixtures — the {@code BUILT_IN} manifest names and their
     * scaffold trees — from main classes. Those files are test-classpath fixtures; production jars
     * must not bake a flattened catalog. Restricted to the built-in names on purpose: a user
     * project may legitimately ship resources under {@code cc/jumpkick/plugin/manifest/}, and the
     * old delete-anything sweep silently kept them out of the jar while re-running resources every
     * build (copy → strip → drift → copy …). Every deletion is reported through {@code warn}.
     */
    static boolean stripFlattenedPluginCatalog(Path classesDir, Consumer<String> warn) throws IOException {
        Path catalog = classesDir.resolve(Path.of("cc", "jumpkick", "plugin", "manifest"));
        if (!Files.isDirectory(catalog)) return false;
        List<String> builtIn = PluginTableRegistry.builtInManifestNames();
        boolean stripped = false;
        List<Path> children;
        try (var stream = Files.list(catalog)) {
            children = stream.toList();
        }
        for (Path p : children) {
            String name = p.getFileName().toString();
            if (Files.isRegularFile(p) && builtIn.contains(name)) {
                Files.delete(p);
                warn.accept(name);
                stripped = true;
            } else if (Files.isDirectory(p) && builtIn.contains(name + ".jk-plugin.toml") && !containsClassFiles(p)) {
                PathUtil.deleteRecursivelyOrThrow(p);
                warn.accept(name + "/");
                stripped = true;
            }
        }
        return stripped;
    }

    private static boolean containsClassFiles(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(
                    p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".class"));
        }
    }

    /**
     * Anchor {@code BEFORE_COMPILE}: stem-script tasks before main language compile (codegen).
     * Product stage {@link BuildStage#GENERATE}.
     */
    static Task buildLogicBeforeCompileStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_BEFORE_COMPILE)
                .stage(BuildLogicAnchor.BEFORE_COMPILE.stage())
                .label("Build logic (before compile)")
                .kind(TaskKind.CPU)
                .requires(TaskNames.PARSE_BUILD, TaskNames.RESOLVE_DEPS, TaskNames.ENSURE_JDK)
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    try {
                        boolean ran = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                BuildLogicAnchor.BEFORE_COMPILE,
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokensRef);
                        if (ran) ctx.label("build-logic applied");
                        else ctx.cached(); // SKIPPED — no generate/before-compile logic this run
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** Anchor {@code AFTER_COMPILE}: stem-script tasks after main classes exist. */
    static Task buildLogicAfterCompileStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        String mainCompile = cx.mainCompile();
        AtomicReference<@Nullable List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_AFTER_COMPILE)
                .stage(BuildLogicAnchor.AFTER_COMPILE.stage())
                .label("Build logic (after compile)")
                .kind(TaskKind.CPU)
                .requires(mainCompile)
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    try {
                        boolean ran = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                BuildLogicAnchor.AFTER_COMPILE,
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokensRef);
                        if (ran) ctx.label("build-logic applied");
                        else ctx.cached(); // SKIPPED — no after-compile logic this run
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /** Anchor {@code BEFORE_PACKAGE}: stem-script tasks immediately before jar/image. */
    static Task buildLogicBeforePackageStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE)
                .stage(BuildLogicAnchor.BEFORE_PACKAGE.stage())
                .label("Build logic (before package)")
                .kind(TaskKind.CPU)
                .requires(beforePackageRequires(in))
                .weight(() -> plan.get().fullyCached() ? 0 : 1)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    try {
                        boolean ran = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                BuildLogicAnchor.BEFORE_PACKAGE,
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokensRef);
                        if (ran) ctx.label("build-logic applied");
                        else ctx.cached(); // SKIPPED — no before-package logic this run
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
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
        AtomicReference<@Nullable List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_AFTER_BUILD)
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
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokensRef);
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
        AtomicReference<@Nullable List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_GUARD)
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
                                ctx::label,
                                ctx::output,
                                buildLogicInputTokensRef);
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
            req = new String[] {includeTests ? TaskNames.RUN_TESTS : TaskNames.COPY_RESOURCES};
        } else if (includeTests) {
            req = new String[] {TaskNames.RUN_TESTS, TaskNames.PACKAGE_JAR};
        } else {
            req = new String[] {TaskNames.PACKAGE_JAR};
        }
        b.addTask(buildLogicGateStep(cx, req));
        return TaskNames.BUILD_LOGIC_GUARD;
    }

    /**
     * BEFORE_PACKAGE waits on resources only — never on tests. Packaging needs a complete
     * classes tree, which tests do not contribute to. A failing suite still fails the build;
     * the artifact is built concurrently.
     */
    static String[] beforePackageRequires(BuildPlanner.Inputs in) {
        return new String[] {TaskNames.COPY_RESOURCES};
    }
}
