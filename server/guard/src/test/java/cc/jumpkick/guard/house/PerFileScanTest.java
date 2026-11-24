// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A house guard that reads every main source judges each file through {@code Text.perFile}, so a
 * body-only edit re-scans the edited files and the rest come back from the read ledger. The guard
 * source set is compiled by jk alone, so this reads the suite as text.
 */
class PerFileScanTest {

    private static final String OWNER = "server/guard/src/guard/java/cc/jumpkick/guard/house/HouseRules.java";

    /** What names the whole main tree in a guard body: the glob itself or the constants that carry it. */
    private static final Pattern WHOLE_TREE = Pattern.compile("src/main/java|\\bMAIN_JAVA\\b|\\bEVERY_JAVA_SET\\b");

    private static final Pattern GUARD_METHOD = Pattern.compile("@Guard\\(.*?\\bvoid\\s+(\\w+)\\s*\\(", Pattern.DOTALL);
    private static final Pattern METHOD = Pattern.compile("\\b(\\w+)\\s*\\([^()]*\\)\\s*\\{");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized");

    private static Map<String, String> houseSources() throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path dir = Objects.requireNonNull(
                RepoRoot.file(PerFileScanTest.class, OWNER).getParent(), "suite dir");
        PathUtil.forEachRegularFile(dir, (f, attrs) -> {
            if (f.getFileName().toString().endsWith(".java"))
                out.put(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
        });
        return out;
    }

    @Test
    void every_guard_that_reads_the_whole_main_tree_judges_it_per_file() throws IOException {
        List<String> wholeTree = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (var e : houseSources().entrySet()) {
            String raw = e.getValue();
            String code = blank(raw);
            Set<String> perFileHelpers = new HashSet<>();
            Matcher h = METHOD.matcher(code);
            while (h.find()) {
                if (KEYWORDS.contains(h.group(1))) continue;
                if (body(raw, code, h.end() - 1).contains("perFile(")) perFileHelpers.add(h.group(1));
            }
            Matcher g = GUARD_METHOD.matcher(code);
            while (g.find()) {
                String body = body(raw, code, code.indexOf('{', g.end()));
                if (!WHOLE_TREE.matcher(body).find()) continue;
                String guard = e.getKey() + ":" + g.group(1);
                wholeTree.add(guard);
                boolean perFile =
                        body.contains("perFile(") || perFileHelpers.stream().anyMatch(n -> body.contains(n + "("));
                if (!perFile) offenders.add(guard);
            }
        }
        assertThat(wholeTree)
                .as("the scan sees the guards that read every main source")
                .contains(
                        "HouseRules.java:oneJsonCodec",
                        "HouseRules.java:pluginFamily",
                        "WireKeyRules.java:wireKeyClosure");
        assertThat(offenders)
                .as("a guard over every main source reads each file through Text.perFile, so an edit"
                        + " re-scans only the files it touched")
                .isEmpty();
    }

    /** The raw text of the block whose {@code {} is at {@code open} in {@code code}, braces matched there. */
    private static String body(String raw, String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return raw.substring(open, i + 1);
        }
        return raw.substring(open);
    }

    /** {@code src} with its comments and the insides of its string and char literals blanked to spaces. */
    static String blank(String src) {
        StringBuilder out = new StringBuilder(src);
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                if (end < 0) end = src.length();
                fill(out, i, end);
                i = end;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? src.length() : end + 2;
                fill(out, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != c && src.charAt(j) != '\n')
                    j += src.charAt(j) == '\\' ? 2 : 1;
                fill(out, i + 1, Math.min(j, src.length()));
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static void fill(StringBuilder out, int from, int to) {
        for (int k = from; k < to; k++) if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
    }

    @Test
    void the_blanker_hides_braces_in_literals_and_comments() {
        String src = "void f() { s(\"{\"); c('}'); // }\n /* { */ }";
        String code = blank(src);
        assertThat(body(src, code, code.indexOf('{'))).isEqualTo(src.substring(src.indexOf('{')));
    }
}
