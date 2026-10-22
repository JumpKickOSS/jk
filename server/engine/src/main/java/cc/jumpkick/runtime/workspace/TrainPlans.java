// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TrainConfig;
import cc.jumpkick.host.Errors;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.TrainRunner;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** {@code jk train}: the {@link Tail} that runs the training observation after the build. */
public final class TrainPlans {

    private TrainPlans() {}

    /**
     * {@code jk train}'s tail: the training run against the jar PACKAGE left. {@code javaHome} is
     * the fallback when the plan resolved no module toolchain.
     */
    public static Tail tail(
            JkBuild module, @Nullable Path graalHome, Path javaHome, @Nullable String profileFilter, boolean force) {
        return (plan, in) -> plan.extend(
                trainStep(in.dir(), module, in.cache(), in.lockFile(), graalHome, javaHome, profileFilter, force));
    }

    static Task trainStep(
            Path moduleDir,
            JkBuild module,
            Path cache,
            Path lockFile,
            @Nullable Path graalHome,
            Path javaHome,
            @Nullable String profileFilter,
            boolean force) {
        return Task.builder(TaskNames.TRAIN)
                .forecast(StepForecast.always("a training run judges its own surface"))
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
                        config = JkBuildParser.trainConfig(ManifestPaths.manifestIn(moduleDir));
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
                        ctx.error("train", Errors.text(e));
                        throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();
    }
}
