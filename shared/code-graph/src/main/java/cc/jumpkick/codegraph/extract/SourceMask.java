// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A C-family source with comments and string literals blanked to spaces (newlines kept, so offsets
 * and lines still line up), plus the comments it removed. Brace depth and declaration patterns are
 * then read off the masked text without a string or comment fooling them. Handles {@code //},
 * {@code /* *\/}, {@code "…"}, {@code '…'}, triple-quoted text blocks and JS template literals.
 */
final class SourceMask {

    /** A comment's text without its delimiters, and the offset it starts at. */
    record Comment(String text, int offset) {}

    final String masked;
    final List<Comment> comments;

    private SourceMask(String masked, List<Comment> comments) {
        this.masked = masked;
        this.comments = comments;
    }

    static SourceMask of(String text) {
        char[] out = text.toCharArray();
        List<Comment> comments = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                if (end < 0) end = n;
                comments.add(new Comment(text.substring(i + 2, end), i));
                blank(out, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                comments.add(new Comment(text.substring(i + 2, Math.max(i + 2, end - 2)), i));
                blank(out, i, end);
                i = end;
            } else if (text.startsWith("\"\"\"", i) || text.startsWith("'''", i)) {
                String q = text.substring(i, i + 3);
                int end = text.indexOf(q, i + 3);
                end = end < 0 ? n : end + 3;
                blank(out, i + 1, end - 1);
                i = end;
            } else if (c == '"' || c == '\'' || c == '`') {
                int end = closeQuote(text, i, c);
                blank(out, i + 1, end - 1);
                i = end;
            } else {
                i++;
            }
        }
        return new SourceMask(new String(out), comments);
    }

    /** The offset just past the quote closing the literal opened at {@code start}. */
    private static int closeQuote(String text, int start, char quote) {
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n' && quote != '`') {
                return i;
            }
        }
        return text.length();
    }

    private static void blank(char[] out, int from, int to) {
        for (int i = Math.max(from, 0); i < Math.min(to, out.length); i++) {
            if (out[i] != '\n') out[i] = ' ';
        }
    }

    /** For each offset holding {@code '{'}, the offset of its matching {@code '}'} (the text's length when unclosed). */
    int[] closers() {
        int[] close = new int[masked.length()];
        int[] stack = new int[64];
        int top = 0;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') {
                if (top == stack.length) stack = Arrays.copyOf(stack, top * 2);
                stack[top++] = i;
                close[i] = masked.length();
            } else if (c == '}' && top > 0) {
                close[stack[--top]] = i;
            }
        }
        return close;
    }

    /** The brace depth at each offset of the masked text: depth before the character there. */
    int[] depths() {
        int[] d = new int[masked.length() + 1];
        int depth = 0;
        for (int i = 0; i < masked.length(); i++) {
            d[i] = depth;
            char c = masked.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
        }
        d[masked.length()] = depth;
        return d;
    }
}
