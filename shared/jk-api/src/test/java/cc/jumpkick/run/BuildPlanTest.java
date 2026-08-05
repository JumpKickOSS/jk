// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BuildPlanTest {

    @Test
    void single_phase_succeeds_and_collects_progress() {
        AtomicInteger ran = new AtomicInteger();
        var pipeline = BuildPlan.builder("test")
                .addTask(Task.builder("step")
                        .ticks(5)
                        .execute(ctx -> {
                            for (int i = 0; i < 5; i++) ctx.progress(1);
                            ran.incrementAndGet();
                        })
                        .build())
                .build();

        var result = pipeline.run();
        assertThat(ran).hasValue(1);
        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(pipeline.snapshot().percent()).isEqualTo(100);
    }

    @Test
    void scope_sums_across_phases() {
        var pipeline = BuildPlan.builder("multi")
                .addTask(Task.builder("a").ticks(3).execute(ctx -> {}).build())
                .addTask(Task.builder("b").ticks(7).execute(ctx -> {}).build())
                .addTask(Task.builder("c").ticks(2).execute(ctx -> {}).build())
                .build();

        var result = pipeline.run();
        assertThat(result.success()).isTrue();
        // Auto-fill: steps reported zero progress but each succeeded, so
        // the numerator climbs to the denominator (3+7+2 = 12).
        assertThat(pipeline.snapshot().numerator()).isEqualTo(12);
        assertThat(pipeline.snapshot().denominator()).isEqualTo(12);
    }

    @Test
    void estimated_total_weight_sums_phase_estimates_without_running() {
        AtomicInteger ran = new AtomicInteger();
        var pipeline = BuildPlan.builder("estimate")
                .addTask(Task.builder("a")
                        .ticks(3)
                        .execute(ctx -> ran.incrementAndGet())
                        .build())
                .addTask(Task.builder("b")
                        .ticks(() -> 7)
                        .execute(ctx -> ran.incrementAndGet())
                        .build())
                .addTask(Task.builder("c").execute(ctx -> ran.incrementAndGet()).build()) // default ticks 1
                .build();

        // No explicit weights → weight tracks ticks, so the total is unchanged.
        assertThat(pipeline.estimatedTotalWeight()).isEqualTo(11); // 3 + 7 + 1
        // Pure estimate: no step executed, no progress accrued.
        assertThat(ran).hasValue(0);
        assertThat(pipeline.snapshot().numerator()).isZero();
        assertThat(pipeline.snapshot().denominator()).isZero();
    }

    @Test
    void interleaved_progress_and_update_scope_never_overshoot() {
        // Regression for the "318 of 161" bug: when a step calls
        // updateTicks(1) followed by progress(1) repeatedly (jk test's
        // per-test bridge), numerator must never exceed denominator
        // mid-step. An earlier implementation advanced the numerator
        // proportionally inside updateTicks to "preserve the fraction,"
        // which compounded into a 2× overshoot.
        AtomicInteger maxNumOverDen = new AtomicInteger(0);
        var pipeline = BuildPlan.builder("interleaved")
                .addListener(new BuildPlanListener() {
                    @Override
                    public void progress(String step, int delta, BuildPlanView view) {
                        if (view.numerator() > view.denominator()) {
                            maxNumOverDen.updateAndGet(
                                    prev -> Math.max(prev, (int) (view.numerator() - view.denominator())));
                        }
                    }

                    @Override
                    public void tickUpdate(String step, int delta, BuildPlanView view) {
                        if (view.numerator() > view.denominator()) {
                            maxNumOverDen.updateAndGet(
                                    prev -> Math.max(prev, (int) (view.numerator() - view.denominator())));
                        }
                    }
                })
                .addTask(Task.builder("loop")
                        .ticks(0)
                        .execute(ctx -> {
                            for (int i = 0; i < 100; i++) {
                                ctx.updateTicks(1);
                                ctx.progress(1);
                            }
                        })
                        .build())
                .build();

        var result = pipeline.run();
        assertThat(result.success()).isTrue();
        assertThat(maxNumOverDen).hasValue(0);
        assertThat(pipeline.snapshot().numerator()).isEqualTo(100);
        assertThat(pipeline.snapshot().denominator()).isEqualTo(100);
    }

    @Test
    void update_scope_grows_denominator() {
        var listener = new RecordingListener();
        var pipeline = BuildPlan.builder("growing")
                .addListener(listener)
                .addTask(Task.builder("expand")
                        .ticks(2)
                        .execute(ctx -> {
                            ctx.progress(1);
                            ctx.updateTicks(5); // denominator climbs from 2 → 7
                            ctx.progress(4);
                        })
                        .build())
                .build();

        var result = pipeline.run();
        assertThat(result.success()).isTrue();
        assertThat(listener.scopeUpdates).contains(5);
        // Auto-fill closes the gap: 1 + 4 reported + 2 auto-filled = 7.
        assertThat(pipeline.snapshot().numerator()).isEqualTo(7);
        assertThat(pipeline.snapshot().denominator()).isEqualTo(7);
    }

    @Test
    void dag_respects_requires_ordering() {
        List<String> order = new ArrayList<>();
        var pipeline = BuildPlan.builder("dag")
                .addTask(
                        Task.builder("setup").execute(ctx -> order.add("setup")).build())
                .addTask(Task.builder("middle")
                        .requires("setup")
                        .execute(ctx -> order.add("middle"))
                        .build())
                .addTask(Task.builder("end")
                        .requires("middle")
                        .execute(ctx -> order.add("end"))
                        .build())
                .build();

        pipeline.run();
        assertThat(order).containsExactly("setup", "middle", "end");
    }

    @Test
    void independent_async_phases_run_in_parallel() throws InterruptedException {
        CountDownLatch sawBothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var pipeline = BuildPlan.builder("parallel")
                .addTask(Task.builder("a")
                        .kind(TaskKind.IO)
                        .execute(ctx -> {
                            sawBothRunning.countDown();
                            release.await();
                        })
                        .build())
                .addTask(Task.builder("b")
                        .kind(TaskKind.IO)
                        .execute(ctx -> {
                            sawBothRunning.countDown();
                            release.await();
                        })
                        .build())
                .build();

        Thread runner = new Thread(pipeline::run);
        runner.start();
        // Both steps must have entered execute() concurrently — proves
        // the IO pool dispatched them in parallel, not sequentially.
        assertThat(sawBothRunning.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        runner.join(2000);
    }

    @Test
    void failed_phase_cancels_dependent_phases() {
        var ran = new AtomicInteger();
        var pipeline = BuildPlan.builder("fail")
                .addTask(Task.builder("ok").execute(ctx -> {}).build())
                .addTask(Task.builder("boom")
                        .requires("ok")
                        .execute(ctx -> {
                            throw new RuntimeException("intentional");
                        })
                        .build())
                .addTask(Task.builder("downstream")
                        .requires("boom")
                        .execute(ctx -> ran.incrementAndGet())
                        .build())
                .build();

        var result = pipeline.run();
        assertThat(result.success()).isFalse();
        assertThat(ran).hasValue(0); // downstream never ran
        assertThat(result.steps())
                .extracting(BuildPlanResult.StepReport::status)
                .containsExactly(TaskStatus.SUCCESS, TaskStatus.FAIL, TaskStatus.CANCELLED);
        // Dependency edges survive into the report (needed by the engine's cache-benefit metric),
        // including on the CANCELLED path.
        assertThat(result.steps())
                .extracting(BuildPlanResult.StepReport::name, BuildPlanResult.StepReport::requires)
                .containsExactly(
                        tuple("ok", List.of()), tuple("boom", List.of("ok")), tuple("downstream", List.of("boom")));
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().step()).isEqualTo("boom");
    }

    @Test
    void warnings_accumulate_and_dont_fail_the_goal() {
        var pipeline = BuildPlan.builder("nags")
                .addTask(Task.builder("one")
                        .execute(ctx -> {
                            ctx.warn("dep.unverified", "no checksum for X");
                            ctx.warn("dep.unverified", "no checksum for Y");
                        })
                        .build())
                .build();
        var result = pipeline.run();
        assertThat(result.success()).isTrue();
        assertThat(result.warnings()).hasSize(2);
    }

    @Test
    void duplicate_phase_names_are_rejected() {
        assertThatThrownBy(() -> BuildPlan.builder("dup")
                        .addTask(Task.builder("x").execute(ctx -> {}).build())
                        .addTask(Task.builder("x").execute(ctx -> {}).build())
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void unknown_requires_is_rejected() {
        assertThatThrownBy(() -> BuildPlan.builder("bad")
                        .addTask(Task.builder("x")
                                .requires("nope")
                                .execute(ctx -> {})
                                .build())
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void cycle_is_rejected() {
        assertThatThrownBy(() -> BuildPlan.builder("loop")
                        .addTask(Task.builder("a")
                                .requires("b")
                                .execute(ctx -> {})
                                .build())
                        .addTask(Task.builder("b")
                                .requires("a")
                                .execute(ctx -> {})
                                .build())
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void typed_state_flows_between_phases() {
        BuildPlanKey<String> NAME = BuildPlanKey.of("name", String.class);
        BuildPlanKey<Integer> COUNT = BuildPlanKey.of("count", Integer.class);
        List<String> consumed = new ArrayList<>();

        var pipeline = BuildPlan.builder("flow")
                .addTask(Task.builder("producer")
                        .execute(ctx -> {
                            ctx.put(NAME, "widget");
                            ctx.put(COUNT, 42);
                        })
                        .build())
                .addTask(Task.builder("consumer")
                        .requires("producer")
                        .execute(ctx -> {
                            consumed.add(ctx.require(NAME));
                            consumed.add(String.valueOf((int) ctx.require(COUNT)));
                        })
                        .build())
                .build();

        var result = pipeline.run();
        assertThat(result.success()).isTrue();
        assertThat(consumed).containsExactly("widget", "42");
        // Command body can read state back too.
        assertThat(pipeline.get(NAME)).hasValue("widget");
    }

    @Test
    void require_throws_when_key_missing() {
        BuildPlanKey<String> MISSING = BuildPlanKey.of("missing", String.class);
        var pipeline = BuildPlan.builder("oops")
                .addTask(Task.builder("reader")
                        .execute(ctx -> ctx.require(MISSING))
                        .build())
                .build();
        var result = pipeline.run();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).contains("required key 'missing'");
    }

    @Test
    void cancellation_propagates_to_running_phases() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch sawCancelled = new CountDownLatch(1);
        var pipeline = BuildPlan.builder("cancellable")
                .addTask(Task.builder("worker")
                        .kind(TaskKind.IO)
                        .execute(ctx -> {
                            started.countDown();
                            while (!ctx.cancelled()) {
                                Thread.sleep(10);
                            }
                            sawCancelled.countDown();
                        })
                        .build())
                .build();

        Thread runner = new Thread(pipeline::run);
        runner.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        pipeline.requestCancel();
        assertThat(sawCancelled.await(2, TimeUnit.SECONDS)).isTrue();
        runner.join(2000);
        assertThat(pipeline.snapshot().cancelled()).isTrue();
    }

    @Test
    void session_cancel_marks_userCancelled_so_truncated_wall_is_not_success() {
        // Engine Ctrl-C only flips SessionCancel (CancelToken) — it does not call requestCancel.
        // The result must still report userCancelled so journal/metrics put the wall in cancelled,
        // not ok (ETA prior).
        SessionCancel.bind(() -> true);
        try {
            var pipeline = BuildPlan.builder("session-cancel")
                    .addTask(Task.builder("worker")
                            .kind(TaskKind.SYNC)
                            .execute(ctx -> {
                                if (ctx.cancelled()) throw new RuntimeException("cancelled");
                            })
                            .build())
                    .build();
            BuildPlanResult r = pipeline.run();
            assertThat(r.success()).isFalse();
            assertThat(r.userCancelled()).isTrue();
            assertThat(r.cancelled()).isTrue();
            assertThat(r.steps().getFirst().status()).isEqualTo(TaskStatus.CANCELLED);
        } finally {
            SessionCancel.bind(null);
        }
    }

    /** Listener that records every event for assertion. */
    static final class RecordingListener implements BuildPlanListener {
        final List<Integer> scopeUpdates = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        volatile BuildPlanResult finalResult;

        @Override
        public void tickUpdate(String step, int delta, BuildPlanView view) {
            scopeUpdates.add(delta);
        }

        @Override
        public void warn(String step, String code, String message) {
            warnings.add(code + ":" + message);
        }

        @Override
        public void error(String step, String code, String message) {
            errors.add(code + ":" + message);
        }

        @Override
        public void pipelineFinish(BuildPlanResult result) {
            finalResult = result;
        }
    }
}
