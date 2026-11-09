// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown's {@code code} view: the prose a reader reads, with fenced blocks, code spans, HTML
 * comments and tags, link targets and bare URLs blanked to spaces. Newlines and offsets are kept, so
 * a hit reports the line it was written on. Indented code blocks are not recognised: a list item's
 * continuation is indented the same way.
 */
final class MarkdownProse {

    private static final Pattern FENCE = Pattern.compile("^[ \\t]*(`{3,}|~{3,})");
    private static final Pattern MARKUP = Pattern.compile(
            "<!--.*?-->" // an HTML comment, markers included
                    + "|<[^>\\n]+>" // a tag or an autolink
                    + "|\\]\\([^)\\s]*(?:\\s+\"[^\"\\n]*\")?\\)" // an inline link's target
                    + "|^[ \\t]{0,3}\\[[^\\]\\n]+\\]:[^\\n]*" // a reference definition
                    + "|https?://[^\\s)>\\]]+", // a bare URL
            Pattern.DOTALL | Pattern.MULTILINE);

    private MarkdownProse() {}

    static String blank(String text) {
        char[] out = text.toCharArray();
        blankFences(text, out);
        blankCodeSpans(out);
        Matcher m = MARKUP.matcher(new String(out));
        while (m.find()) blank(out, m.start(), m.end());
        return new String(out);
    }

    /** Three or more backticks or tildes open a fence and a run as long closes it; an open fence runs to the end. */
    private static void blankFences(String text, char[] out) {
        String fence = null;
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            String line = text.substring(start, end);
            Matcher m = FENCE.matcher(line);
            if (fence != null) {
                blank(out, start, end);
                if (m.find()
                        && m.group(1).charAt(0) == fence.charAt(0)
                        && m.group(1).length() >= fence.length()
                        && line.strip().equals(m.group(1))) fence = null;
            } else if (m.find()) {
                fence = m.group(1);
                blank(out, start, end);
            }
            start = end + 1;
        }
    }

    /** A run of n backticks opens a span that the next run of exactly n closes, within one paragraph. */
    private static void blankCodeSpans(char[] out) {
        int i = 0;
        while (i < out.length) {
            if (out[i] != '`') {
                i++;
                continue;
            }
            int run = runEnd(out, i);
            int n = run - i;
            int close = closing(out, run, n);
            if (close < 0) {
                i = run;
                continue;
            }
            blank(out, i, close + n);
            i = close + n;
        }
    }

    private static int closing(char[] out, int from, int n) {
        int k = from;
        while (k < out.length) {
            if (out[k] == '\n' && k + 1 < out.length && isBlankLine(out, k + 1)) return -1;
            if (out[k] != '`') {
                k++;
                continue;
            }
            int e = runEnd(out, k);
            if (e - k == n) return k;
            k = e;
        }
        return -1;
    }

    private static boolean isBlankLine(char[] out, int at) {
        for (int k = at; k < out.length && out[k] != '\n'; k++) if (!Character.isWhitespace(out[k])) return false;
        return true;
    }

    private static int runEnd(char[] out, int at) {
        int e = at;
        while (e < out.length && out[e] == '`') e++;
        return e;
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to; k++) if (out[k] != '\n') out[k] = ' ';
    }
}
