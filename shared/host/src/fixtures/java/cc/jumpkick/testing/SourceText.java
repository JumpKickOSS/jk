// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading Java source as <em>code</em>, for the tests that enforce a house rule by scanning it.
 *
 * <p>Every such test needs the same two things and gets them wrong the same two ways. A banned
 * token named in a javadoc line is documentation, not a defect, so comments have to go first — and
 * blanking them <em>in place</em>, preserving length and newlines, is what keeps a reported line
 * number meaning something. Second, a scan that walks a source tree by hand picks its own root, and
 * a root picked from the working directory differs between {@code ./gradlew test} and {@code jk
 * test} (which is what {@link RepoRoot} exists for).
 *
 * <p>The Gradle guards these tests replaced each carried a private copy of the blanker — four of
 * them, differing in whether they kept string bodies and whether they preserved offsets. One
 * lexer, with the string question as a parameter, is the same fact stated once.
 */
public final class SourceText {

    private SourceText() {}

    /** Every {@code *.java} file under {@code root}, sorted. An absent directory contributes none. */
    public static List<Path> javaUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var walk = Files.walk(root)) {
            List<Path> out =
                    new ArrayList<>(walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                            .filter(Files::isRegularFile)
                            .toList());
            out.sort(Path::compareTo);
            return List.copyOf(out);
        }
    }

    /** {@code path} relative to {@code root}, with forward slashes on every platform. */
    public static String rel(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * {@code src} with comments blanked to spaces — newlines, length and therefore every offset and
     * line number preserved. String and char literals are kept verbatim, because a scan hunting a
     * literal needs to see it.
     */
    public static String withoutComments(String src) {
        return blank(src, /* blankLiterals */ false);
    }

    /**
     * As {@link #withoutComments}, and string / char / text-block <em>bodies</em> blanked too.
     *
     * <p>For a scan whose target could appear inside a fixture string: an exit code in a text block
     * is some other program's exit code, and a class name inside a JSONL fixture is data.
     */
    public static String codeOnly(String src) {
        return blank(src, /* blankLiterals */ true);
    }

    private static String blank(String src, boolean blankLiterals) {
        int n = src.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        boolean line = false;
        boolean block = false;
        boolean text = false;
        boolean str = false;
        boolean chr = false;
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : ' ';
            boolean triple = c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"';
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                i++;
            } else if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    out.append("  ");
                    i += 2;
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (text) {
                if (triple) {
                    text = false;
                    append(out, src, i, 3, blankLiterals);
                    i += 3;
                } else {
                    out.append(c == '\n' ? '\n' : literal(c, blankLiterals));
                    i++;
                }
            } else if (str || chr) {
                if (c == '\\') {
                    append(out, src, i, Math.min(2, n - i), blankLiterals);
                    i += 2;
                } else {
                    if (str && c == '"') str = false;
                    if (chr && c == '\'') chr = false;
                    out.append(literal(c, blankLiterals));
                    i++;
                }
            } else if (c == '/' && next == '/') {
                line = true;
                out.append("  ");
                i += 2;
            } else if (c == '/' && next == '*') {
                block = true;
                out.append("  ");
                i += 2;
            } else if (triple) {
                text = true;
                append(out, src, i, 3, blankLiterals);
                i += 3;
            } else if (c == '"' || c == '\'') {
                if (c == '"') str = true;
                else chr = true;
                out.append(literal(c, blankLiterals));
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static char literal(char c, boolean blankLiterals) {
        return blankLiterals ? ' ' : c;
    }

    private static void append(StringBuilder out, String src, int from, int len, boolean blankLiterals) {
        if (blankLiterals) {
            out.append(" ".repeat(len));
        } else {
            out.append(src, from, from + len);
        }
    }
}
