// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The exit code of a finished standalone run. */
class BuildRunExitTest {

    private static BuildPlanResult failed(String code) {
        return new BuildPlanResult(
                "test",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("run-tests", code, "why")),
                false);
    }

    @Test
    void a_class_selection_that_matched_nothing_is_a_usage_error() {
        assertThat(BuildRun.exitOf(failed("no-tests-matched"), null)).isEqualTo(Exit.USAGE);
    }

    @Test
    void red_tests_beside_a_dropped_class_still_exit_as_test_failures() {
        TestSummary red =
                new TestSummary(1, 0, 1, 0, List.of(new TestFailureInfo("m", "a.FooTest", "x", "x()", "", "boom", "")));
        assertThat(BuildRun.exitOf(failed("no-tests-matched"), red)).isEqualTo(Exit.TESTS_FAILED);
    }
}
