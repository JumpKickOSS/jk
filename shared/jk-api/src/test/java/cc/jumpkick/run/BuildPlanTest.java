// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.FakeClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BuildPlanTest {

    @Test
    void single_phase_succeeds_and_collects_progress() {
        AtomicInteger ran = new AtomicInteger();
        var plan = BuildPlan.builder("test")
                .addTask(Task.builder("step")
                        .ticks(5)
                        .execute(ctx -> {
                            for (int i = 0; i < 5; i++) ctx.progress(1);
                            ran.incrementAndGet();
                        })
                        .build())
                .build();

        var result = plan.run();
        assertThat(ran).hasValue(1);
        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(plan.snapshot().percent()).isEqualTo(100);
    }

    @Test
    void an_unreported_step_throwable_reaches_listeners_not_just_the_result() {
        // : the synthesized "exception" diagnostic landed only in the result's
        // diagnostics, which never cross the wire on the workspace path — a GlobException
        // out of copy-resources failed the module with no message anywhere.
        List<String> seen = new ArrayList<>();
        var plan = BuildPlan.builder("boom")
                .addListener(new BuildPlanListener() {
                    @Override
                    public void error(String step, String code, String message) {
                        seen.add(step + ":" + code + ":" + message);
                    }
                })
                .addTask(Task.builder("copy-resources")
                        .ticks(1)
                        .execute(ctx -> {
                            throw new IllegalStateException("path `../x` matched no files");
                        })
                        .build())
                .build();

        var result = plan.run();
        assertThat(result.success()).isFalse();
        assertThat(seen).containsExactly("copy-resources:exception:path `../x` matched no files");
        assertThat(result.errors()).hasSize(1);
    }

    /** What a step reports as blocked time rides beside its wall to every listener. */
    @Test
    void a_steps_reported_wait_reaches_listeners_beside_its_wall() {
        List<Duration> waits = new ArrayList<>();
        List<Duration> walls = new ArrayList<>();
        var plan = BuildPlan.builder("queued")
                .addListener(new BuildPlanListener() {
                    @Override
                    public void stepFinish(
                            String step,
                            @Nullable String group,
                            TaskStatus status,
                            Duration duration,
                            Duration waited) {
                        walls.add(duration);
                        waits.add(waited);
                    }
                })
                .addTask(Task.builder("compile-java")
                        .ticks(1)
                        .execute(ctx -> {
                            ctx.waited(Duration.ofMillis(30));
                            ctx.waited(Duration.ofMillis(12));
                        })
                        .build())
                .addTask(Task.builder("write-stamp").ticks(1).execute(ctx -> {}).build())
                .build();

        assertThat(plan.run().success()).isTrue();
        assertThat(waits).containsExactly(Duration.ofMillis(42), Duration.ZERO);
        assertThat(walls).allSatisfy(w -> assertThat(w).isGreaterThanOrEqualTo(Duration.ZERO));
    }

    @Test
    void scope_sums_across_phases() {
        var plan = BuildPlan.builder("multi")
                .addTask(Task.builder("a").ticks(3).execute(ctx -> {}).build())
                .addTask(Task.builder("b").ticks(7).execute(ctx -> {}).build())
                .addTask(Task.builder("c").ticks(2).execute(ctx -> {}).build())
                .build();

        var result = plan.run();
        assertThat(result.success()).isTrue();
        // Auto-fill: steps reported zero progress but each succeeded, so
        // the numerator climbs to the denominator (3+7+2 = 12).
        assertThat(plan.snapshot().numerator()).isEqualTo(12);
        assertThat(plan.snapshot().denominator()).isEqualTo(12);
    }

    @Test
    void estimated_total_weight_sums_phase_estimates_without_running() {
        AtomicInteger ran = new AtomicInteger();
        var plan = BuildPlan.builder("estimate")
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
        assertThat(plan.estimatedTotalWeight()).isEqualTo(11); // 3 + 7 + 1
        // Pure estimate: no step executed, no progress accrued.
        assertThat(ran).hasValue(0);
        assertThat(plan.snapshot().numerator()).isZero();
        assertThat(plan.snapshot().denominator()).isZero();
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
        var plan = BuildPlan.builder("interleaved")
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

        var result = plan.run();
        assertThat(result.success()).isTrue();
        assertThat(maxNumOverDen).hasValue(0);
        assertThat(plan.snapshot().numerator()).isEqualTo(100);
        assertThat(plan.snapshot().denominator()).isEqualTo(100);
    }

    @Test
    void update_scope_grows_denominator() {
        var listener = new RecordingListener();
        var plan = BuildPlan.builder("growing")
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

        var result = plan.run();
        assertThat(result.success()).isTrue();
        assertThat(listener.scopeUpdates).contains(5);
        // Auto-fill closes the gap: 1 + 4 reported + 2 auto-filled = 7.
        assertThat(plan.snapshot().numerator()).isEqualTo(7);
        assertThat(plan.snapshot().denominator()).isEqualTo(7);
    }

    @Test
    void dag_respects_requires_ordering() {
        List<String> order = new ArrayList<>();
        var plan = BuildPlan.builder("dag")
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

        plan.run();
        assertThat(order).containsExactly("setup", "middle", "end");
    }

    @Test
    void independent_async_phases_run_in_parallel() throws InterruptedException {
        CountDownLatch sawBothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        var plan = BuildPlan.builder("parallel")
                .addTask(Task.builder("a")
                        .kind(TaskKind.IO)
                        .execute(ctx -> {
                            sawBothRunning.countDown();
                            if (release.await(30, TimeUnit.SECONDS)) released.incrementAndGet();
                        })
                        .build())
                .addTask(Task.builder("b")
                        .kind(TaskKind.IO)
                        .execute(ctx -> {
                            sawBothRunning.countDown();
                            if (release.await(30, TimeUnit.SECONDS)) released.incrementAndGet();
                        })
                        .build())
                .build();

        Thread runner = new Thread(plan::run);
        runner.start();
        try {
            // Both steps must have entered execute() concurrently — proves
            // the IO pool dispatched them in parallel, not sequentially.
            assertThat(sawBothRunning.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown(); // an unmet expectation must not strand the tasks, and the runner
        }
        assertThat(runner.join(Duration.ofSeconds(10))).isTrue();
        assertThat(released)
                .as("both steps left execute() through the release, not a timeout")
                .hasValue(2);
    }

    @Test
    void failed_phase_cancels_dependent_phases() {
        var ran = new AtomicInteger();
        var plan = BuildPlan.builder("fail")
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

        var result = plan.run();
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
        var plan = BuildPlan.builder("nags")
                .addTask(Task.builder("one")
                        .execute(ctx -> {
                            ctx.warn("dep.unverified", "no checksum for X");
                            ctx.warn("dep.unverified", "no checksum for Y");
                        })
                        .build())
                .build();
        var result = plan.run();
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
        BuildPlanKey<String> NAME = BuildPlanKey.scalar("name", String.class);
        BuildPlanKey<Integer> COUNT = BuildPlanKey.scalar("count", Integer.class);
        List<String> consumed = new ArrayList<>();

        var plan = BuildPlan.builder("flow")
                .stateKeys(NAME, COUNT)
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

        var result = plan.run();
        assertThat(result.success()).isTrue();
        assertThat(consumed).containsExactly("widget", "42");
        // Command body can read state back too.
        assertThat(plan.get(NAME)).hasValue("widget");
    }

    @Test
    void publish_rejects_wrong_list_element_type() {
        BuildPlanKey<List<String>> names = BuildPlanKey.list("names", String.class);
        BuildPlan plan = planPublishing(names, List.of("ok", 7));

        BuildPlanResult result = plan.run();

        assertThat(result.success()).isFalse();
        assertThat(result.errors().getFirst().message())
                .isEqualTo("plan state 'names' element [1] expected java.lang.String but was java.lang.Integer");
    }

    @Test
    void read_revalidates_a_list_mutated_after_publish() {
        BuildPlanKey<List<String>> names = BuildPlanKey.list("names", String.class);
        List<String> published = new ArrayList<>();
        published.add("ok");
        var plan = BuildPlan.builder("mutated-state")
                .stateKeys(names)
                .addTask(Task.builder("producer")
                        .execute(ctx -> {
                            ctx.put(names, published);
                            published.add(null);
                        })
                        .build())
                .addTask(Task.builder("consumer")
                        .requires("producer")
                        .execute(ctx -> ctx.require(names))
                        .build())
                .build();

        BuildPlanResult result = plan.run();

        assertThat(result.success()).isFalse();
        assertThat(result.errors().getFirst().message())
                .isEqualTo("plan state 'names' element [1] expected java.lang.String but was null");
    }

    @Test
    void publish_rejects_wrong_map_key_type() {
        BuildPlanKey<Map<String, Integer>> counts = BuildPlanKey.map("counts", String.class, Integer.class);
        BuildPlan plan = planPublishing(counts, Map.of(7, 1));

        BuildPlanResult result = plan.run();

        assertThat(result.success()).isFalse();
        assertThat(result.errors().getFirst().message())
                .isEqualTo("plan state 'counts' map key expected java.lang.String but was java.lang.Integer");
    }

    @Test
    void publish_rejects_wrong_map_value_type() {
        BuildPlanKey<Map<String, Integer>> counts = BuildPlanKey.map("counts", String.class, Integer.class);
        BuildPlan plan = planPublishing(counts, Map.of("wrong", "one"));

        BuildPlanResult result = plan.run();

        assertThat(result.success()).isFalse();
        assertThat(result.errors().getFirst().message())
                .isEqualTo(
                        "plan state 'counts' map value for key 'wrong' expected java.lang.Integer but was java.lang.String");
    }

    @Test
    void same_name_reuse_rejects_incompatible_descriptor() {
        BuildPlanKey<String> scalar = BuildPlanKey.scalar("shared", String.class);
        BuildPlanKey<List<String>> list = BuildPlanKey.list("shared", String.class);
        assertThatThrownBy(
                        () -> BuildPlan.builder("reuse").stateKeys(scalar, list).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "plan state key 'shared' reused with incompatible type: expected java.lang.String but actual list<java.lang.String>");
    }

    @Test
    void unrelated_plans_can_use_the_same_name_with_different_descriptors() {
        BuildPlan first = BuildPlan.builder("first")
                .stateKeys(BuildPlanKey.scalar("shared", String.class))
                .build();
        BuildPlan second = BuildPlan.builder("second")
                .stateKeys(BuildPlanKey.list("shared", String.class))
                .build();

        assertThat(first.name()).isEqualTo("first");
        assertThat(second.name()).isEqualTo("second");
    }

    @Test
    void runtime_rejects_a_key_that_disagrees_with_the_declared_descriptor() {
        BuildPlanKey<String> scalar = BuildPlanKey.scalar("shared", String.class);
        BuildPlanKey<List<String>> list = BuildPlanKey.list("shared", String.class);
        var plan = BuildPlan.builder("runtime-reuse")
                .stateKeys(scalar)
                .addTask(Task.builder("consumer").execute(ctx -> ctx.get(list)).build())
                .build();

        BuildPlanResult result = plan.run();

        assertThat(result.success()).isFalse();
        assertThat(result.errors().getFirst().message())
                .isEqualTo(
                        "plan state key 'shared' reused with incompatible type: expected java.lang.String but actual list<java.lang.String>");
    }

    private static BuildPlan planPublishing(BuildPlanKey<?> key, Object value) {
        return BuildPlan.builder("invalid-publish")
                .stateKeys(key)
                .addTask(Task.builder("producer")
                        .execute(ctx -> invokePut(ctx, key, value))
                        .build())
                .build();
    }

    private static void invokePut(TaskContext context, BuildPlanKey<?> key, Object value) throws Exception {
        Method put = TaskContext.class.getMethod("put", BuildPlanKey.class, Object.class);
        try {
            put.invoke(context, key, value);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    /** put on an undeclared key names the key and the plan; require on one says "never declared". */
    @Test
    void undeclared_keys_are_named_as_such_on_put_and_require() {
        BuildPlanKey<String> DECLARED = BuildPlanKey.scalar("declared", String.class);
        BuildPlanKey<String> STRAY = BuildPlanKey.scalar("stray", String.class);
        var writer = BuildPlan.builder("writer")
                .stateKeys(DECLARED)
                .addTask(Task.builder("w").execute(ctx -> ctx.put(STRAY, "x")).build())
                .build();
        var written = writer.run();
        assertThat(written.success()).isFalse();
        assertThat(written.errors().getFirst().message()).contains("plan state key 'stray' was not declared");

        var reader = BuildPlan.builder("reader")
                .stateKeys(DECLARED)
                .addTask(Task.builder("r").execute(ctx -> ctx.require(STRAY)).build())
                .build();
        var read = reader.run();
        assertThat(read.success()).isFalse();
        assertThat(read.errors().getFirst().message()).contains("never declared");
        // get stays lenient for cross-plan reads: empty, not an error.
        assertThat(reader.get(STRAY)).isEmpty();
    }

    @Test
    void require_throws_when_key_missing() {
        BuildPlanKey<String> MISSING = BuildPlanKey.scalar("missing", String.class);
        var plan = BuildPlan.builder("oops")
                .stateKeys(MISSING)
                .addTask(Task.builder("reader")
                        .execute(ctx -> ctx.require(MISSING))
                        .build())
                .build();
        var result = plan.run();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).contains("required key 'missing'");
    }

    @Test
    void cancellation_propagates_to_running_phases() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch sawCancelled = new CountDownLatch(1);
        var plan = BuildPlan.builder("cancellable")
                .addTask(Task.builder("worker")
                        .kind(TaskKind.IO)
                        .execute(ctx -> {
                            started.countDown();
                            // Bounded: an unpropagated cancel would otherwise spin this task — and
                            // the runner thread joining it — for the life of the JVM.
                            Await.until(Duration.ofSeconds(30), ctx::cancelled);
                            sawCancelled.countDown();
                        })
                        .build())
                .build();

        Thread runner = new Thread(plan::run);
        runner.start();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        plan.requestCancel();
        assertThat(sawCancelled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(runner.join(Duration.ofSeconds(10))).isTrue();
        assertThat(plan.snapshot().cancelled()).isTrue();
    }

    @Test
    void session_cancel_marks_userCancelled_so_truncated_wall_is_not_success() {
        // Engine Ctrl-C only flips SessionCancel (CancelToken) — it does not call requestCancel.
        // The result must still report userCancelled so journal/metrics put the wall in cancelled,
        // not ok (ETA prior).
        SessionCancel.bind(() -> true);
        try {
            var plan = BuildPlan.builder("session-cancel")
                    .addTask(Task.builder("worker")
                            .kind(TaskKind.SYNC)
                            .execute(ctx -> {
                                if (ctx.cancelled()) throw new RuntimeException("cancelled");
                            })
                            .build())
                    .build();
            BuildPlanResult r = plan.run();
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
        volatile @Nullable BuildPlanResult finalResult;

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
        public void planFinish(BuildPlanResult result) {
            finalResult = result;
        }
    }

    /** A step's reported duration is what the plan's clock says passed, not what the machine took. */
    @Test
    void step_durations_come_from_the_plan_clock() {
        FakeClock clock = new FakeClock();
        var plan = BuildPlan.builder("timed")
                .clock(clock)
                .addTask(Task.builder("slow")
                        .execute(ctx -> clock.advance(Duration.ofMillis(1_500)))
                        .build())
                .build();
        BuildPlanResult result = plan.run();
        assertThat(result.success()).isTrue();
        assertThat(result.steps())
                .filteredOn(s -> s.name().equals("slow"))
                .extracting(BuildPlanResult.StepReport::duration)
                .containsExactly(Duration.ofMillis(1_500));
        assertThat(result.duration()).isGreaterThanOrEqualTo(Duration.ofMillis(1_500));
    }
}
