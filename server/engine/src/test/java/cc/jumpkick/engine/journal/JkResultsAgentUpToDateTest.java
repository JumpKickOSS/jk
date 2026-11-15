// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The agent headline says a run was up to date when it did no work, and only then. */
class JkResultsAgentUpToDateTest {

    @Test
    void an_ok_build_that_did_no_work_says_it_was_up_to_date() {
        BuildRecord nothingRan = JkResultsAgentTest.record("build", true, false, 24, null, List.of(), List.of());
        assertThat(JkResultsAgent.render(nothingRan)).isEqualTo("OK build rest-service · up to date · 24ms\n");

        BuildRecord restored = JkResultsAgentTest.record(
                "build",
                true,
                false,
                31,
                null,
                List.of(),
                List.of(new BuildRecord.Task("compile-main", "COMPILE", "SKIPPED", 3, 0, "cache hit")));
        assertThat(JkResultsAgent.render(restored)).isEqualTo("OK build rest-service · up to date · 31ms\n");

        BuildRecord replayed = JkResultsAgentTest.record(
                "test",
                true,
                false,
                40,
                new BuildRecord.Tests(2, 2, 0, 0),
                List.of(),
                List.of(new BuildRecord.Task("run-tests", "TEST", "SKIPPED", 5, 0)));
        assertThat(JkResultsAgent.render(replayed)).isEqualTo("OK test rest-service · 2 tests · up to date · 40ms\n");
    }

    @Test
    void a_build_that_did_work_or_failed_or_ran_another_tool_is_not_up_to_date() {
        BuildRecord compiled = JkResultsAgentTest.record(
                "build",
                true,
                false,
                900,
                null,
                List.of(),
                List.of(new BuildRecord.Task("compile-main", "COMPILE", "SUCCESS", 800, 0)));
        assertThat(JkResultsAgent.render(compiled)).isEqualTo("OK build rest-service · 900ms\n");
        assertThat(RecordUpToDate.of(JkResultsAgentTest.record("build", false, false, 10, null, List.of(), List.of())))
                .isFalse();
        assertThat(RecordUpToDate.of(JkResultsAgentTest.record("mvn", true, false, 10, null, List.of(), List.of())))
                .isFalse();
    }
}
