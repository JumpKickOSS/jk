// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemoryTerminalTest {
    @Test
    void enterPopsToCooked() {
        try (MemoryTerminal tty =
                Terminals.memory(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            assertThat(tty.mode()).isEqualTo(InputMode.COOKED);
            try (ModeGuard g = tty.enter(InputMode.PROMPT)) {
                assertThat(tty.mode()).isEqualTo(InputMode.PROMPT);
                try (ModeGuard inner = tty.enter(InputMode.PLAN_KEYS)) {
                    assertThat(tty.mode()).isEqualTo(InputMode.PLAN_KEYS);
                }
                assertThat(tty.mode()).isEqualTo(InputMode.PROMPT);
            }
            assertThat(tty.mode()).isEqualTo(InputMode.COOKED);
            assertThat(tty.isLive()).isTrue();
        }
    }

    @Test
    void closeSetsNotLive() {
        MemoryTerminal tty = Terminals.memory(new ByteArrayInputStream(new byte[] {'a'}), new ByteArrayOutputStream());
        tty.close();
        assertThat(tty.isLive()).isFalse();
        assertThat(tty.readKey(Duration.ofMillis(5))).isEmpty();
    }

    @Test
    void readKeyMapsBytes() {
        try (MemoryTerminal tty =
                Terminals.memory(new ByteArrayInputStream(new byte[] {0x03, 'x'}), new ByteArrayOutputStream())) {
            assertThat(tty.readKey(Duration.ofSeconds(1))).contains(Key.CtrlC.INSTANCE);
            assertThat(tty.readKey(Duration.ofSeconds(1))).contains(new Key.Char('x'));
        }
    }

    @Test
    void readKeyTimesOutWhenEmpty() {
        try (MemoryTerminal tty =
                Terminals.memory(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            long t0 = System.nanoTime();
            assertThat(tty.readKey(Duration.ofMillis(40))).isEmpty();
            assertThat(tty.isLive()).isTrue();
            // A LOWER bound on elapsed time, which is the safe direction: load can only make a
            // wait longer, so this cannot flip on a busy machine the way an upper bound can. It
            // says readKey honoured its 40ms timeout instead of returning immediately.
            assertThat(System.nanoTime() - t0)
                    .as("readKey waited out its timeout rather than returning at once")
                    .isGreaterThanOrEqualTo(20_000_000L);
        }
    }

    @Test
    void ttyOutWritesUtf8() {
        try (MemoryTerminal tty =
                Terminals.memory(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            tty.ttyOut().print("hi");
            tty.ttyOut().flush();
            assertThat(new String(tty.written())).contains("hi");
        }
    }
}
