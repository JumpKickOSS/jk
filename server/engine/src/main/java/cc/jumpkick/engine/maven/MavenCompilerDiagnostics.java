// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.maven;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sites in a {@code maven-compiler-plugin} failure message. The plugin writes one line per
 * error as {@code path:[line,col] message}; indented lines that follow ({@code symbol:},
 * {@code location:}) belong to the site above them.
 */
public final class MavenCompilerDiagnostics {

    /** {@code /ws/A.java:[3,5] cannot find symbol}. */
    static final Pattern SITE = Pattern.compile(
            "^(?<file>\\S.*?\\.(?:java|kt|kts|groovy|scala)):\\[(?<line>\\d+),(?<col>\\d+)\\]\\s*(?<msg>.*)$");

    private MavenCompilerDiagnostics() {}

    /** One compiler error at a source position; {@code message} may span lines. */
    public record Site(String file, int line, int col, String message) {

        /** The site as a {@code file:line:col: message} header a compiler-locus parser reads. */
        public String asHeader() {
            return file + ":" + line + ":" + col + ": " + message;
        }
    }

    /** The distinct sites in {@code message}, in order; empty when it names none. */
    public static List<Site> parse(String message) {
        List<Site> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder current = null;
        String file = "";
        int line = 0, col = 0;
        for (String raw : message.split("\n", -1)) {
            Matcher m = SITE.matcher(raw.strip());
            if (m.matches()) {
                if (current != null) add(out, seen, file, line, col, current);
                file = m.group("file");
                line = Integer.parseInt(m.group("line"));
                col = Integer.parseInt(m.group("col"));
                current = new StringBuilder(m.group("msg").strip());
            } else if (current != null && !raw.isBlank() && Character.isWhitespace(raw.charAt(0))) {
                current.append('\n').append(raw.stripTrailing());
            } else if (current != null) {
                add(out, seen, file, line, col, current);
                current = null;
            }
        }
        if (current != null) add(out, seen, file, line, col, current);
        return out;
    }

    private static void add(List<Site> out, Set<String> seen, String file, int line, int col, StringBuilder msg) {
        Site site = new Site(file, line, col, msg.toString());
        if (seen.add(site.asHeader())) out.add(site);
    }
}
