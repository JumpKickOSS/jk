// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The line grammar {@code metrics.toml} (one run) and {@code project-metrics.toml} (the ledger)
 * share. Scalar rows are {@code key = number}, keyed as {@code task.<step>.wall-ms}, {@code
 * module.<dir>.task.<step>.wall-ms}, …, under a plain {@code [section]} or none. Per-class test
 * walls are one table per module, {@code [test-class."<dir>"]}, whose rows are {@code <fqcn> =
 * <ms>}: the module directory is spelled once as the header instead of once per class, which is
 * most of what keeps a large project's ledger small. The class tables close the file, since
 * every row after a header belongs to it.
 */
public final class MetricsFile {

    /** The table family that holds a module's per-class test walls. */
    public static final String TEST_CLASS = "test-class";

    private static final Pattern SECTION = Pattern.compile("^\\[([a-zA-Z0-9._-]+)\\]\\s*$");
    private static final Pattern TEST_CLASS_HEADER = Pattern.compile("^\\[test-class\\.\"([^\"]+)\"\\]\\s*$");
    private static final Pattern KEY_EQ = Pattern.compile("^([a-zA-Z0-9._:/-]+)\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");

    private MetricsFile() {}

    /** A scalar row: {@code section} is {@code ""} above the first header. */
    public interface ScalarRow {
        void row(String section, String key, double value);
    }

    /** One class wall of the module the enclosing table names, in its on-disk spelling. */
    public interface ClassWallRow {
        void row(String moduleDir, String fqcn, double millis);
    }

    /** The header opening {@code moduleDir}'s class-wall table, {@code moduleDir} in its key spelling. */
    public static String testClassHeader(String moduleDir) {
        return "[" + TEST_CLASS + ".\"" + moduleDir + "\"]";
    }

    /** Walk {@code text}, handing every decodable row to the sink of its kind. */
    public static void scan(String text, ScalarRow scalars, ClassWallRow classWalls) {
        String section = "";
        String classModule = null;
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("[")) {
                Matcher header = TEST_CLASS_HEADER.matcher(t);
                if (header.matches()) {
                    classModule = header.group(1);
                    continue;
                }
                classModule = null;
                Matcher plain = SECTION.matcher(t);
                section = plain.matches() ? plain.group(1) : "";
                continue;
            }
            Matcher km = KEY_EQ.matcher(t);
            if (!km.matches()) continue;
            Double v = parse(km.group(2));
            if (v == null || v.isNaN() || v.isInfinite()) continue;
            if (classModule != null) classWalls.row(classModule, km.group(1), v);
            else scalars.row(section, km.group(1), v);
        }
    }

    private static @Nullable Double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
