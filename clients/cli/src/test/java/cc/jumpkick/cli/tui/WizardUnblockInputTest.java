// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

/**
 * {@link Wizard#unblockBlockingInput} must force non-canonical VMIN=0/VTIME=0 so a stuck JLine
 * NonBlocking stdin reader can return; {@link Wizard#restoreCooked} must leave ECHO+ICANON on.
 */
class WizardUnblockInputTest {

    private static Terminal dumbTerminal() throws Exception {
        return TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                // Force the type: with only dumb(true) as a fallback hint, JLine 4 still
                // types the terminal from $TERM and runs its mode-2027 grapheme probe
                // against the real controlling tty — which blocks forever in a forked
                // test worker whose inherited PTY is dead (JK-2201). Type dumb skips
                // the probe outright.
                .type("dumb")
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
    }

    @Test
    void unblock_sets_noncanonical_vmin0_vtime0() throws Exception {
        try (Terminal t = dumbTerminal()) {
            Attributes raw = t.getAttributes();
            raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            raw.setControlChar(Attributes.ControlChar.VMIN, 1);
            raw.setControlChar(Attributes.ControlChar.VTIME, 0);
            t.setAttributes(raw);

            Wizard.unblockBlockingInput(t);

            Attributes after = t.getAttributes();
            assertThat(after.getLocalFlag(Attributes.LocalFlag.ICANON)).isFalse();
            assertThat(after.getControlChar(Attributes.ControlChar.VMIN)).isZero();
            assertThat(after.getControlChar(Attributes.ControlChar.VTIME)).isZero();
        }
    }

    @Test
    void restore_cooked_forces_echo_and_icanon_after_unblock() throws Exception {
        try (Terminal t = dumbTerminal()) {
            Attributes saved = t.getAttributes();
            Attributes raw = new Attributes(saved);
            raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            raw.setControlChar(Attributes.ControlChar.VMIN, 1);
            raw.setControlChar(Attributes.ControlChar.VTIME, 0);
            t.setAttributes(raw);

            Wizard.restoreCooked(t, saved);

            Attributes after = t.getAttributes();
            assertThat(after.getLocalFlag(Attributes.LocalFlag.ECHO)).isTrue();
            assertThat(after.getLocalFlag(Attributes.LocalFlag.ICANON)).isTrue();
        }
    }

    @Test
    void unblock_null_is_a_no_op() {
        Wizard.unblockBlockingInput(null);
    }

    @Test
    void stdin_wake_pulse_is_safe_without_a_tty() {
        // Must not throw when FD 0 is not a terminal (Gradle test worker).
        StdinWake.pulseNonBlocking();
    }
}
