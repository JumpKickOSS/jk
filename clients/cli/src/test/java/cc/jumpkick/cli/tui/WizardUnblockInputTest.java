// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.Terminals;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * ModeGuard pop restores COOKED. StdinWake remains until JK-2377 and must stay safe without a tty.
 */
class WizardUnblockInputTest {

    @Test
    void prompt_guard_pops_to_cooked() {
        try (MemoryTerminal tty =
                Terminals.memory(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            assertThat(tty.mode()).isEqualTo(InputMode.COOKED);
            try (ModeGuard g = tty.enter(InputMode.PROMPT)) {
                assertThat(tty.mode()).isEqualTo(InputMode.PROMPT);
            }
            assertThat(tty.mode()).isEqualTo(InputMode.COOKED);
            assertThat(tty.isLive()).isTrue();
        }
    }

    @Test
    void stdin_wake_pulse_is_safe_without_a_tty() {
        assertThatCode(StdinWake::pulseNonBlocking).doesNotThrowAnyException();
    }
}
