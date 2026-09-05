// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import org.jspecify.annotations.Nullable;

/**
 * Visible width: CSI/OSC strip plus wcwidth. Control = -1, combining/VS16/ZWJ = 0, CJK/Wide = 2.
 */
public final class Width {
    private Width() {}

    public static int wcwidth(int codePoint) {
        if (codePoint == 0) {
            return 0;
        }
        if (codePoint < 32 || (codePoint >= 0x7F && codePoint < 0xA0)) {
            return -1;
        }
        if (codePoint == 0x200D || codePoint == 0xFE0F || codePoint == 0xFE0E) {
            return 0;
        }
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) {
            return 0;
        }
        if (isWide(codePoint)) {
            return 2;
        }
        return 1;
    }

    public static int skipEscape(String s, int i) {
        if (i + 1 >= s.length()) {
            return s.length();
        }
        char n = s.charAt(i + 1);
        if (n == '[') {
            int j = i + 2;
            while (j < s.length()) {
                char c = s.charAt(j++);
                if (c >= '@' && c <= '~') {
                    break;
                }
            }
            return j;
        }
        if (n == ']') {
            int j = i + 2;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '\u0007') {
                    return j + 1;
                }
                if (c == '\u001b') {
                    return (j + 1 < s.length() && s.charAt(j + 1) == '\\') ? j + 2 : j;
                }
                j++;
            }
            return s.length();
        }
        return i + 2;
    }

    public static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        if (s.indexOf('\u001b') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '\u001b') {
                i = skipEscape(s, i);
            } else {
                sb.append(s.charAt(i++));
            }
        }
        return sb.toString();
    }

    public static int columns(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        String plain = stripAnsi(s);
        int cols = 0;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            int w = wcwidth(cp);
            if (w > 0) {
                cols += w;
            }
            i += Character.charCount(cp);
        }
        return cols;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || cp == 0x2329
                || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }
}
