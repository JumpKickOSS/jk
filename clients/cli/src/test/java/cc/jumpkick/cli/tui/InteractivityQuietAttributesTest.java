// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.jline.terminal.Attributes;
import org.junit.jupiter.api.Test;

/**
 * The canPrompt probe must hand the tty back cooked with only ECHO off — copying the
 * post-unblock attrs kept ICANON off/VMIN=0 for the rest of the process, which broke
 * stdin for probe-without-prompt paths and inheritIO children (JK-2164).
 */
class InteractivityQuietAttributesTest {

    @Test
    void quiet_attributes_keep_canonical_mode_and_vmin() {
        Attributes cooked = new Attributes();
        cooked.setLocalFlag(Attributes.LocalFlag.ICANON, true);
        cooked.setLocalFlag(Attributes.LocalFlag.ECHO, true);
        cooked.setControlChar(Attributes.ControlChar.VMIN, 1);
        cooked.setControlChar(Attributes.ControlChar.VTIME, 0);

        Attributes quiet = Interactivity.quietAttributes(cooked);

        assertThat(quiet.getLocalFlag(Attributes.LocalFlag.ECHO)).isFalse();
        assertThat(quiet.getLocalFlag(Attributes.LocalFlag.ICANON)).isTrue();
        assertThat(quiet.getControlChar(Attributes.ControlChar.VMIN)).isEqualTo(1);
        assertThat(quiet.getControlChar(Attributes.ControlChar.VTIME)).isZero();
        // The input is not mutated.
        assertThat(cooked.getLocalFlag(Attributes.LocalFlag.ECHO)).isTrue();
    }
}
