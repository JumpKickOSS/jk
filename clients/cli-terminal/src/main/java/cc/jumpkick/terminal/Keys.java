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
                if (c >= 0xC2 && c <= 0xDF) {
                    int b1 = feed.read(CSI_PEEK);
                    if ((b1 & 0xC0) == 0x80) {
                        int cp = ((c & 0x1F) << 6) | (b1 & 0x3F);
                        yield new Key.Char((char) cp);
                    }
                    if (b1 >= 0) feed.unread(b1);
                } else if (c >= 0xE0 && c <= 0xEF) {
                    int b1 = feed.read(CSI_PEEK);
                    if ((b1 & 0xC0) == 0x80) {
                        int b2 = feed.read(CSI_PEEK);
                        if ((b2 & 0xC0) == 0x80) {
                            int cp = ((c & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                            if (cp >= 0x800 && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                                yield new Key.Char((char) cp);
                            }
                        } else if (b2 >= 0) {
                            feed.unread(b2);
                        }
                    } else if (b1 >= 0) {
                        feed.unread(b1);
                    }
                }
                yield new Key.Unknown(c);
            }
        };
    }

    /**
     * After ESC: a CSI sequence ({@code ESC [}, parameters, intermediates, one final byte, as ECMA-48
     * lays it out) or an SS3 one ({@code ESC O} and one final byte, the arrows of application cursor
     * mode) is one key; otherwise ESC is Escape and the byte after it is the next key. An arrow
     * with modifiers ({@code ESC [ 1 ; 2 A}) is the arrow.
     */
    private static Key parseEscape(ByteFeed feed) {
        int peek = feed.read(ESC_PEEK);
        if (peek < 0) {
            return Key.Escape.INSTANCE;
        }
        if (peek == 'O') {
            int fin = feed.read(ESC_PEEK);
            if (isFinal(fin)) {
                return finalKey(fin);
            }
            if (fin >= 0) {
                // Only one byte can go back: keep the one that would otherwise be lost.
                feed.unread(fin);
                return new Key.Char('O');
            }
            feed.unread(peek);
            return Key.Escape.INSTANCE;
        }
        if (peek != '[') {
            feed.unread(peek);
            return Key.Escape.INSTANCE;
        }
        int b = feed.read(ESC_PEEK);
        if (b < 0) {
            return Key.Escape.INSTANCE;
        }
        for (int n = 0; n < MAX_CSI_BYTES && b >= 0; n++) {
            if (isFinal(b)) {
                return finalKey(b);
            }
            if (b < 0x20 || b > 0x3F) {
                // Not a parameter or intermediate byte: the sequence was cut short.
                feed.unread(b);
                return new Key.Unknown('[');
            }
            b = feed.read(CSI_PEEK);
        }
        return new Key.Unknown('[');
    }

    /** Parameter and intermediate bytes read before a CSI sequence is given up as malformed. */
    private static final int MAX_CSI_BYTES = 32;

    private static boolean isFinal(int b) {
        return b >= 0x40 && b <= 0x7E;
    }

    private static Key finalKey(int b) {
        return switch (b) {
            case 'A' -> Key.Up.INSTANCE;
            case 'B' -> Key.Down.INSTANCE;
            case 'C' -> Key.Right.INSTANCE;
            case 'D' -> Key.Left.INSTANCE;
            default -> new Key.Unknown(b);
        };
    }
}
