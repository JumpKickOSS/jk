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
    void the_seed_is_the_step_prices_in_ms_with_no_scale() {
        RemainingWork rw = model();
        assertThat(rw.R0()).isEqualTo(96_000);
        assertThat(rw.remaining()).isEqualTo(96_000);
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

    @Test
    void a_running_suite_follows_its_class_walls_then_its_own_rate() {
        RemainingWork rw = model();
        rw.stepStart(A, "compile-java");
        rw.stepFinish(A, "compile-java");
        rw.stepStart(A, "run-tests");
        now.addAndGet(9_000);
        rw.suiteProgress(A, 0.10);
        assertThat(rw.remaining()).isEqualTo(81_000); // 90% of the priced suite: under a quarter done
        now.addAndGet(51_000);
        rw.suiteProgress(A, 0.50);
        assertThat(rw.remaining()).isEqualTo(60_000); // half done in 60 s: the rest at that rate
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
}
