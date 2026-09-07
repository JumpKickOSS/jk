// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * A C-family lexer for the JVM languages: line and block comments, string and char literals, text
 * blocks. Blanking replaces the chosen parts with spaces (newlines kept), so offsets and line
 * numbers survive.
 */
final class Blanker {

    private Blanker() {}

    /** Blank {@code comments} and/or {@code strings}; with {@code codeToo} everything else is blanked instead (the comments-only view). */
    static String blank(String src, boolean comments, boolean strings, boolean codeToo) {
        char[] out = src.toCharArray();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                if (end < 0) end = n;
                if (comments) space(out, i, end);
                else if (codeToo) {
                    /* comment stays */
                }
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                if (comments) space(out, i, end);
                i = end;
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                if (strings) space(out, i + 3, end - 3);
                else if (codeToo) space(out, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < n && src.charAt(end) != c && src.charAt(end) != '\n') {
                    if (src.charAt(end) == '\\') end++;
                    end++;
                }
                end = Math.min(n, end + 1);
                if (strings) space(out, i + 1, Math.max(i + 1, end - 1));
                else if (codeToo) space(out, i, end);
                i = end;
            } else {
                if (codeToo && c != '\n' && c != '\r') out[i] = ' ';
                i++;
            }
        }
        return new String(out);
    }

    /** String literals in order, unescaped only for the quotes. */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                if (end < 0) end = n;
                out.add(src.substring(i + 3, end).strip());
                i = Math.min(n, end + 3);
            } else if (c == '"') {
                int end = i + 1;
                StringBuilder sb = new StringBuilder();
                while (end < n && src.charAt(end) != '"' && src.charAt(end) != '\n') {
                    if (src.charAt(end) == '\\' && end + 1 < n) {
                        sb.append(src.charAt(end + 1) == 'n' ? '\n' : src.charAt(end + 1));
                        end += 2;
                        continue;
                    }
                    sb.append(src.charAt(end++));
                }
                out.add(sb.toString());
                i = Math.min(n, end + 1);
            } else if (c == '\'') {
                int end = i + 1;
                while (end < n && src.charAt(end) != '\'' && src.charAt(end) != '\n') {
                    if (src.charAt(end) == '\\') end++;
                    end++;
                }
                i = Math.min(n, end + 1);
            } else {
                i++;
            }
        }
        return out;
    }

    private static void space(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) if (out[k] != '\n' && out[k] != '\r') out[k] = ' ';
    }
}
