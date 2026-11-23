// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.orchestrator.TestRun;
import cc.jumpkick.run.BuildPlan;
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
    void a_replayed_suite_carries_the_tests_its_stamp_kept() {
        TestSummary stamped = new TestSummary(12, 10, 0, 2, List.of());

        assertThat(NativePlans.testsOf(plan(stamped))).isEqualTo(new ModuleOutcome.Tests(12, 2));
    }

    @Test
    void a_module_whose_suite_left_no_counts_carries_none() {
        assertThat(NativePlans.testsOf(plan(null))).isNull();
    }

    /** A finished module plan whose run-tests step left {@code result} behind, as a run or a replay does. */
    private static BuildPlan plan(@Nullable TestSummary result) {
        BuildPlan plan = BuildPlan.builder("module")
                .stateKeys(BuildPlanner.TEST_RUN)
                .addTask(Task.builder("run-tests")
                        .ticks(1)
                        .execute(ctx -> {
                            if (result != null) ctx.put(BuildPlanner.TEST_RUN, TestRun.of(result));
                        })
                        .build())
                .build();
        plan.run();
        return plan;
    }
}
