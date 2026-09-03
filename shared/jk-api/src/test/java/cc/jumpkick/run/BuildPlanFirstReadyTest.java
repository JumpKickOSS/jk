// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Admission is first-ready: a step starts when its own {@code requires()} are ok, not when every
 * peer that shared its readiness level has finished.
 *
 * <p>These fixtures are the shape the dogfood build actually has, because that shape is what the
 * level executor got wrong. {@code package-jar} is milliseconds, {@code run-tests} is tens of
 * seconds, and {@code package-assembly} / {@code native-image} require only the jar — yet all four
 * landed in one level, so a 0.41 s assembly waited out a 26.5 s suite and the module's dependents
 * waited with it. Asserting "native finished before tests" is not enough on its own: that could
 * also mean the plan reordered. Each test therefore pins the *start* against the slow step's
 * still-running state.
 */
class BuildPlanFirstReadyTest {

    /** How long a "slow" step blocks. Long enough to be unambiguous, short enough for CI. */
    private static final Duration SLOW = Duration.ofSeconds(2);

    @Test
    void native_image_starts_while_run_tests_is_still_running() throws Exception {
        CountDownLatch testsRunning = new CountDownLatch(1);
        CountDownLatch nativeStarted = new CountDownLatch(1);
        AtomicBoolean testsStillRunningWhenNativeStarted = new AtomicBoolean();
        AtomicBoolean testsDone = new AtomicBoolean();

        var plan = BuildPlan.builder("engine-shaped")
                .addTask(Task.builder("compile-test")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("package-jar")
                        .kind(TaskKind.CPU)
                        .requires("compile-test")
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("run-tests")
                        .kind(TaskKind.CPU)
                        .requires("compile-test")
                        .execute(ctx -> {
                            testsRunning.countDown();
                            // Hold until native has started, but never hang the suite.
                            nativeStarted.await(10, TimeUnit.SECONDS);
                            testsDone.set(true);
                        })
                        .build())
                // The real edge: only the jar, never the suite.
                .addTask(Task.builder("native-image")
                        .kind(TaskKind.IO)
                        .requires("package-jar")
                        .execute(ctx -> {
                            assertThat(testsRunning.await(10, TimeUnit.SECONDS)).isTrue();
                            testsStillRunningWhenNativeStarted.set(!testsDone.get());
                            nativeStarted.countDown();
                        })
                        .build())
                .build();

        BuildPlanResult r = plan.run();

        assertThat(r.success()).isTrue();
        assertThat(nativeStarted.await(0, TimeUnit.SECONDS)).isTrue();
        assertThat(testsStillRunningWhenNativeStarted)
                .as("native-image must be admitted on package-jar alone, while run-tests is in flight")
                .isTrue();
    }

    @Test
    void package_assembly_starts_while_run_tests_is_still_running() throws Exception {
        CountDownLatch testsRunning = new CountDownLatch(1);
        AtomicReference<Instant> assemblyStart = new AtomicReference<>();
        AtomicReference<Instant> testsEnd = new AtomicReference<>();

        var plan = BuildPlan.builder("assembly-shaped")
                .addTask(Task.builder("package-jar")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("run-tests")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> {
                            testsRunning.countDown();
                            Thread.sleep(SLOW.toMillis());
                            testsEnd.set(Instant.now());
                        })
                        .build())
                .addTask(Task.builder("package-assembly")
                        .kind(TaskKind.CPU)
                        .requires("package-jar")
                        .execute(ctx -> {
                            assertThat(testsRunning.await(10, TimeUnit.SECONDS)).isTrue();
                            assemblyStart.set(Instant.now());
                        })
                        .build())
                .build();

        BuildPlanResult r = plan.run();

        assertThat(r.success()).isTrue();
        assertThat(assemblyStart.get())
                .as("assembly requires package-jar only, so it must start before the suite ends")
                .isBefore(testsEnd.get());
    }

    /**
     * The plan's terminal join is the pattern {@code PlannerTails} uses to keep {@code run-tests}
     * scheduled without making it a gate. First-ready must not let a join through early: it
     * requires everything, so it runs last, and a plan that "finished" before its suite would
     * report a green build for tests that never ran.
     */
    @Test
    void a_join_still_waits_for_every_branch() {
        var plan = BuildPlan.builder("joined")
                .addTask(Task.builder("package-jar")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("run-tests")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> Thread.sleep(200))
                        .build())
                .addTask(Task.builder("native-image")
                        .kind(TaskKind.IO)
                        .requires("package-jar")
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("deliver")
                        .requires("native-image", "run-tests")
                        .execute(ctx -> {})
                        .build())
                .build();

        BuildPlanResult r = plan.run();

        assertThat(r.success()).isTrue();
        assertThat(r.steps()).extracting(BuildPlanResult.StepReport::status).containsOnly(TaskStatus.SUCCESS);
        assertThat(r.steps()).extracting(BuildPlanResult.StepReport::name).contains("deliver", "run-tests");
    }

    /**
     * Fail-fast has to get *stronger*, not weaker, when packaging overlaps tests. Under the level
     * executor a red suite failed the plan before the expensive tail was admitted at all, so
     * "return early" cost nothing. First-ready means the tail is already in flight, and waiting for
     * it would make every failed build as slow as a successful one — so a failure flips the
     * cooperative cancel flag and the long step observes {@code ctx.cancelled()} and bails.
     */
    @Test
    void a_failing_step_cancels_an_already_running_sibling_instead_of_waiting_it_out() {
        AtomicBoolean sawCancelled = new AtomicBoolean();
        var plan = BuildPlan.builder("failing")
                .addTask(Task.builder("package-jar")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("run-tests")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> {
                            Thread.sleep(150);
                            throw new RuntimeException("suite is red");
                        })
                        .build())
                .addTask(Task.builder("native-image")
                        .kind(TaskKind.IO)
                        .requires("package-jar")
                        .execute(ctx -> {
                            // Stand in for a multi-minute Graal run that polls the flag.
                            for (int i = 0; i < 400; i++) {
                                if (ctx.cancelled()) {
                                    sawCancelled.set(true);
                                    throw new RuntimeException("cancelled");
                                }
                                Thread.sleep(25);
                            }
                        })
                        .build())
                .build();

        Instant t0 = Instant.now();
        BuildPlanResult r = plan.run();
        Duration wall = Duration.between(t0, Instant.now());

        assertThat(r.success()).isFalse();
        assertThat(sawCancelled)
                .as("the long sibling must observe the cancel, not run to completion")
                .isTrue();
        assertThat(wall)
                .as("plan must not wait out the full 10 s tail after the suite went red")
                .isLessThan(Duration.ofSeconds(8));
        assertThat(statusOf(r, "run-tests")).isEqualTo(TaskStatus.FAIL);
    }

    /**
     * The SYNC arm of the same promise: a step that fails inline on the plan thread must flip the
     * flag before the plan blocks on the next async terminal, or the in-flight sibling runs out its
     * whole wall first.
     */
    @Test
    void a_failing_sync_step_cancels_an_in_flight_sibling_without_waiting_for_it() {
        AtomicBoolean sawCancelled = new AtomicBoolean();
        var plan = BuildPlan.builder("sync-failing")
                .addTask(Task.builder("package-jar")
                        .kind(TaskKind.CPU)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("native-image")
                        .kind(TaskKind.IO)
                        .requires("package-jar")
                        .execute(ctx -> {
                            for (int i = 0; i < 400; i++) {
                                if (ctx.cancelled()) {
                                    sawCancelled.set(true);
                                    throw new RuntimeException("cancelled");
                                }
                                Thread.sleep(25);
                            }
                        })
                        .build())
                // An OTHER-stage join (unknown name), so it may follow package-jar: a stage-ordered
                // name like write-stamp could not, and SYNC steps are joins and stamps by design.
                .addTask(Task.builder("finalize-join")
                        .kind(TaskKind.SYNC)
                        .requires("package-jar")
                        .execute(ctx -> {
                            Thread.sleep(150);
                            throw new IllegalStateException("stamp dir vanished");
                        })
                        .build())
                .build();

        Instant t0 = Instant.now();
        BuildPlanResult r = plan.run();
        Duration wall = Duration.between(t0, Instant.now());

        assertThat(r.success()).isFalse();
        assertThat(sawCancelled)
                .as("the in-flight sibling must observe the cancel")
                .isTrue();
        assertThat(wall).as("no waiting out the 10 s tail").isLessThan(Duration.ofSeconds(8));
        assertThat(statusOf(r, "finalize-join")).isEqualTo(TaskStatus.FAIL);
    }

    /**
     * Admission order is longest-weighted-chain first with declaration order as the tiebreak, so
     * two runs of one plan submit in the same order. Before the tiebreak existed, {@code topoSort}
     * seeded its ready set from a {@code HashMap} and independent steps could be submitted in
     * either order from run to run — which matters because ready steps race for {@code PluginSlots}
     * permits.
     */
    @Test
    void admission_order_is_repeatable_across_runs() {
        assertThat(startOrder()).isEqualTo(startOrder());
    }

    private static List<String> startOrder() {
        var recorded = new CopyOnWriteArrayList<String>();
        var plan = BuildPlan.builder("order")
                .addTask(Task.builder("root").execute(ctx -> {}).build())
                .addTask(Task.builder("cheap-leaf")
                        .requires("root")
                        .weight(1)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("spine-a")
                        .requires("root")
                        .weight(5)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("spine-b")
                        .requires("spine-a")
                        .weight(50)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("mid-leaf")
                        .requires("root")
                        .weight(2)
                        .execute(ctx -> {})
                        .build())
                .addListener(new BuildPlanListener() {
                    @Override
                    public void stepStart(String step, @Nullable String group, int ticks) {
                        recorded.add(step);
                    }
                })
                .build();
        plan.run();
        return List.copyOf(recorded);
    }

    private static TaskStatus statusOf(BuildPlanResult r, String step) {
        return r.steps().stream()
                .filter(s -> s.name().equals(step))
                .map(BuildPlanResult.StepReport::status)
                .findFirst()
                .orElseThrow();
    }
}
