// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.command.Exit;
import org.junit.jupiter.api.Test;

/**
 * What a {@code jk import} job is journaled as.
 *
 * <p>{@code CompatPlans} publishes the importer's exit code as a plan result rather than failing the
 * step, so a conversion the importer refused still runs its plan to the end. Without folding the two
 * the job reads green while the command exits non-zero.
 */
class ImportVerbVerdictTest {

    @Test
    void a_clean_conversion_is_a_green_job() {
        assertThat(ImportVerb.verdict(JobOutcome.ok(), 0)).isEqualTo(JobOutcome.ok());
    }

    /** The importer's own code, not a flat failure: it is what {@code jk import} exits with. */
    @Test
    void a_refused_conversion_carries_the_importers_exit_code() {
        JobOutcome outcome = ImportVerb.verdict(JobOutcome.ok(), Exit.DATA_ERR);

        assertThat(outcome).isInstanceOf(JobOutcome.Failed.class);
        assertThat(((JobOutcome.Failed) outcome).exitCode()).isEqualTo(Exit.DATA_ERR);
    }

    @Test
    void a_failed_plan_keeps_its_verdict() {
        JobOutcome dead = JobOutcome.failed(Exit.SOFTWARE);

        assertThat(ImportVerb.verdict(dead, 0)).isEqualTo(dead);
        assertThat(ImportVerb.verdict(dead, Exit.DATA_ERR)).isEqualTo(dead);
    }

    @Test
    void a_cancelled_run_stays_cancelled() {
        assertThat(ImportVerb.verdict(JobOutcome.cancelled(), Exit.DATA_ERR)).isEqualTo(JobOutcome.cancelled());
    }
}
