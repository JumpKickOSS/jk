// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The cause chain of a failed test in {@code jk-results.md}: the innermost {@code Caused by:} the
 * stack clip dropped, and a {@code →} line naming the resource the test could not load.
 *
 * <p>A framework failure ({@code Failed to load ApplicationContext}, a Spring Batch reader that
 * could not open) is thrown many causes above the one that says what went wrong, and the
 * {@code Caused by:} sections come after the whole top-level trace — past the line cut. The root
 * cause is written under the fence when the clipped text does not carry it; when any cause names a
 * resource that does not exist, the hint names the file.
 */
@NullMarked
final class JkResultsCause {

    private static final String CAUSED_BY = "Caused by: ";

    /** Spring's resource descriptions: {@code class path resource [x]}, {@code file [x]}, {@code URL [x]}. */
    private static final String RESOURCE = "(?:class path resource|file|URL|ServletContext resource) \\[([^\\]]+)\\]";

    private static final List<Pattern> MISSING = List.of(
            Pattern.compile(RESOURCE + " cannot be (?:opened|resolved)"),
            Pattern.compile("Input resource must exist.*?" + RESOURCE),
            Pattern.compile("Cannot read SQL script from " + RESOURCE),
            Pattern.compile("java\\.io\\.FileNotFoundException: (.+?) \\((?:No such file or directory|"
                    + "The system cannot find the (?:file|path) specified)\\)"),
            Pattern.compile("java\\.nio\\.file\\.NoSuchFileException: (\\S+)"));

    private static final Pattern SCRIPT_STATEMENT =
            Pattern.compile("Failed to execute SQL script statement #(\\d+) of " + RESOURCE);

    private JkResultsCause() {}

    /**
     * Write {@code root cause: …} when {@code clipped} lost the innermost cause of {@code stack},
     * then the hint the chain earns, each on its own line under the fence.
     */
    static void append(StringBuilder sb, String stack, String clipped) {
        String root = rootCause(stack);
        if (root != null && !clipped.contains(root)) {
            sb.append("root cause: ")
                    .append(JkResultsMarkdown.clipOneLine(root, JkResultsMarkdown.MAX_WARNING_LINE))
                    .append('\n');
        }
        String hint = hint(stack);
        if (hint != null) sb.append("→ ").append(hint).append('\n');
    }

    /** The innermost {@code Caused by:} line without its prefix, or {@code null} when the trace has none. */
    static @Nullable String rootCause(String stack) {
        List<String> headers = headers(stack);
        return headers.size() < 2 ? null : headers.get(headers.size() - 1);
    }

    /** The trace's first line and every {@code Caused by:} line (prefix stripped), outermost first. */
    static List<String> headers(String stack) {
        List<String> out = new ArrayList<>();
        String[] lines = stack.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (i == 0) {
                if (!line.isEmpty()) out.add(line);
            } else if (line.startsWith(CAUSED_BY)) {
                out.add(line.substring(CAUSED_BY.length()));
            }
        }
        return out;
    }

    /** The resource a cause says does not exist, or {@code null}. */
    static @Nullable String missingResource(String stack) {
        for (String header : headers(stack)) {
            for (Pattern p : MISSING) {
                Matcher m = p.matcher(header);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    /** The {@code →} line for a chain that names a missing resource or a failed script statement. */
    static @Nullable String hint(String stack) {
        String missing = missingResource(stack);
        if (missing != null) {
            if (isAbsolute(missing)) {
                return "the test opens `" + missing + "` and the file does not exist: create it, or fix the path.";
            }
            return "the test loads `" + missing + "` and nothing on its classpath ships it: add the file under"
                    + " `src/test/resources/` (or `src/main/resources/`), or fix the path it loads.";
        }
        for (String header : headers(stack)) {
            Matcher m = SCRIPT_STATEMENT.matcher(header);
            if (m.find()) {
                return "statement #" + m.group(1) + " of `" + m.group(2)
                        + "` failed — the script the test runs: fix the SQL, or the schema it assumes.";
            }
        }
        return null;
    }

    private static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.matches("[A-Za-z]:[\\\\/].*");
    }
}
