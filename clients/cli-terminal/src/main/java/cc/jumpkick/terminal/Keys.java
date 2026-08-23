// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

/**
 * Single-byte key map. CSI / ESC-peek parse lands in JK-2374; this is enough for session tests
 * and for feeding UTF-8 bytes from {@code poll}/{@code ReadFile}.
 */
final class Keys {
    private Keys() {}

    static Key mapByte(int b) {
        return switch (b) {
            case 0x03 -> Key.CtrlC.INSTANCE;
            case 0x0F -> Key.CtrlO.INSTANCE;
            case 0x18 -> Key.CtrlX.INSTANCE;
            case '\r', '\n' -> Key.Enter.INSTANCE;
            case ' ' -> Key.Space.INSTANCE;
            case '\t' -> Key.Tab.INSTANCE;
            case 0x7F, 0x08 -> Key.Backspace.INSTANCE;
            case 0x1B -> Key.Escape.INSTANCE;
            default -> {
                if (b >= 0x20 && b <= 0x7E) {
                    yield new Key.Char((char) b);
                }
                yield new Key.Unknown(b);
            }
        };
    }
}
