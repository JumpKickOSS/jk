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
                    // SIMPLE uses top-level resources/; TRADITIONAL uses src/main/resources.
                    // Plugin-contributed resource roots (grails-app/conf, i18n, views) merge after.
                    List<Path> resDirs = new ArrayList<>();
                    Path resMain = cc.jumpkick.layout.ModuleLayout.mainResourcesDir(in.dir(), compact);
                    if (Files.isDirectory(resMain)) resDirs.add(resMain);
                    for (var root : cc.jumpkick.layout.ModuleLayoutPlugins.pluginContributedRoots(in.dir())) {
                        if (!root.resource()) continue;
                        Path dir = in.dir().resolve(root.relative());
                        if (Files.isDirectory(dir)) resDirs.add(dir);
                    }
                    Path pluginManifest = in.dir().resolve("jk-plugin.toml");
                    boolean ownManifest = Files.isRegularFile(pluginManifest);
                    // Orphan reconciliation: a manifest deleted (or renamed away) at the module
                    // root must also leave the classes tree, or the jar keeps describing a
                    // plugin that no longer exists.
                    if (!ownManifest) {
                        Files.deleteIfExists(classes.resolve("jk-plugin.toml"));
                    }
                    boolean copied = false;
                    if (!resDirs.isEmpty() || ownManifest) {
                        ctx.label("copy resources");
                        for (Path dir : resDirs) copyResources(dir, classes);
                        if (ownManifest) {
                            Files.copy(
                                    pluginManifest,
                                    classes.resolve("jk-plugin.toml"),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                        copied = true;
                    } else {
                        ctx.label("no static resources");
                    }
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

    /**
     * BEFORE_PACKAGE waits on resources only — never on tests. Packaging needs a complete
     * classes tree, which tests do not contribute to. A failing suite still fails the build;
     * the artifact is built concurrently.
     */
    static String[] beforePackageRequires(BuildPlanner.Inputs in) {
        return new String[] {TaskNames.COPY_RESOURCES};
    }
}
