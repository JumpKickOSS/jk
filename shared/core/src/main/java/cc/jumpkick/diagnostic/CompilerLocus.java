// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.diagnostic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File / line / column parsed from a javac, kotlinc, or groovyc diagnostic block. The header is
 * {@code path:line[:col]:…}; when the header has no column, a following caret line supplies a
 * 1-based column. Used when journaling so agents do not scrape the blob.
 */
public record CompilerLocus(String file, int line, int col) {

    /** {@code <path ending in a source ext>:<line>[:<col>]:<rest>}. */
    private static final Pattern HEADER = Pattern.compile(
            "^(?<file>.+?\\.(?:java|kt|kts|groovy|gvy|gy)):(?<line>\\d+)(?::(?<col>\\d+))?:(?<rest>.*)$");

    /** A caret line: optional indent, a single {@code ^}, optional trailing space. */
    private static final Pattern CARET = Pattern.compile("^(\\s*)\\^\\s*$");

    /**
     * First header in {@code raw} (whole block or first line), with caret column when the header
     * omitted it. Null when no compiler header is present.
     */
    public static CompilerLocus parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] lines = raw.split("\n", -1);
        CompilerLocus header = null;
        int caretCol = 0;
        for (String line : lines) {
            if (header == null) {
                Matcher m = HEADER.matcher(line);
                if (!m.matches()) continue;
                int lineNo = parsePositive(m.group("line"));
                int col = parsePositive(m.group("col"));
                header = new CompilerLocus(m.group("file"), lineNo, col);
                continue;
            }
            if (CARET.matcher(line).matches()) {
                int at = line.indexOf('^');
                if (at >= 0) caretCol = at + 1;
                break;
            }
        }
        if (header == null) return null;
        if (header.col > 0 || caretCol <= 0) return header;
        return new CompilerLocus(header.file, header.line, caretCol);
    }

    private static int parsePositive(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw.strip());
            return n > 0 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
