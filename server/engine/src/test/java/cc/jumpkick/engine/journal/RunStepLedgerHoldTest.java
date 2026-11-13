// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A step's time on hold for memory does not teach the ledger; every other wait does. */
class RunStepLedgerHoldTest {

    @Test
    void a_held_runs_wait_comes_off_the_wall_up_to_its_time_on_hold_and_an_unheld_run_keeps_it() {
        var suite = new BuildRecord.Task("run-tests", "test", "SUCCESS", 80_000, 19_000);

        assertThat(RunStepLedger.sample("/ws/a", suite, 30_000).ms()).isEqualTo(61_000);
        assertThat(RunStepLedger.sample("/ws/a", suite, 7_000).ms())
                .as("only the time on hold")
                .isEqualTo(73_000);
        assertThat(RunStepLedger.sample("/ws/a", suite, 0).ms())
                .as("no hold: slot waits are build time")
                .isEqualTo(80_000);
    }
}
