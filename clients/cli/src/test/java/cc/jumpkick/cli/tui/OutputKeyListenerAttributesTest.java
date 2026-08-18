// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.jline.terminal.Attributes;
import org.junit.jupiter.api.Test;

/**
 * Regresses the Mac Ctrl-O failure mode: with IEXTEN left on, the kernel treats Ctrl-O as VDISCARD
 * and never delivers 0x0F to {@link KeyReader}.
 */
class OutputKeyListenerAttributesTest {

    @Test
    void disables_iexten_so_ctrl_o_is_not_swallowed_as_vdiscard() {
        Attributes saved = new Attributes();
        saved.setLocalFlag(Attributes.LocalFlag.ICANON, true);
        saved.setLocalFlag(Attributes.LocalFlag.ECHO, true);
        saved.setLocalFlag(Attributes.LocalFlag.IEXTEN, true);
        saved.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        saved.setControlChar(Attributes.ControlChar.VMIN, 1);
        saved.setControlChar(Attributes.ControlChar.VTIME, 0);

        Attributes raw = JkManager.outputKeyListenerAttributes(saved);

        assertThat(raw.getLocalFlag(Attributes.LocalFlag.ICANON)).isFalse();
        assertThat(raw.getLocalFlag(Attributes.LocalFlag.ECHO)).isFalse();
        assertThat(raw.getLocalFlag(Attributes.LocalFlag.IEXTEN)).isFalse();
        // Ctrl-C must still raise SIGINT for GlobalCancel.
        assertThat(raw.getLocalFlag(Attributes.LocalFlag.ISIG)).isTrue();
        assertThat(raw.getControlChar(Attributes.ControlChar.VMIN)).isEqualTo(1);
        assertThat(raw.getControlChar(Attributes.ControlChar.VTIME)).isZero();
    }
}
