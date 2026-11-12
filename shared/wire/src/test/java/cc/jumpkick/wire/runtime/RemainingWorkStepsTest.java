// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A model seeded with each module's steps counts a module down step by step, in ms at the fixed
 * {@link WorkModel#MS_PER_WEIGHT}: a short step that finishes first leaves the long one's whole
 * price, where a plan-tick fraction read the module nearly half done the moment its compile ended.
 */
class RemainingWorkStepsTest {

    private static final Path A = Path.of("/ws/a");
    private static final int MS = WorkModel.MS_PER_WEIGHT;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override
        public long millis() {
            return now.get();
        }

        @Override
        public long nanos() {
            return now.get() * 1_000_000;
        }
    };

    /** A module of a 6 s compile and a 90 s suite. */
    private RemainingWork model() {
        ModuleWorkCost cost = new ModuleWorkCost(A, Set.of(), 96_000 / MS, 90_000 / MS, 0, 6_000 / MS);
        return RemainingWork.seedSteps(
                        List.of(cost),
                        Map.of(
                                A,
                                List.of(
                                        new StepCost("compile-java", 6_000 / MS, StepCost.Kind.PRE),
                                        new StepCost("run-tests", 90_000 / MS, StepCost.Kind.TEST))),
                        4,
                        false,
                        false)
                .withClock(clock);
    }

    @Test
    void a_suite_on_other_workers_than_it_was_priced_at_is_repriced_at_them() {
        ModuleWorkCost cost = new ModuleWorkCost(A, Set.of(), 96_000 / MS, 90_000 / MS, 0, 6_000 / MS);
        RemainingWork rw = RemainingWork.seedSteps(
                        List.of(cost),
                        Map.of(
                                A,
                                List.of(
                                        new StepCost("compile-java", 6_000 / MS, StepCost.Kind.PRE),
                                        new StepCost(
                                                "run-tests",
                                                90_000 / MS,
                                                StepCost.Kind.TEST,
                                                90_000 / MS,
                                                90_000 / MS,
                                                2))),
                        4,
                        false,
                        false)
                .withClock(clock);
        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.stepStart(A, "run-tests");
        rw.suiteWorkers(A, 4, 0, 0);
        assertThat(rw.remaining())
                .as("priced on 2 JVMs, running on 4: (2/4)^0.6 of 90 s")
                .isCloseTo(Math.round(90_000 * Math.pow(0.5, StepCost.WORKER_EXPONENT)), within((long) MS));

        rw.suiteWorkers(A, 4, 600_000, 590_000);
        assertThat(rw.remaining())
                .as("a suite its longest class bounds gains nothing from more JVMs")
                .isCloseTo(90_000, within((long) MS));
    }

    @Test
    void a_suite_priced_with_no_worker_count_is_not_rescaled() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.stepStart(A, "run-tests");
        rw.suiteWorkers(A, 8, 0, 0);
        assertThat(rw.remaining()).isCloseTo(90_000, within((long) MS));
    }

    @Test
    void the_seed_is_the_step_prices_in_ms_with_no_scale() {
        RemainingWork rw = model();
        assertThat(rw.R0()).isEqualTo(96_000);
        assertThat(rw.remaining()).isEqualTo(96_000);
    }

    @Test
    void the_guard_chain_runs_beside_the_suite_not_ahead_of_it() {
        ModuleWorkCost cost = new ModuleWorkCost(A, Set.of(), 96_000 / MS, 90_000 / MS, 0, 6_000 / MS);
        RemainingWork rw = RemainingWork.seedSteps(
                        List.of(cost),
                        Map.of(
                                A,
                                List.of(
                                        new StepCost("compile-java", 6_000 / MS, StepCost.Kind.PRE),
                                        new StepCost("compile-guard", 3_000 / MS, StepCost.Kind.LANE),
                                        new StepCost("guard", 20_000 / MS, StepCost.Kind.LANE),
                                        new StepCost("run-tests", 90_000 / MS, StepCost.Kind.TEST))),
                        4,
                        false,
                        false)
                .withClock(clock);
        assertThat(rw.R0())
                .as("compile, then the suite (90 s) beside the guard chain (3 + 20 s)")
                .isEqualTo(96_000);

        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.stepStart(A, "run-tests");
        rw.stepStart(A, "compile-guard");
        now.addAndGet(85_000);
        rw.suiteProgress(A, 0.98);
        assertThat(rw.remaining())
                .as("the guard chain overran its price: it, not the nearly done suite, is what is left")
                .isGreaterThanOrEqualTo(20_000);
    }

    @Test
    void a_finished_short_step_leaves_the_long_steps_whole_price() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        now.addAndGet(6_000);
        rw.stepFinish(A, "compile-java");
        // A tick fraction would have read ~45% here; the module is 6 s into 96 s.
        rw.moduleProgress(A, 0.45);
        assertThat(rw.remaining()).isEqualTo(90_000);
    }

    @Test
    void a_running_step_counts_down_by_the_clock_and_holds_a_floor_once_it_overruns() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        now.addAndGet(2_000);
        assertThat(rw.remaining()).isEqualTo(94_050); // 4 s left of compile + the suite, in weight units
        now.addAndGet(60_000);
        assertThat(rw.remaining()).isEqualTo(90_300); // 5% of the compile's price + the suite
    }

    /**
     * An integration suite priced at 110 s from its default profile's wall ran 249 s, and held at a
     * twentieth of its price it read 4 s left for its last three minutes: a running step never has
     * less left than a quarter of the time it has run, up to what its high bound leaves.
     */
    @Test
    void a_running_step_has_a_share_of_its_run_left_up_to_its_high_bound() {
        ModuleWorkCost cost = new ModuleWorkCost(A, Set.of(), 110_000 / MS, 110_000 / MS, 0, 0);
        RemainingWork rw = RemainingWork.seedSteps(
                        List.of(cost),
                        Map.of(
                                A,
                                List.of(new StepCost(
                                        "run-tests", 110_000 / MS, StepCost.Kind.TEST, 80_000 / MS, 330_000 / MS, 0))),
                        4,
                        false,
                        false)
                .withClock(clock);
        rw.stepStart(A, "run-tests");
        now.addAndGet(60_000);
        assertThat(rw.remaining()).as("well under its price: the clock").isCloseTo(50_000, within((long) MS));
        now.addAndGet(40_000);
        assertThat(rw.remaining())
                .as("past four-fifths of it: a quarter of its run")
                .isCloseTo(25_000, within((long) MS));
        now.addAndGet(100_000);
        assertThat(rw.remaining()).as("200 s in: a quarter of it").isCloseTo(50_000, within((long) MS));
        now.addAndGet(120_000);
        assertThat(rw.remaining()).as("320 s in: what the high bound leaves").isCloseTo(10_000, within((long) MS));
    }

    @Test
    void a_running_suite_follows_its_class_walls_then_its_own_rate() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.stepStart(A, "run-tests");
        now.addAndGet(9_000);
        rw.suiteProgress(A, 0.10);
        assertThat(rw.remaining()).isEqualTo(81_000); // 90% of the priced suite: under half done
        now.addAndGet(51_000);
        rw.suiteProgress(A, 0.50);
        assertThat(rw.remaining()).isEqualTo(60_000); // half done in 60 s: the rest at that rate
    }

    /**
     * The engine suite's last class ran alone for 10 s and reported nothing: its remainder counts
     * down from the wall its rate projected, where the rate read at the clock grew it as it ran.
     */
    @Test
    void a_suite_that_reports_nothing_counts_down_from_its_last_projection() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.stepStart(A, "run-tests");
        now.addAndGet(40_000);
        rw.suiteProgress(A, 0.80);
        assertThat(rw.remaining()).as("40 s for 80%: 10 s more").isCloseTo(10_000, within((long) MS));
        now.addAndGet(3_000);
        assertThat(rw.remaining()).as("3 s on with nothing reported").isCloseTo(7_000, within((long) MS));
        now.addAndGet(20_000);
        assertThat(rw.remaining()).as("past its projection: the overrun floor").isCloseTo(4_500, within((long) MS));
    }

    @Test
    void a_finished_module_leaves_nothing() {
        RemainingWork rw = model();
        rw.moduleComplete(A);
        assertThat(rw.remaining()).isZero();
    }

    @Test
    void a_hold_for_memory_stops_a_running_step_counting_down_and_resumes_it_where_it_stood() {
        RemainingWork rw = model();
        rw.stepStart(A, "run-tests");
        now.addAndGet(10_000);
        long before = rw.remaining();

        rw.hold();
        now.addAndGet(30_000);
        assertThat(rw.remaining()).as("on hold").isEqualTo(before);

        rw.resume();
        assertThat(rw.remaining()).as("just resumed").isEqualTo(before);
        now.addAndGet(5_000);
        assertThat(rw.remaining()).as("counting again").isCloseTo(before - 5_000, within(2L * MS));
    }

    /**
     * A hold is one fork of the build waiting for memory; the others run on. The engine's suite
     * started inside a 21 s hold and ran through it: its share is what it really did, and read
     * against a clock that stood still its rate came out double and its remainder 19 s short.
     */
    @Test
    void a_suite_that_runs_through_a_hold_reads_its_rate_on_the_wall_clock() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.hold();
        rw.stepStart(A, "run-tests");
        now.addAndGet(20_000);
        rw.resume();
        now.addAndGet(20_000);
        rw.suiteProgress(A, 0.50);
        assertThat(rw.remaining())
                .as("half done in the 40 s it ran, 20 s of them on hold: the rest at that rate")
                .isCloseTo(40_000, within((long) MS));
    }

    /**
     * The engine's longest class, 13 s, ran alone after 91% of the suite's class walls had
     * finished; the share read the suite nearly done, and it showed 2 s left for 11 s.
     */
    @Test
    void a_long_class_still_running_holds_its_suite_open_for_its_recorded_wall() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.stepStart(A, "run-tests");
        now.addAndGet(50_000);
        rw.suiteClassStarted(A, "LongTest", 20_000);
        now.addAndGet(10_000);
        rw.suiteProgress(A, 0.98);
        assertThat(rw.remaining()).as("10 s into its 20 s").isCloseTo(10_000, within((long) MS));
        now.addAndGet(1_000);
        assertThat(rw.remaining()).as("counting down with it").isCloseTo(9_000, within((long) MS));
        rw.suiteClassFinished(A, "LongTest");
        assertThat(rw.remaining())
                .as("finished: the suite's own rate again, at its overrun floor")
                .isCloseTo(4_500, within((long) MS));
    }

    /**
     * A suite on its own rate kept no range: low and high were one point, and every checkpoint past
     * it fell outside the range shown (run 6 of jk's 2026-11-12 corpus, 52% of warm checkpoints
     * inside). The projection spreads as the suite's walls do.
     */
    @Test
    void a_suite_on_its_rate_projects_its_range_at_its_low_and_high_over_its_price() {
        ModuleWorkCost cost = new ModuleWorkCost(A, Set.of(), 90_000 / MS, 90_000 / MS, 0, 0);
        RemainingWork rw = RemainingWork.seedSteps(
                        List.of(cost),
                        Map.of(
                                A,
                                List.of(new StepCost(
                                        "run-tests", 90_000 / MS, StepCost.Kind.TEST, 60_000 / MS, 120_000 / MS))),
                        4,
                        false,
                        false)
                .withClock(clock);
        rw.stepStart(A, "run-tests");
        now.addAndGet(40_000);
        rw.suiteProgress(A, 0.80);
        assertThat(rw.remaining()).as("40 s for 80%").isCloseTo(10_000, within((long) MS));
        long[] range = rw.remainingRange();
        assertThat(range[0]).as("at two thirds of its price").isCloseTo(6_667, within((long) MS));
        assertThat(range[1]).as("at four thirds of its price").isCloseTo(13_333, within((long) MS));
    }

    @Test
    void the_likely_range_is_the_schedule_at_each_steps_low_and_high_and_narrows_as_steps_finish() {
        ModuleWorkCost cost = new ModuleWorkCost(A, Set.of(), 96_000 / MS, 90_000 / MS, 0, 6_000 / MS);
        RemainingWork rw = RemainingWork.seedSteps(
                        List.of(cost),
                        Map.of(
                                A,
                                List.of(
                                        new StepCost(
                                                "compile-java", 6_000 / MS, StepCost.Kind.PRE, 3_000 / MS, 12_000 / MS),
                                        new StepCost(
                                                "run-tests",
                                                90_000 / MS,
                                                StepCost.Kind.TEST,
                                                60_000 / MS,
                                                120_000 / MS))),
                        4,
                        false,
                        false)
                .withClock(clock);

        assertThat(rw.remainingRange()).containsExactly(63_000, 132_000);

        rw.stepStart(A, "compile-java");
        now.addAndGet(6_000);
        rw.stepFinish(A, "compile-java");
        assertThat(rw.remainingRange()).as("a finished step leaves no range").containsExactly(60_000, 120_000);
    }
}
