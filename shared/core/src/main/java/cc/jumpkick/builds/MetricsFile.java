// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The line grammar {@code metrics.toml} (one run) and {@code project-metrics.toml} (the ledger)
 * share. Scalar rows are {@code key = number}, keyed as {@code task.<step>.wall-ms}, {@code
 * module.<dir>.task.<step>.wall-ms}, …, under a plain {@code [section]} or none. Per-class test
 * walls are one table per package of a module, {@code [test-class."<dir>"."<pkg>"]}, whose rows
 * are {@code <SimpleName> = <ms>}; a class in the default package sits under {@code
 * [test-class."<dir>"]} by its bare name. The module directory and the package are each spelled
 * once as a header instead of once per class, which is most of what keeps a large project's ledger
 * small. The class tables close the file, since every row after a header belongs to it.
 */
public final class MetricsFile {

    /** The table family that holds a module's per-class test walls. */
    public static final String TEST_CLASS = "test-class";

    private static final Pattern SECTION = Pattern.compile("^\\[([a-zA-Z0-9._-]+)\\]\\s*$");
    private static final Pattern TEST_CLASS_HEADER =
            Pattern.compile("^\\[test-class\\.\"([^\"]+)\"(?:\\.\"([^\"]*)\")?\\]\\s*$");
    private static final Pattern KEY_EQ = Pattern.compile("^([a-zA-Z0-9._:/-]+)\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");

    private MetricsFile() {}

    /** A scalar row: {@code section} is {@code ""} above the first header. */
    public interface ScalarRow {
        void row(String section, String key, double value);
    }

    /** One class wall of the module the enclosing table names, the class by its qualified name. */
    public interface ClassWallRow {
        void row(String moduleDir, String fqcn, double millis);
    }

    /** The header opening the table of {@code moduleDir}'s default-package classes, {@code moduleDir} in its key spelling. */
    public static String testClassHeader(String moduleDir) {
        return "[" + TEST_CLASS + ".\"" + moduleDir + "\"]";
    }

    /** The header opening the table of {@code moduleDir}'s classes in {@code pkg}; the default package is {@code ""}. */
    public static String testClassHeader(String moduleDir, String pkg) {
        return pkg.isEmpty()
                ? testClassHeader(moduleDir)
                : "[" + TEST_CLASS + ".\"" + moduleDir + "\".\"" + pkg + "\"]";
    }

    /**
     * Append {@code moduleDir}'s class walls — qualified class name to its formatted value — as
     * one table per package, the classes in name order so each package is opened once, each row
     * the class's simple name. Both writers of the grammar go through here.
     */
    public static void appendClassWalls(StringBuilder sb, String moduleDir, Map<String, String> byClass) {
        String open = null;
        for (Map.Entry<String, String> e : new TreeMap<>(byClass).entrySet()) {
            String fqcn = e.getKey();
            int dot = fqcn.lastIndexOf('.');
            String pkg = dot > 0 ? fqcn.substring(0, dot) : "";
            String simple = dot > 0 ? fqcn.substring(dot + 1) : fqcn;
            if (simple.isEmpty()) continue;
            if (!pkg.equals(open)) {
                sb.append('\n').append(testClassHeader(moduleDir, pkg)).append('\n');
                open = pkg;
            }
            sb.append(simple).append(" = ").append(e.getValue()).append('\n');
        }
    }

    /** Walk {@code text}, handing every decodable row to the sink of its kind. */
    public static void scan(String text, ScalarRow scalars, ClassWallRow classWalls) {
        String section = "";
        String classModule = null;
        String classPackage = "";
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("[")) {
                Matcher header = TEST_CLASS_HEADER.matcher(t);
                if (header.matches()) {
                    classModule = header.group(1);
                    classPackage = header.group(2) == null ? "" : header.group(2);
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
            if (classModule != null) {
                String fqcn = classPackage.isEmpty() ? km.group(1) : classPackage + "." + km.group(1);
                classWalls.row(classModule, fqcn, v);
            } else {
                scalars.row(section, km.group(1), v);
            }
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
