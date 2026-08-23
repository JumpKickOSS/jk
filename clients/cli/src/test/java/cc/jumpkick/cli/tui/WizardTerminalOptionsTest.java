// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.terminal.Terminals;
import org.junit.jupiter.api.Test;

/** Encoding is {@code WindowsUtf8}'s job; bootstrap is idempotent off Windows. */
class WizardTerminalOptionsTest {

    @Test
    void bootstrap_is_safe_on_this_host() {
        assertThatCode(Terminals::bootstrap).doesNotThrowAnyException();
    }
}
