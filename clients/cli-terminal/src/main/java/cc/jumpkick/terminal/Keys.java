// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.time.Duration;

/**
 * Package-private parse. ESC 50ms peek and CSI 1ms trailing drain use the session wait loop.
 */
final class Keys {
    private static final Duration ESC_PEEK = Duration.ofMillis(50);
    private static final Duration CSI_PEEK = Duration.ofMillis(1);

    private Keys() {}

    interface ByteFeed {
        /** {@code -1} timeout/empty, {@code -2} dead. */
        int read(Duration timeout);

        void unread(int b);
    }

    static Key dispatch(int c, ByteFeed feed) {
        return switch (c) {
            case 0x03 -> Key.CtrlC.INSTANCE;
            case 0x0F -> Key.CtrlO.INSTANCE;
            case 0x18 -> Key.CtrlX.INSTANCE;
            case 0x0A, 0x0D -> Key.Enter.INSTANCE;
            case 0x09 -> Key.Tab.INSTANCE;
            case 0x20 -> Key.Space.INSTANCE;
            case 0x7F, 0x08 -> Key.Backspace.INSTANCE;
            case 0x1B -> parseEscape(feed);
            default -> {
                if (c >= 0x21 && c <= 0x7E) {
                    yield new Key.Char((char) c);
                }
                yield new Key.Unknown(c);
            }
        };
    }

    private static Key parseEscape(ByteFeed feed) {
        int peek = feed.read(ESC_PEEK);
        if (peek < 0) {
            return Key.Escape.INSTANCE;
        }
        if (peek != '[') {
            feed.unread(peek);
            return Key.Escape.INSTANCE;
        }
        int code = feed.read(ESC_PEEK);
        if (code < 0) {
            return Key.Escape.INSTANCE;
        }
        Key key =
                switch (code) {
                    case 'A' -> Key.Up.INSTANCE;
                    case 'B' -> Key.Down.INSTANCE;
                    case 'C' -> Key.Right.INSTANCE;
                    case 'D' -> Key.Left.INSTANCE;
                    default -> new Key.Unknown(code);
                };
        drainTrailing(feed);
        return key;
    }

    private static void drainTrailing(ByteFeed feed) {
        while (true) {
            int p = feed.read(CSI_PEEK);
            if (p < 0) {
                return;
            }
            if ((p >= '0' && p <= '9') || p == ';' || p == '~') {
                continue;
            }
            feed.unread(p);
            return;
        }
    }
}
