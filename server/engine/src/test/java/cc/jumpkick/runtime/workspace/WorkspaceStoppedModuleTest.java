// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A module fail-fast stopped for a sibling's failure finishes as stopped, not as failed. */
class WorkspaceStoppedModuleTest {

    @Test
    void a_plan_stopped_mid_step_is_stopped() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        BuildPlan plan = plan(ctx -> {
            started.countDown();
            while (!ctx.cancelled()) Thread.onSpinWait();
            throw new IllegalStateException("cancelled");
        });
        CompletableFuture<BuildPlanResult> run = CompletableFuture.supplyAsync(plan::run);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        plan.stop();
        BuildPlanResult result = run.get(10, TimeUnit.SECONDS);

        assertThat(result.success()).isFalse();
        assertThat(result.userCancelled()).isFalse();
        assertThat(WorkspaceRunPhase.stoppedByFailure(plan, result, false)).isTrue();
    }

    @Test
    void a_plan_that_failed_on_its_own_is_a_failure_even_when_stopped() {
        BuildPlan plan = plan(ctx -> {
            throw new IllegalStateException("javac said no");
        });
        BuildPlanResult result = plan.run();
        plan.stop();

        assertThat(WorkspaceRunPhase.stoppedByFailure(plan, result, false)).isFalse();
    }

    @Test
    void a_failure_nobody_stopped_is_a_failure() {
        BuildPlan plan = plan(ctx -> {
            throw new IllegalStateException("javac said no");
        });
        BuildPlanResult result = plan.run();

        assertThat(WorkspaceRunPhase.stoppedByFailure(plan, result, false)).isFalse();
    }

    @Test
    void a_user_cancel_is_not_a_stop() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        BuildPlan plan = plan(ctx -> {
            started.countDown();
            while (!ctx.cancelled()) Thread.onSpinWait();
            throw new IllegalStateException("cancelled");
        });
        CompletableFuture<BuildPlanResult> run = CompletableFuture.supplyAsync(plan::run);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        plan.stop();
        BuildPlanResult result = run.get(10, TimeUnit.SECONDS);

        assertThat(WorkspaceRunPhase.stoppedByFailure(plan, result, true)).isFalse();
    }

    private static BuildPlan plan(Task.Body body) {
        BuildPlan.Builder b = BuildPlan.builder("module");
        b.addTask(Task.builder(TaskNames.COMPILE_JAVA)
                .stage(BuildStage.COMPILE)
                .ticks(1)
                .execute(body)
                .build());
        return b.build();
    }
}
