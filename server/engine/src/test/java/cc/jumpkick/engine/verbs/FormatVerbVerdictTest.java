// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.command.Exit;
import org.junit.jupiter.api.Test;

/**
 * What a {@code jk format} job is journaled as.
 *
 * <p>The plan's own verdict answers whether the run reached the end, and a run with one
 * unformattable file reaches the end — the completeness check is about a worker that died, and
 * treats the worker's exit {@code 1} as the legitimate code it is. So the plan succeeds, and
 * without this combination the job the dashboard shows for a tree it could not format reads green.
 *
 * <p>Only {@code error} counts here. {@code --check} drift is reported as {@code changed}, so a
 * drifted tree is a complete, green <em>job</em> whose command still exits 1.
 */
class FormatVerbVerdictTest {

    @Test
    void a_complete_clean_run_is_a_green_job() {
        assertThat(FormatVerb.verdict(JobOutcome.ok(), 0)).isEqualTo(JobOutcome.ok());
    }

    @Test
    void a_complete_run_that_could_not_format_a_file_is_not_a_green_job() {
        JobOutcome outcome = FormatVerb.verdict(JobOutcome.ok(), 1);

        assertThat(outcome).isInstanceOf(JobOutcome.Failed.class);
        // The run happened and was not clean, which is the ordinary failure code — not SOFTWARE,
        // which the CLI reserves for a format that never ran.
        assertThat(((JobOutcome.Failed) outcome).exitCode()).isEqualTo(Exit.FAILURE);
    }

    /** A plan that already failed keeps its own code: a dead worker is not re-diagnosed here. */
    @Test
    void a_failed_plan_keeps_its_verdict_whatever_the_error_count() {
        JobOutcome dead = JobOutcome.failed(Exit.SOFTWARE);

        assertThat(FormatVerb.verdict(dead, 0)).isEqualTo(dead);
        assertThat(FormatVerb.verdict(dead, 7)).isEqualTo(dead);
    }

    /**
     * Cancellation is not a per-file fact. A user who stops a format mid-run gets a cancelled row,
     * not a failed one, even though the files still in flight will never report.
     */
    @Test
    void a_cancelled_run_stays_cancelled() {
        assertThat(FormatVerb.verdict(JobOutcome.cancelled(), 3)).isEqualTo(JobOutcome.cancelled());
    }
}
