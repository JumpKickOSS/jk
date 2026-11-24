// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.orchestrator.TestRun;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A module's outcome carries the counts of the suite it ran or replayed, so the client can say what was skipped. */
class ModuleOutcomeTestsTest {

    @Test
    void a_replayed_suite_carries_the_tests_its_stamp_kept_and_says_it_replayed() {
        TestSummary stamped = new TestSummary(12, 10, 0, 2, List.of());

        assertThat(testsOf(stamped, true)).isEqualTo(new ModuleOutcome.Tests(12, 2, true));
    }

    @Test
    void a_suite_that_ran_says_it_did_not_replay() {
        TestSummary ran = new TestSummary(12, 10, 0, 2, List.of());

        assertThat(testsOf(ran, false)).isEqualTo(new ModuleOutcome.Tests(12, 2, false));
    }

    @Test
    void a_module_whose_suite_left_no_counts_carries_none() {
        assertThat(testsOf(null, false)).isNull();
    }

    /**
     * The tests of a finished module plan whose run-tests step left {@code result} behind, as a run
     * or a replay does; a replay marks the step cached.
     */
    private static ModuleOutcome.@Nullable Tests testsOf(@Nullable TestSummary result, boolean replay) {
        BuildPlan plan = BuildPlan.builder("module")
                .stateKeys(BuildPlanner.TEST_RUN)
                .addTask(Task.builder("run-tests")
                        .ticks(1)
                        .execute(ctx -> {
                            if (result != null) ctx.put(BuildPlanner.TEST_RUN, TestRun.of(result));
                            if (replay) ctx.cached();
                        })
                        .build())
                .build();
        BuildPlanResult ran = plan.run();
        return NativePlans.testsOf(plan, ran);
    }
}
