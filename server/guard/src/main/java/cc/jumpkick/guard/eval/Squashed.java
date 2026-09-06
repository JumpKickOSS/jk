// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

/**
 * A code projection with the whitespace between tokens dropped, so a call wrapped across two lines
 * cannot evade a pattern written on one — plus the map from every squashed offset back to the
 * original, so a match still names its line. Whitespace inside string literals is kept, and one
 * space survives between two word characters so tokens do not fuse ({@code int x} stays two).
 */
final class Squashed {

    final String text;
    private final int[] offsets;

    private Squashed(String text, int[] offsets) {
        this.text = text;
        this.offsets = offsets;
    }

    static Squashed of(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int[] map = new int[src.length() + 1];
        int n = src.length();
        int i = 0;
        boolean pendingSpace = false;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                String close = c == '"' && src.startsWith("\"\"\"", i) ? "\"\"\"" : String.valueOf(c);
                if (pendingSpace) {
                    pendingSpace = false;
                }
                int start = i;
                i += close.length();
                while (i < n) {
                    if (src.charAt(i) == '\\') {
                        i += 2;
                    } else if (src.startsWith(close, i)) {
                        i += close.length();
                        break;
                    } else {
                        i++;
                    }
                }
                int end = Math.min(i, n);
                for (int k = start; k < end; k++) {
                    map[out.length()] = k;
                    out.append(src.charAt(k));
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
                i++;
                continue;
            }
            if (pendingSpace) {
                // Keep one separator between two identifier characters; drop it elsewhere.
                if (out.length() > 0 && isWord(out.charAt(out.length() - 1)) && isWord(c)) {
                    map[out.length()] = i - 1;
                    out.append(' ');
                }
                pendingSpace = false;
            }
            map[out.length()] = i;
            out.append(c);
            i++;
        }
        map[out.length()] = n;
        int[] trimmed = new int[out.length() + 1];
        System.arraycopy(map, 0, trimmed, 0, trimmed.length);
        return new Squashed(out.toString(), trimmed);
    }

    /** Original offset of squashed offset {@code i}. */
    int originalOffset(int i) {
        return offsets[Math.min(i, offsets.length - 1)];
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
