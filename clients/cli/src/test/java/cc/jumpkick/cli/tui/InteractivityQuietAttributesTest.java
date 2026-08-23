// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.terminal.Terminals;
import org.junit.jupiter.api.Test;

/**
 * canPrompt no longer mutates tty attributes. inheritIO restore is a no-op until a session exists.
 */
class InteractivityQuietAttributesTest {

    @Test
    void restore_for_child_is_a_noop_without_a_session() {
        assertThatCode(Terminals::restoreForChild).doesNotThrowAnyException();
    }
}
