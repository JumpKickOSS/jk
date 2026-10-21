// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.host.CodeText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A C-family source as the scanners read it: {@link CodeText}'s code-only projection (comments and
 * literals blanked, offsets kept), the comment lines it removed, and brace structure over the code.
 */
final class SourceMask {

    /** One line of comment text, delimiters included, and the offset it starts at. */
    record Comment(String text, int offset) {}

    final String masked;
    final List<Comment> comments;

    private SourceMask(String masked, List<Comment> comments) {
        this.masked = masked;
        this.comments = comments;
    }

    /** {@code templateLiterals} for JavaScript and TypeScript, where backticks delimit strings. */
    static SourceMask of(String text, boolean templateLiterals) {
        String code = CodeText.blank(text, CodeText.Blank.COMMENTS_AND_STRINGS, templateLiterals);
        String commentsOnly = CodeText.blank(text, CodeText.Blank.CODE, templateLiterals);
        List<Comment> comments = new ArrayList<>();
        int lineStart = 0;
        for (String line : commentsOnly.split("\n", -1)) {
            int first = 0;
            while (first < line.length() && Character.isWhitespace(line.charAt(first))) first++;
            if (first < line.length()) comments.add(new Comment(line.strip(), lineStart + first));
            lineStart += line.length() + 1;
        }
        return new SourceMask(code, comments);
    }

    /** The comment's text without {@code //}, {@code /*}, {@code *} or {@code *}{@code /} markers. */
    static String body(Comment c) {
        String s = c.text();
        if (s.startsWith("//")) s = s.substring(2);
        else if (s.startsWith("/*")) s = s.substring(2);
        if (s.endsWith("*/")) s = s.substring(0, s.length() - 2);
        s = s.strip();
        while (s.startsWith("*")) s = s.substring(1).strip();
        return s;
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

    /** The brace depth before each offset of the masked text. */
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

    /** The quoted literal starting at the first non-blank offset from {@code from} in {@code source}, or {@code null}. */
    static @Nullable String quotedAt(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
        if (i >= source.length()) return null;
        char q = source.charAt(i);
        if (q != '"' && q != '\'' && q != '`') return null;
        int end = source.indexOf(q, i + 1);
        return end < 0 ? null : source.substring(i + 1, end);
    }
}
