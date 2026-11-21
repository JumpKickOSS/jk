// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static cc.jumpkick.engine.journal.JkResultsAgentTest.record;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The agent report's headline counts skipped tests, and says why when no test ran. */
class JkResultsAgentSkipsTest {

    @Test
    void an_ok_run_with_skipped_tests_says_how_many() {
        BuildRecord r = record("test", true, false, 500, new BuildRecord.Tests(3, 2, 0, 1), List.of(), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("OK test rest-service · 3 tests, 1 skipped · 500ms\n");
    }

    @Test
    void a_failed_run_with_skipped_tests_says_how_many_beside_the_failures() {
        BuildRecord r = record("test", false, false, 500, new BuildRecord.Tests(5, 2, 1, 2), List.of(), List.of());
        assertThat(JkResultsAgent.render(r)).startsWith("FAIL test rest-service · 1 of 5 failed, 2 skipped · 500ms");
    }

    /** {@code jk test --class} on a class whose one test is disabled: the headline says why nothing ran. */
    @Test
    void an_ok_run_whose_every_test_was_skipped_names_the_reason() {
        MarkdownTestReport.Entry off =
                new MarkdownTestReport.Entry("com.example.AppTests", "adds()", 0, null, null, "not on CI");
        var run = new MarkdownTestReport.ModuleRun("/ws", "rest-service", List.of(off));
        BuildRecord r = record("test", true, false, 500, new BuildRecord.Tests(1, 0, 0, 1), List.of(), List.of());
        assertThat(JkResultsAgent.render(r, List.of(run)))
                .isEqualTo("OK test rest-service · 1 test, 1 skipped · 500ms — skipped: not on CI\n");
    }

    @Test
    void the_reason_of_an_all_skipped_run_counts_the_other_reasons() {
        var run = new MarkdownTestReport.ModuleRun(
                "/ws",
                "rest-service",
                List.of(
                        new MarkdownTestReport.Entry("A", "a()", 0, null, null, "not on CI"),
                        new MarkdownTestReport.Entry("A", "b()", 0, null, null, "not on CI"),
                        new MarkdownTestReport.Entry("A", "c()", 0, null, null, "needs docker")));
        BuildRecord r = record("test", true, false, 500, new BuildRecord.Tests(3, 0, 0, 3), List.of(), List.of());
        assertThat(JkResultsAgent.render(r, List.of(run))).endsWith(" — skipped: not on CI (+1 other reason)\n");
    }

    @Test
    void a_run_where_some_test_ran_gives_no_skip_reason() {
        var run = new MarkdownTestReport.ModuleRun(
                "/ws",
                "rest-service",
                List.of(
                        new MarkdownTestReport.Entry("A", "a()", 3, null, null, null),
                        new MarkdownTestReport.Entry("A", "b()", 0, null, null, "not on CI")));
        BuildRecord r = record("test", true, false, 500, new BuildRecord.Tests(2, 1, 0, 1), List.of(), List.of());
        assertThat(JkResultsAgent.render(r, List.of(run)))
                .isEqualTo("OK test rest-service · 2 tests, 1 skipped · 500ms\n");
    }
}
