// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.JdkInstallView;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkEnsureProgress;
import cc.jumpkick.jdk.JdkInstallListener;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A toolchain jk provisions on the user's behalf — a pinned JDK the build needs, the GraalVM a
 * native build links with — rendered exactly as {@code jk jdk install} renders its own: one
 * {@code ensure-jdk} plan step whose progress is the {@link JdkInstallView} bar on a terminal and
 * {@link JdkEnsureProgress} labels on {@code --output json}, chosen by the caller's {@link
 * BuildPlanConsole.Mode} the way every other plan chooses. Runs to completion before the caller's
 * own console opens, so the two never share the screen.
 */
public final class ToolchainInstalls {

    /**
     * The install itself: download + extract under {@code progress}, answering the installed JDK.
     * {@code warn} takes a degradation the install went ahead despite (a feed that was unreachable
     * and answered from its cache); it reaches the plan's listeners and the terminal.
     */
    public interface Body {
        InstalledJdk install(JdkInstallListener progress, Consumer<String> warn) throws Exception;
    }

    private static final BuildPlanKey<InstalledJdk> INSTALLED = BuildPlanKey.scalar("installed", InstalledJdk.class);

    private ToolchainInstalls() {}

    /**
     * Run {@code body} as a one-step plan in {@code mode}. Empty when it failed — the failure has
     * been rendered as a {@code JDK} fail wedge, so the caller only decides what to stop.
     *
     * @param header why jk is installing this, printed above the bar when the download starts
     * @param label the human JDK label ({@code "Temurin 21"}), or null to take it from the events
     */
    public static Optional<InstalledJdk> run(
            BuildPlanConsole.Mode mode, @Nullable String header, @Nullable String label, Body body) {
        Task install = Task.builder(TaskNames.ENSURE_JDK)
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("install " + (label == null ? "JDK" : label));
                    try (JdkInstallView view = new JdkInstallView(label).header(header)) {
                        Consumer<String> warn = message -> {
                            ctx.warn("jdk", message);
                            view.warn(message);
                        };
                        ctx.put(
                                INSTALLED,
                                body.install(JdkInstallListener.tee(new JdkEnsureProgress(ctx), view), warn));
                    } catch (Exception e) {
                        throw failure(ctx, e);
                    }
                    ctx.progress(1);
                })
                .build();
        BuildPlan plan = BuildPlan.builder("toolchain")
                .interactive(true)
                .stateKeys(INSTALLED)
                .addTask(install)
                .build();
        BuildPlanResult result = BuildPlanConsole.run(plan, mode);
        if (!result.success()) {
            // Interactive plans render silently, so the step's error is this line or nothing.
            String why = result.errors().isEmpty()
                    ? "install failed"
                    : result.errors().getFirst().message();
            CommandWedge.printFail("JDK", why);
            return Optional.empty();
        }
        return plan.get(INSTALLED);
    }

    /**
     * The step's failure, recorded on {@code ctx} for the plan's listeners. An interrupt is
     * re-raised on the thread that received it — the plan's cancellation reads the flag, and a
     * download that swallowed it would leave the step looking like an ordinary error.
     */
    static RuntimeException failure(TaskContext ctx, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            ctx.error("jdk", "interrupted");
        } else {
            ctx.error("jdk", Errors.text(e));
        }
        return new RuntimeException(e);
    }
}
