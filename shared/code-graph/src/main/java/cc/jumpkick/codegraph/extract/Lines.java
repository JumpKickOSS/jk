// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offset-to-line lookup over one text. */
final class Lines {

    /** A dotted JVM binary name whose last segment is capitalized: {@code com.acme.Shop$Cart}. */
    static final Pattern FQCN =
            Pattern.compile("\\b(?:[a-z_][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(?:\\$[A-Za-z0-9_]+)*\\b");

    private final int[] starts;

    Lines(String text) {
        int[] s = new int[16];
        int n = 0;
        s[n++] = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (n == s.length) s = Arrays.copyOf(s, n * 2);
                s[n++] = i + 1;
            }
        }
        starts = Arrays.copyOf(s, n);
    }

    /** The 1-based line holding {@code offset}. */
    int at(int offset) {
        int i = Arrays.binarySearch(starts, offset);
        return (i >= 0 ? i : -i - 2) + 1;
    }

    /** The text's lines, without terminators. */
    static List<String> split(String text) {
        return text.lines().toList();
    }

    /** Every FQCN-shaped token in {@code text}, each with its line. */
    static void eachClassName(String text, Lines lines, ClassNameVisit visit) {
        Matcher m = FQCN.matcher(text);
        while (m.find()) visit.accept(m.group(), lines.at(m.start()));
    }

    @FunctionalInterface
    interface ClassNameVisit {
        void accept(String name, int line);
    }
}
