// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.posix.TermiosLinux;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * Regresses the Mac Ctrl-O failure mode: with IEXTEN left on, the kernel treats Ctrl-O as VDISCARD
 * and never delivers 0x0F. PLAN_KEYS also clears IXON.
 */
class OutputKeyListenerAttributesTest {

    @Test
    void plan_keys_clears_iexten_and_ixon_keeps_isig() {
        try (Arena arena = Arena.ofConfined()) {
            var t = arena.allocate(TermiosLinux.SIZE);
            t.set(ValueLayout.JAVA_INT, 0, TermiosLinux.IXON | TermiosLinux.ICRNL);
            t.set(
                    ValueLayout.JAVA_INT,
                    12,
                    TermiosLinux.ECHO | TermiosLinux.ICANON | TermiosLinux.IEXTEN | TermiosLinux.ISIG);
            TermiosLinux.apply(t, InputMode.PLAN_KEYS);
            assertThat(TermiosLinux.ixonOff(t)).isTrue();
            assertThat(TermiosLinux.iextenOff(t)).isTrue();
            assertThat(TermiosLinux.isigOn(t)).isTrue();
            assertThat(TermiosLinux.cc(t, TermiosLinux.VMIN)).isEqualTo(1);
            assertThat(TermiosLinux.cc(t, TermiosLinux.VTIME)).isEqualTo(0);
        }
    }
}
