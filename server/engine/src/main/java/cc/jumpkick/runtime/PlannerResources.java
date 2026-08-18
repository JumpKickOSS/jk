// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.BuildStage;
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
import java.util.function.Supplier;

/**
 * copy-resources and SPI build-logic anchors around compile / package.
 */
public final class PlannerResources {

    private PlannerResources() {}

    static Task copyResourcesStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        AtomicReference<List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COPY_RESOURCES)
                .stage(BuildStage.COMPILE)
                .label("Resources")
                .kind(TaskKind.CPU)
                // After AFTER_COMPILE SPI so generated classes land before resource merge.
                .requires(TaskNames.BUILD_LOGIC_AFTER_COMPILE)
                .weight(() -> plan.get().fullyCached() ? 0 : W_RESOURCES)
                .ticks(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    // /1145: SIMPLE uses top-level resources/; TRADITIONAL uses src/main/resources.
                    // Plugin-contributed resource roots (grails-app/conf, i18n, views) merge after.
                    List<Path> resDirs = new ArrayList<>();
                    Path resMain = cc.jumpkick.layout.ModuleLayout.mainResourcesDir(in.dir(), compact);
                    if (Files.isDirectory(resMain)) resDirs.add(resMain);
                    for (var root : cc.jumpkick.layout.ModuleLayoutPlugins.pluginContributedRoots(in.dir())) {
                        if (!root.resource()) continue;
                        Path dir = in.dir().resolve(root.relative());
                        if (Files.isDirectory(dir)) resDirs.add(dir);
                    }
                    // [build] extra-resources: individual files from outside the module, each with
                    // its own destination and optional rename, so they cannot ride resDirs.
                    List<ExtraResources.Copy> extra = ExtraResources.resolve(ctx.require(PROJECT), in.dir());
                    boolean copied = false;
                    if (!resDirs.isEmpty() || !extra.isEmpty()) {
                        ctx.label("copy resources");
                        for (Path dir : resDirs) copyResources(dir, classes);
                        for (ExtraResources.Copy c : extra) {
                            Path target = classes.resolve(c.destination());
                            Files.createDirectories(target.getParent());
                            Files.copy(c.source(), target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        copied = true;
                    } else {
                        ctx.label("no static resources");
                    }
                    // Sync: destinations copied by a PREVIOUS run but no longer declared must
                    // leave the classes dir, or a shrunk/renamed extra-resources config ships
                    // stale files in every later jar (JK-2174). Deleting them also changes the
                    // classes tree, so the package step's action key re-runs.
                    syncExtraResourceManifest(in.dir(), classes, extra);
                    // Project build logic: AFTER_RESOURCES anchor.
                    boolean logicRan = false;
                    try {
                        logicRan = BuildLogicSupport.run(
                                in.dir(),
                                ctx.require(LAYOUT),
                                actionCache,
                                classes,
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.AFTER_RESOURCES,
                                ctx::label,
                                buildLogicInputTokensRef);
                        if (logicRan) ctx.label("build-logic applied");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("build-logic interrupted", e);
                    }
                    if (!copied && !logicRan) ctx.cached(); // SKIPPED — nothing to copy, no logic
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * SPI anchor {@code BEFORE_COMPILE}: named build-logic tasks before main language compile
     * (codegen). Product stage {@link BuildStage#GENERATE}.
     */
    static Task buildLogicBeforeCompileStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_BEFORE_COMPILE)
                .stage(BuildStage.GENERATE)
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
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.BEFORE_COMPILE,
                                ctx::label,
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

    /** SPI anchor {@code AFTER_COMPILE}: named build-logic tasks after main classes exist. */
    static Task buildLogicAfterCompileStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        String mainCompile = cx.mainCompile();
        AtomicReference<List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_AFTER_COMPILE)
                .stage(BuildStage.COMPILE)
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
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.AFTER_COMPILE,
                                ctx::label,
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

    /** SPI anchor {@code BEFORE_PACKAGE}: named build-logic tasks immediately before jar/image. */
    static Task buildLogicBeforePackageStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<String>> buildLogicInputTokensRef = cx.buildLogicInputTokensRef();
        return Task.builder(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE)
                .stage(BuildStage.PACKAGE)
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
                                cc.jumpkick.plugin.buildlogic.BuildLogicAnchor.BEFORE_PACKAGE,
                                ctx::label,
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

    /** BEFORE_PACKAGE waits on resources (and tests when they run) so packaging sees a complete tree. */
    static String[] beforePackageRequires(BuildPlanner.Inputs in) {
        List<String> requires = new ArrayList<>();
        requires.add(TaskNames.COPY_RESOURCES);
        if (!in.skipTests()) requires.add(TaskNames.RUN_TESTS);
        return requires.toArray(new String[0]);
    }

    /**
     * Reconcile the classes dir against the previous run's extra-resources manifest
     * ({@code target/.jk/extra-resources.txt}): delete destinations that are no longer
     * declared, then record the current set. Best-effort — a missing/corrupt manifest just
     * means nothing to clean (JK-2174).
     */
    static Path extraResourceManifest(Path moduleDir) {
        return moduleDir.resolve("target").resolve(".jk").resolve("extra-resources.txt");
    }

    /** True when the recorded manifest names a destination the current declaration lacks. */
    static boolean hasOrphanedExtraResources(Path moduleDir, List<ExtraResources.Copy> declared) {
        Path manifest = extraResourceManifest(moduleDir);
        if (!Files.isRegularFile(manifest)) return false;
        java.util.Set<String> current = new java.util.LinkedHashSet<>();
        for (ExtraResources.Copy c : declared) current.add(c.destination());
        try {
            for (String prior : Files.readAllLines(manifest)) {
                if (!prior.isBlank() && !current.contains(prior)) return true;
            }
            return false;
        } catch (IOException e) {
            return true; // unreadable manifest — run the copy step and let it reconcile
        }
    }

    static void syncExtraResourceManifest(Path moduleDir, Path classes, List<ExtraResources.Copy> extra) {
        Path manifest = extraResourceManifest(moduleDir);
        java.util.Set<String> current = new java.util.LinkedHashSet<>();
        for (ExtraResources.Copy c : extra) current.add(c.destination());
        try {
            if (Files.isRegularFile(manifest)) {
                Path classesRoot = classes.toAbsolutePath().normalize();
                for (String prior : Files.readAllLines(manifest)) {
                    if (prior.isBlank() || current.contains(prior)) continue;
                    Path stale = classesRoot.resolve(prior).normalize();
                    // Clamp: a manifest edited by hand must never delete outside classes/.
                    if (!stale.startsWith(classesRoot)) continue;
                    Files.deleteIfExists(stale);
                    // Prune now-empty parents up to the classes root.
                    Path parent = stale.getParent();
                    while (parent != null && !parent.equals(classesRoot)) {
                        try (var s = Files.list(parent)) {
                            if (s.findAny().isPresent()) break;
                        }
                        Files.deleteIfExists(parent);
                        parent = parent.getParent();
                    }
                }
            }
            if (current.isEmpty()) {
                Files.deleteIfExists(manifest);
            } else {
                Files.createDirectories(manifest.getParent());
                cc.jumpkick.util.AtomicWrites.replace(manifest, String.join("\n", current) + "\n");
            }
        } catch (IOException | RuntimeException ignored) {
            // best-effort — the next full clean rebuild converges anyway
        }
    }
}
