// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code jk format --check} exits 1 on drift, so its closing wedge has to agree with its
 * exit code.
 */
class FormatSummaryTest {

    /**
     * A file still in flight is chatter, not a verdict: the wedge's counts must not see it, and the
     * line must name the path — a stalled run that says nothing is the defect this line exists for.
     */
    @Test
    void a_file_still_in_flight_is_named_with_its_elapsed_time() {
        String line = FormatCommand.slowLine(
                "/w/proj/src/main/java/demo/Nested.java", Path.of("/w/proj"), "still formatting after 6.4s");

        assertThat(line)
                .contains("slow")
                .contains("src/main/java/demo/Nested.java")
                .endsWith("still formatting after 6.4s");
    }

    @Test
    void check_on_drift_reads_as_a_failure_and_names_the_fix() {
        var summary = FormatCommand.summarize(true, 14, 1445, 0, "took 7.0s");

        assertThat(summary.failed()).isTrue();
        assertThat(summary.body()).contains("14 files unformatted").contains("jk format");
    }

    @Test
    void check_on_a_clean_tree_is_a_success() {
        var summary = FormatCommand.summarize(true, 0, 1459, 0, "took 7.0s");

        assertThat(summary.failed()).isFalse();
        assertThat(summary.body()).startsWith("Already formatted");
    }

    @Test
    void formatting_files_is_work_done_not_a_failure() {
        var summary = FormatCommand.summarize(false, 14, 1445, 0, "took 4.5s");

        assertThat(summary.failed()).isFalse();
        assertThat(summary.body()).startsWith("Formatted 14 files");
    }

    @Test
    void errors_fail_in_both_modes() {
        assertThat(FormatCommand.summarize(true, 0, 10, 2, "took 1s").failed()).isTrue();
        assertThat(FormatCommand.summarize(false, 3, 10, 2, "took 1s").failed()).isTrue();
        assertThat(FormatCommand.summarize(false, 3, 10, 1, "took 1s").body()).contains("1 error ");
    }

    /**
     * A plan failure and {@code --check} drift must not arrive at the shell as the same number.
     * Drift exits with the worker's own {@code 1}; a plan that did not run to completion exits
     * {@link Exit#SOFTWARE}.
     */
    @Test
    void a_failed_plan_and_check_drift_do_not_share_an_exit_code() {
        assertThat(FormatCommand.PLAN_FAILED).isEqualTo(Exit.SOFTWARE).isNotEqualTo(Exit.FAILURE);
    }

    @Test
    void one_file_is_singular() {
        assertThat(FormatCommand.summarize(true, 1, 0, 0, "took 1s").body()).contains("1 file unformatted");
        assertThat(FormatCommand.summarize(false, 1, 0, 0, "took 1s").body()).startsWith("Formatted 1 file ");
    }
}
