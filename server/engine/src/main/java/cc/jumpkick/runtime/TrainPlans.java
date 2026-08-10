// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TrainConfig;
import cc.jumpkick.config.TrainConfigParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;

/**
 * {@code jk train} plan: core build through package-jar, then a single train observation task.
 * Never attaches native-image or minified.
 */
public final class TrainPlans {

    private TrainPlans() {}

    public static BuildPlan moduleBuildPlan(
            Path moduleDir,
            JkBuild module,
            Path cache,
            Path jdksDir,
            Path graalHome,
            Path javaHome,
            String profileFilter,
            boolean force,
            boolean skipTests,
            boolean verbose) {
        Path buildFile = moduleDir.resolve("jk.toml");
        Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(moduleDir);
        boolean compact = cc.jumpkick.layout.ModuleLayout.isCompact(moduleDir);
        int estimatedTests = TestSupport.estimateAllSuiteTestCount(moduleDir, compact);
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                moduleDir,
                cache,
                buildFile,
                lockFile,
                moduleDir,
                1,
                estimatedTests,
                null,
                jdksDir,
                skipTests,
                verbose,
                false,
                false,
                java.util.Set.of(),
                cc.jumpkick.config.SessionContext.current());
        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
        // No assembly/native/minified tails — train only needs a runnable package.
        builder.addTask(trainStep(moduleDir, module, cache, lockFile, graalHome, javaHome, profileFilter, force));
        return builder.terminal(TaskNames.TRAIN).build();
    }

    static Task trainStep(
            Path moduleDir,
            JkBuild module,
            Path cache,
            Path lockFile,
            Path graalHome,
            Path javaHome,
            String profileFilter,
            boolean force) {
        return Task.builder(TaskNames.TRAIN)
                .stage(BuildStage.TRAIN)
                .requires(TaskNames.PACKAGE_JAR)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("train dynamic surface");
                    BuildLayout layout = ctx.require(BuildPlanner.LAYOUT);
                    TrainConfig config;
                    try {
                        config = TrainConfigParser.parse(moduleDir.resolve("jk.toml"));
                    } catch (Exception e) {
                        throw new RuntimeException("invalid [train] config: " + e.getMessage(), e);
                    }
                    try {
                        var result = TrainRunner.run(
                                moduleDir,
                                module,
                                layout,
                                cache,
                                lockFile,
                                graalHome,
                                javaHome,
                                config,
                                profileFilter,
                                force,
                                msg -> ctx.label(msg));
                        if (result.cacheHit()) {
                            ctx.label("train up-to-date");
                        } else {
                            ctx.label("train wrote "
                                    + result.surface().entries().size()
                                    + " entries"
                                    + (result.aotWritten() ? " + AOT cache" : ""));
                        }
                    } catch (Exception e) {
                        ctx.error("train", e.getMessage());
                        throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();
    }
}
