// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * JK-1670: {@code jk format --check} exits 1 on drift, so its closing wedge has to agree with its
 * exit code. It used to print a green {@code ✓ Format  14 to format, …} and return 1.
 */
class FormatSummaryTest {

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

    @Test
    void one_file_is_singular() {
        assertThat(FormatCommand.summarize(true, 1, 0, 0, "took 1s").body()).contains("1 file unformatted");
        assertThat(FormatCommand.summarize(false, 1, 0, 0, "took 1s").body()).startsWith("Formatted 1 file ");
    }
}
