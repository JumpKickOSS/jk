// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static cc.jumpkick.engine.journal.JkResultsAgentTest.record;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A run whose only failures were in quarantined classes passed, and its verdict says why in one line. */
class JkResultsQuarantineTest {

    private static BuildRecord.Diag warning(String code, String message) {
        return new BuildRecord.Diag("warning", "/ws/rest-service", "run-tests", code, message, null, null);
    }

    private static final BuildRecord.Diag HELD =
            warning("test-quarantined-failed", "quarantined flaky test failed, not your change: demo.FlakyTest");

    private static final BuildRecord.Tests ONE_FAILED = new BuildRecord.Tests(3, 2, 1, 0);

    @Test
    void the_verdict_names_the_quarantined_class_in_place_of_failing() {
        BuildRecord r = record("test", true, false, 500, ONE_FAILED, List.of(HELD), List.of());
        assertThat(JkResultsAgent.render(r))
                .startsWith("OK test rest-service · 1 of 3 failed · 500ms — quarantined flaky test failed, not your"
                        + " change: demo.FlakyTest\n");
    }

    @Test
    void the_count_says_quarantined_and_a_reported_failure_still_says_reported() {
        StringBuilder held = new StringBuilder();
        JkResultsTestsSection.appendCount(
                held, record("test", true, false, 500, ONE_FAILED, List.of(HELD), List.of()), List.of());
        assertThat(held.toString()).contains(JkResultsTestsSection.QUARANTINED).doesNotContain("[test] failures");

        BuildRecord both = record(
                "test",
                true,
                false,
                500,
                ONE_FAILED,
                List.of(HELD, warning("test-failures-reported", "1 test failure reported, not failing")),
                List.of());
        StringBuilder reported = new StringBuilder();
        JkResultsTestsSection.appendCount(reported, both, List.of());
        assertThat(reported.toString()).contains(JkResultsTestsSection.REPORTED);
        assertThat(JkResultsAgent.render(both)).contains("test failures reported, not failing");
    }
}
