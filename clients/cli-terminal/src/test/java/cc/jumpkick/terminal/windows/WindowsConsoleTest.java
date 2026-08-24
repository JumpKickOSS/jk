// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.windows;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.InputMode;
import org.junit.jupiter.api.Test;

class WindowsConsoleTest {
    private static final int TYPICAL_COOKED = WindowsConsole.ENABLE_PROCESSED_INPUT
            | WindowsConsole.ENABLE_LINE_INPUT
            | WindowsConsole.ENABLE_ECHO_INPUT
            | WindowsConsole.ENABLE_MOUSE_INPUT
            | WindowsConsole.ENABLE_WINDOW_INPUT
            | WindowsConsole.ENABLE_QUICK_EDIT_MODE
            | WindowsConsole.ENABLE_EXTENDED_FLAGS;

    @Test
    void planKeysDropsLineEchoMouseAndQuickEditKeepsProcessed() {
        int bits = WindowsConsole.inputModeBits(TYPICAL_COOKED, InputMode.PLAN_KEYS);
        assertThat(bits & WindowsConsole.ENABLE_LINE_INPUT).isZero();
        assertThat(bits & WindowsConsole.ENABLE_ECHO_INPUT).isZero();
        assertThat(bits & WindowsConsole.ENABLE_MOUSE_INPUT).isZero();
        assertThat(bits & WindowsConsole.ENABLE_WINDOW_INPUT).isZero();
        assertThat(bits & WindowsConsole.ENABLE_QUICK_EDIT_MODE).isZero();
        assertThat(bits & WindowsConsole.ENABLE_PROCESSED_INPUT).isEqualTo(WindowsConsole.ENABLE_PROCESSED_INPUT);
        assertThat(bits & WindowsConsole.ENABLE_EXTENDED_FLAGS).isEqualTo(WindowsConsole.ENABLE_EXTENDED_FLAGS);
        assertThat(bits & WindowsConsole.ENABLE_VIRTUAL_TERMINAL_INPUT).isZero();
    }

    @Test
    void promptClearsProcessedInput() {
        int bits = WindowsConsole.inputModeBits(TYPICAL_COOKED, InputMode.PROMPT);
        assertThat(bits & WindowsConsole.ENABLE_LINE_INPUT).isZero();
        assertThat(bits & WindowsConsole.ENABLE_PROCESSED_INPUT).isZero();
        assertThat(bits & WindowsConsole.ENABLE_ECHO_INPUT).isZero();
    }

    @Test
    void cookedLeavesSavedBits() {
        assertThat(WindowsConsole.inputModeBits(TYPICAL_COOKED, InputMode.COOKED))
                .isEqualTo(TYPICAL_COOKED);
        assertThat(WindowsConsole.inputModeBits(TYPICAL_COOKED, InputMode.INHERIT_CHILD))
                .isEqualTo(TYPICAL_COOKED);
    }

    @Test
    void keyDownLetterBecomesUtf8Byte() {
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, 'a', 0x41))
                .containsExactly((byte) 'a');
    }

    @Test
    void ctrlOAndEnter() {
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, (char) 0x0F, 0))
                .containsExactly((byte) 0x0F);
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, (char) 0x0D, 0x0D))
                .containsExactly((byte) 0x0D);
    }

    @Test
    void arrowsBecomeCsiWhenUnicodeIsZero() {
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, (char) 0, WindowsConsole.VK_UP))
                .containsExactly((byte) 0x1B, (byte) '[', (byte) 'A');
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, (char) 0, WindowsConsole.VK_DOWN))
                .containsExactly((byte) 0x1B, (byte) '[', (byte) 'B');
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, (char) 0, WindowsConsole.VK_RIGHT))
                .containsExactly((byte) 0x1B, (byte) '[', (byte) 'C');
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, (char) 0, WindowsConsole.VK_LEFT))
                .containsExactly((byte) 0x1B, (byte) '[', (byte) 'D');
    }

    @Test
    void keyUpAndModifiersAreDiscarded() {
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 0, 'a', 0x41))
                .isEmpty();
        assertThat(WindowsConsole.bytesForKeyEvent(WindowsConsole.KEY_EVENT, 1, (char) 0, 0x10))
                .isEmpty();
        assertThat(WindowsConsole.bytesForKeyEvent(0x2, 1, (char) 0, 0)).isEmpty();
    }
}
