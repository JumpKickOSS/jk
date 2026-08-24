// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import cc.jumpkick.terminal.posix.PosixTty;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NativeTerminalSessionTest {
    @Test
    void controllingNeverUsesFdZero() {
        PosixTty tty = PosixTty.openControlling();
        if (tty == null) {
            // Windows binds CONIN$, not /dev/tty — a null POSIX open is not "no TTY".
            if (!Os.isWindows()) {
                assertThat(Terminals.controlling().isLive()).isFalse();
            }
            return;
        }
        try {
            assertThat(tty.fd()).isGreaterThan(0);
        } finally {
            tty.close();
        }
    }

    @Test
    void planKeysReadKeyHonorsTimeout() {
        TerminalSession s = Terminals.controlling();
        if (!s.isLive()) {
            return;
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (ModeGuard ignored = s.enter(InputMode.PLAN_KEYS)) {
                s.readKey(Duration.ofMillis(80));
            }
        });
    }

    @Test
    void singletonCloseKeepsLiveWhenTtyExists() {
        TerminalSession s = Terminals.controlling();
        if (!s.isLive()) {
            return;
        }
        s.close();
        assertThat(s.isLive()).isTrue();
        Terminals.shutdown();
        assertThat(s.isLive()).isFalse();
    }

    @Test
    void deadReadIsEmpty() {
        assertThat(DeadTerminal.INSTANCE.readKey(Duration.ofMillis(1))).isEmpty();
        assertThat(DeadTerminal.INSTANCE.isLive()).isFalse();
    }
}
