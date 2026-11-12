// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A step's wait on hold for memory does not teach the ledger (JK-VYJ4X). */
class RunStepLedgerHoldTest {

    @Test
    void a_held_runs_long_wait_comes_off_the_wall_and_a_short_one_or_an_unheld_run_keeps_it() {
        var suite = new BuildRecord.Task("run-tests", "test", "SUCCESS", 80_000, 19_000);
        var queued = new BuildRecord.Task("compile-java", "compile", "SUCCESS", 6_000, 200);

        assertThat(RunStepLedger.sample("/ws/a", suite, true).ms()).isEqualTo(61_000);
        assertThat(RunStepLedger.sample("/ws/a", queued, true).ms())
                .as("ordinary queueing")
                .isEqualTo(6_000);
        assertThat(RunStepLedger.sample("/ws/a", suite, false).ms())
                .as("no hold")
                .isEqualTo(80_000);
    }
}
