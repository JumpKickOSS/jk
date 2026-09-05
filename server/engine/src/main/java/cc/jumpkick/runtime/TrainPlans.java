// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TrainConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
            @Nullable Path jdksDir,
            @Nullable Path graalHome,
            Path javaHome,
            String profileFilter,
            boolean force,
            boolean skipTests,
            boolean verbose) {
        Path buildFile = moduleDir.resolve(ManifestPaths.MANIFEST);
        Path lockFile = LockPaths.lockFile(moduleDir);
        boolean compact = ModuleLayout.isCompact(moduleDir);
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
                Set.of(),
                SessionContext.current());
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
            @Nullable Path graalHome,
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
                    // The module's resolved toolchain, not the engine's own JVM: an AOT cache
                    // trained by the engine JDK is silently rejected by the app's.
                    Path moduleJdk = ctx.get(BuildPlanner.JAVA_HOME).orElse(javaHome);
                    TrainConfig config;
                    try {
                        config = JkBuildParser.trainConfig(moduleDir.resolve(ManifestPaths.MANIFEST));
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
                                moduleJdk,
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
