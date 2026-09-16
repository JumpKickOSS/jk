// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The {@code SigIgn} decoder behind the {@code ignoredSignals} vital. */
class TerminalSignalsTest {

    private static final String STATUS_TEMPLATE = "Name:\tjava\nSigQ:\t0/126000\nSigPnd:\t0000000000000000\n"
            + "SigBlk:\t0000000000000000\nSigIgn:\t%s\nSigCgt:\t2000000181005ccf\n";

    @Test
    void a_clear_mask_reads_as_nothing_ignored() {
        assertThat(TerminalSignals.ignoredSignals(STATUS_TEMPLATE.formatted("0000000000000000")))
                .isEmpty();
    }

    @Test
    void the_background_shell_mask_names_hangup_and_interrupt() {
        // Bit 0 is SIGHUP (1), bit 1 SIGINT (2): the mask a non-interactive shell's background job leaves.
        assertThat(TerminalSignals.ignoredSignals(STATUS_TEMPLATE.formatted("0000000000000003")))
                .isEqualTo("HUP, INT");
        assertThat(TerminalSignals.ignoredSignals(STATUS_TEMPLATE.formatted("0000000000001002")))
                .isEqualTo("INT, PIPE");
    }

    @Test
    void a_status_without_the_line_reads_as_unobservable() {
        assertThat(TerminalSignals.ignoredSignals("Name:\tjava\n")).isEmpty();
    }
}
