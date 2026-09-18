// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.diagnostic;

import java.net.URI;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * File / line / column parsed from a javac, kotlinc, groovyc or scalac diagnostic block. The header is
 * {@code path:line[:col]:…}, or kotlinc's {@code file:///path:line:col message} with a space
 * after the column; when the header has no column, a following caret line supplies a 1-based
 * column. A {@code file:} URI in the header is reported as its filesystem path. Used when
 * journaling so agents do not scrape the blob.
 */
public record CompilerLocus(String file, int line, int col) {

    /**
     * {@code <path ending in a source ext>:<line>[:<col>]:<rest>}, or with a space in place of the
     * colon after the column ({@code file:///w/Foo.kt:3:5 Unresolved reference 'x'.}, the shape
     * kotlinc's Build Tools logger writes). The single definition — CLI rendering ({@code
     * CompilerDiagnostic}) matches against it too. The optional space after the first colon is
     * groovyc's shape ({@code /w/Foo.groovy: 5: unexpected token …}). The {@code file} group is
     * the header's own text; {@link #fileName} turns a {@code file:} URI into a path.
     */
    public static final Pattern HEADER = Pattern.compile(
            "^(?<file>.+?\\.(?:java|kt|kts|groovy|gvy|gy|scala|sc)): ?(?<line>\\d+)(?::(?<col>\\d+))?[: ](?<rest>.*)$");

    /** groovyc's column trailer: {@code … @ line 5, column 1.} (header carries no inline col). */
    public static final Pattern GROOVY_TRAILER = Pattern.compile("@ line \\d+, column (?<col>\\d+)\\.?\\s*$");

    /** A caret line: optional indent, a single {@code ^}, optional trailing space. */
    public static final Pattern CARET = Pattern.compile("^(\\s*)\\^\\s*$");

    /**
     * First header in {@code raw} (whole block or first line), with caret column when the header
     * omitted it. Null when no compiler header is present.
     */
    public static @Nullable CompilerLocus parse(@Nullable String raw) {
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
                if (col <= 0) {
                    Matcher tr = GROOVY_TRAILER.matcher(m.group("rest"));
                    if (tr.find()) col = parsePositive(tr.group("col"));
                }
                header = new CompilerLocus(fileName(m.group("file")), lineNo, col);
                continue;
            }
            if (CARET.matcher(line).matches()) {
                int at = line.indexOf('^');
                if (at >= 0) caretCol = at + 1;
                break;
            }
            // A later unit's caret belongs to its own header, never to this one.
            if (HEADER.matcher(line).matches()) break;
        }
        if (header == null) return null;
        if (header.col > 0 || caretCol <= 0) return header;
        return new CompilerLocus(header.file, header.line, caretCol);
    }

    /**
     * The filesystem path a header's {@code file} group names: a {@code file:} URI becomes its
     * path ({@code file:///w/Foo.kt} is {@code /w/Foo.kt}); any other text is returned as written.
     */
    public static String fileName(String header) {
        if (header == null || !header.startsWith("file:")) return header == null ? "" : header;
        try {
            return Path.of(URI.create(header)).toString();
        } catch (RuntimeException e) {
            String rest = header.substring("file:".length());
            return rest.startsWith("//") ? rest.substring(2) : rest;
        }
    }

    /** {@code raw} as a positive int; 0 for blank, negative, or unparseable input. */
    public static int parsePositive(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw.strip());
            return n > 0 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
