// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.RemainingWork;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The build's first steps run while its work model is still being priced. A step that started
 * before the model arrived is replayed into it from when it really started: a 15 s guard that
 * began half a second before the model read "16 s left" for its whole run (jk's 2026-11-12 corpus,
 * every `jk test --class` run).
 */
class JobSessionStepMarksTest {

    private static final Path A = Path.of("/ws/a");
    private static final Path B = Path.of("/ws/b");
    private static final int MS = WorkModel.MS_PER_WEIGHT;
    private static final long NOW = 1_000_000;

    private static final Clock CLOCK = new Clock() {
        @Override
        public long millis() {
            return NOW;
        }

        @Override
        public long nanos() {
            return NOW * 1_000_000;
        }
    };

    /** Module a: a 15 s guard. Module b: a 3 s compile then a 6 s suite. */
    private static RemainingWork model() {
        return RemainingWork.seedSteps(
                        List.of(
                                new ModuleWorkCost(A, Set.of(), 15_000 / MS, 0, 0, 15_000 / MS),
                                new ModuleWorkCost(B, Set.of(), 9_000 / MS, 6_000 / MS, 0, 3_000 / MS)),
                        Map.of(
                                A,
                                List.of(new StepCost("guard", 15_000 / MS, StepCost.Kind.PRE)),
                                B,
                                List.of(
                                        new StepCost("compile-java", 3_000 / MS, StepCost.Kind.PRE),
                                        new StepCost("run-tests", 6_000 / MS, StepCost.Kind.TEST))),
                        4,
                        false,
                        false)
                .withClock(CLOCK);
    }

    @Test
    void a_step_that_started_before_the_model_counts_down_from_its_real_start() {
        JobSession s = new JobSession(7);
        assertThat(s.stepMarked(new JobSession.StepMark(A.toString(), "guard", true, NOW - 5_000)))
                .as("no model yet: the mark waits on the session")
                .isNull();

        s.remaining(model());

        RemainingWork rw = Objects.requireNonNull(s.remaining());
        synchronized (rw) {
            assertThat(rw.remaining())
                    .as("the guard has run 5 s of its 15: 10 s left, not the whole price")
                    .isCloseTo(10_000, within((long) MS));
        }
    }

    @Test
    void a_step_that_finished_before_the_model_is_not_priced_again() {
        JobSession s = new JobSession(8);
        s.stepMarked(new JobSession.StepMark(B.toString(), "compile-java", true, NOW - 3_000));
        s.stepMarked(new JobSession.StepMark(B.toString(), "compile-java", false, NOW));
        s.stepMarked(new JobSession.StepMark(A.toString(), "guard", true, NOW - 3_000));

        s.remaining(model());

        RemainingWork rw = Objects.requireNonNull(s.remaining());
        synchronized (rw) {
            assertThat(rw.remaining())
                    .as("b's suite (6 s) is left behind its compile; a's guard has 12 s left")
                    .isCloseTo(12_000, within((long) MS));
        }
    }

    @Test
    void a_mark_after_the_model_is_returned_to_apply_now() {
        JobSession s = new JobSession(9);
        s.remaining(model());
        assertThat(s.stepMarked(new JobSession.StepMark(A.toString(), "guard", true, NOW)))
                .isSameAs(s.remaining());
    }

    @Test
    void a_retired_session_keeps_no_marks() {
        JobSession s = new JobSession(10);
        s.retire();
        assertThat(s.stepMarked(new JobSession.StepMark(A.toString(), "guard", true, NOW)))
                .isNull();
    }
}
