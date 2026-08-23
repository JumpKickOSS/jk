// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.terminal.Terminals;
import org.junit.jupiter.api.Test;

/** {@link Interactivity} is a policy layer over {@link Terminals}; never close the singleton. */
class InteractivityTest {

    @Test
    void restore_for_child_is_safe_when_never_opened() {
        assertThatCode(Terminals::restoreForChild).doesNotThrowAnyException();
        assertThatCode(Interactivity::restoreForChildProcess).doesNotThrowAnyException();
    }

    @Test
    void stdout_is_tty_does_not_throw() {
        assertThatCode(Interactivity::stdoutIsTty).doesNotThrowAnyException();
    }
}
