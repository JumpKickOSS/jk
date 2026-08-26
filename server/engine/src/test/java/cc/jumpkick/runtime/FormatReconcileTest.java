// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind {@code jk format}'s completeness check, on its own. {@link
 * FormatWorkerCompletenessTest} proves the format step actually consults it, against a real forked
 * worker; this file pins the verdicts and the wording of the diagnostic.
 *
 * <p>A dead worker cannot be detected by looking at any file's status — it reports nothing, and
 * nothing is exactly what a clean file reports too. Only a count against a total can see silence.
 */
class FormatReconcileTest {

    @Test
    void a_run_that_visited_every_file_reconciles() {
        assertThat(FormatWorker.reconcile(2088, 2088, 0)).isNull();
        assertThat(FormatWorker.reconcile(0, 0, 0)).isNull();
    }

    /** The worker's own exit law: {@code 1} is {@code --check} drift (or per-file errors), not death. */
    @Test
    void the_workers_own_drift_code_is_not_a_crash() {
        assertThat(FormatWorker.reconcile(2088, 2088, 1)).isNull();
    }

    @Test
    void a_shortfall_names_the_unvisited_files_and_the_exit() {
        String d = FormatWorker.reconcile(500, 2063, 139);

        assertThat(d).isNotNull();
        assertThat(d)
                .contains("500 of 2063")
                .contains("1563 were never visited")
                .contains("worker exit 139")
                .contains("retries them");
    }

    /**
     * More results than files is the same defect read from the other side — a tally that does not
     * describe the run. Reporting it as a shortfall of a negative number would be worse than saying
     * nothing, so it gets its own sentence.
     */
    @Test
    void more_results_than_files_is_also_a_failure() {
        String d = FormatWorker.reconcile(12, 8, 0);

        assertThat(d).isNotNull();
        assertThat(d).contains("12 files but only 8 were planned").contains("4 more results than files");
    }

    /**
     * A death after the last file event: the count balances, so only the exit vocabulary can see it.
     * Leaving this out would let the wedge print green while the CLI handed the shell a 139.
     */
    @Test
    void an_exit_outside_the_workers_vocabulary_fails_even_when_the_count_balances() {
        assertThat(FormatWorker.reconcile(8, 8, 139))
                .isNotNull()
                .contains("exited 139")
                .contains("exit law is 0 or 1");
        assertThat(FormatWorker.reconcile(8, 8, 137)).isNotNull().contains("exited 137");
        assertThat(FormatWorker.reconcile(8, 8, 70))
                .as("Exit.SOFTWARE out of PluginMain means the worker jar is built wrong — still a death")
                .isNotNull();
    }
}
