// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.InputMode;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

class TermiosLinuxTest {
    @Test
    void planKeysClearsIxonAndIextenKeepsIsig() {
        try (Arena arena = Arena.ofConfined()) {
            var t = arena.allocate(TermiosLinux.SIZE);
            t.set(ValueLayout.JAVA_INT, 0, TermiosLinux.IXON | TermiosLinux.ICRNL);
            t.set(
                    ValueLayout.JAVA_INT,
                    12,
                    TermiosLinux.ECHO | TermiosLinux.ICANON | TermiosLinux.IEXTEN | TermiosLinux.ISIG);
            t.set(ValueLayout.JAVA_BYTE, 17 + TermiosLinux.VMIN, (byte) 0);
            TermiosLinux.apply(t, InputMode.PLAN_KEYS);
            assertThat(TermiosLinux.ixonOff(t)).isTrue();
            assertThat(TermiosLinux.iextenOff(t)).isTrue();
            assertThat(TermiosLinux.isigOn(t)).isTrue();
            assertThat(TermiosLinux.cc(t, TermiosLinux.VMIN)).isEqualTo(1);
            assertThat(TermiosLinux.cc(t, TermiosLinux.VTIME)).isEqualTo(0);
        }
    }

    @Test
    void promptClearsIsig() {
        try (Arena arena = Arena.ofConfined()) {
            var t = arena.allocate(TermiosLinux.SIZE);
            t.set(ValueLayout.JAVA_INT, 12, TermiosLinux.ISIG | TermiosLinux.ECHO);
            TermiosLinux.apply(t, InputMode.PROMPT);
            assertThat(TermiosLinux.isigOn(t)).isFalse();
        }
    }

    @Test
    void cookedLeavesBits() {
        try (Arena arena = Arena.ofConfined()) {
            var t = arena.allocate(TermiosLinux.SIZE);
            t.set(ValueLayout.JAVA_INT, 0, TermiosLinux.IXON);
            t.set(ValueLayout.JAVA_INT, 12, TermiosLinux.ECHO);
            TermiosLinux.apply(t, InputMode.COOKED);
            assertThat(TermiosLinux.iflag(t) & TermiosLinux.IXON).isEqualTo(TermiosLinux.IXON);
            assertThat(TermiosLinux.lflag(t) & TermiosLinux.ECHO).isEqualTo(TermiosLinux.ECHO);
        }
    }
}
