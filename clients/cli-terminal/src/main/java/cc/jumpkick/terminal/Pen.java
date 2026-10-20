// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The SGR state a cell is painted in: attribute flags plus the foreground and background as their
 * SGR parameters ({@code "38;2;r;g;b"}, {@code "31"}, {@code "38;5;n"}), {@code null} for the
 * terminal default. Two pens are equal exactly when they paint the same.
 */
record Pen(int attrs, @Nullable String fg, @Nullable String bg) {

    static final Pen DEFAULT = new Pen(0, null, null);

    /** SGR attribute numbers with an on/off pair, in emission order. Index = bit. */
    private static final int[] ON = {1, 2, 3, 4, 5, 7, 8, 9};

    /** SGR that clears each {@link #ON} attribute; bold and dim share 22. */
    private static final int[] OFF = {22, 22, 23, 24, 25, 27, 28, 29};

    /** This pen after the parameters of one SGR sequence ({@code "1;38;2;0;0;0"}, {@code ""} = reset). */
    Pen apply(String params) {
        if (params.isEmpty()) return DEFAULT;
        String[] p = params.split("[;:]", -1);
        int a = attrs;
        String f = fg;
        String b = bg;
        for (int i = 0; i < p.length; i++) {
            int n = number(p[i]);
            if (n == 0) {
                a = 0;
                f = null;
                b = null;
            } else if (n == 38 || n == 48) {
                int consumed = extendedColor(p, i);
                if (consumed == 0) break;
                String color = String.join(";", Arrays.copyOfRange(p, i, i + consumed));
                if (n == 38) f = color;
                else b = color;
                i += consumed - 1;
            } else if ((n >= 30 && n <= 37) || (n >= 90 && n <= 97)) {
                f = Integer.toString(n);
            } else if ((n >= 40 && n <= 47) || (n >= 100 && n <= 107)) {
                b = Integer.toString(n);
            } else if (n == 39) {
                f = null;
            } else if (n == 49) {
                b = null;
            } else {
                for (int bit = 0; bit < ON.length; bit++) {
                    if (n == ON[bit]) a |= 1 << bit;
                    else if (n == OFF[bit]) a &= ~(1 << bit);
                }
            }
        }
        return new Pen(a, f, b);
    }

    /**
     * The shortest SGR sequence that turns this pen into {@code next}; empty when they are equal.
     * Either only what changed (with the specific off codes for a dropped attribute or colour), or
     * a reset followed by everything {@code next} sets, whichever is fewer bytes.
     */
    String to(Pen next) {
        if (equals(next)) return "";
        String full = next.equals(DEFAULT) ? "" : "0;" + next.body();
        List<String> delta = new ArrayList<>();
        int dropped = attrs & ~next.attrs;
        int keep = 0;
        for (int bit = 0; bit < ON.length; bit++) {
            if ((dropped & (1 << bit)) == 0) continue;
            // 22 clears bold and dim together: the one that stays is set again below.
            if (OFF[bit] == 22) {
                if (!delta.contains("22")) delta.add("22");
                keep |= next.attrs & 0b11;
            } else {
                delta.add(Integer.toString(OFF[bit]));
            }
        }
        int added = (next.attrs & ~attrs) | keep;
        for (int bit = 0; bit < ON.length; bit++) {
            if ((added & (1 << bit)) != 0) delta.add(Integer.toString(ON[bit]));
        }
        if (!Objects.equals(fg, next.fg)) delta.add(next.fg == null ? "39" : next.fg);
        if (!Objects.equals(bg, next.bg)) delta.add(next.bg == null ? "49" : next.bg);
        String incremental = String.join(";", delta);
        return Ansi.CSI + (full.length() <= incremental.length() ? full : incremental) + "m";
    }

    /** Every parameter this pen sets, from the default: attributes, then fg, then bg. */
    String body() {
        List<String> parts = new ArrayList<>();
        for (int bit = 0; bit < ON.length; bit++) {
            if ((attrs & (1 << bit)) != 0) parts.add(Integer.toString(ON[bit]));
        }
        if (fg != null) parts.add(fg);
        if (bg != null) parts.add(bg);
        return String.join(";", parts);
    }

    /** Whether an erase (EL/ED) under this pen would fill with a colour: erases take the background. */
    boolean tintsErase() {
        return bg != null || (attrs & (1 << 5)) != 0;
    }

    /** Parameters an extended colour at {@code p[i]} spans ({@code 38;5;n} = 3, {@code 38;2;r;g;b} = 5); 0 if malformed. */
    private static int extendedColor(String[] p, int i) {
        if (i + 1 >= p.length) return 0;
        int kind = number(p[i + 1]);
        if (kind == 5 && i + 2 < p.length) return 3;
        if (kind == 2 && i + 4 < p.length) return 5;
        return 0;
    }

    private static int number(String s) {
        if (s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            n = n * 10 + (c - '0');
            if (n > 1_000_000) return -1;
        }
        return n;
    }
}
