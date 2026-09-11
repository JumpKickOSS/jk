// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Class-name filters for {@link JUnitLauncher}: exact names ({@code --affected}) and {@code --class} patterns. */
final class JUnitClassFilter {

    private JUnitClassFilter() {}

    /**
     * One anchored regex over class names for {@code --class} patterns: a name with a dot is a
     * fully qualified class, a bare name matches that simple name in any package, and {@code *}
     * stands for any run of characters in either form.
     */
    static String patternRegex(List<String> patterns) {
        StringBuilder re = new StringBuilder("^(");
        boolean first = true;
        for (String raw : patterns) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            if (!first) re.append('|');
            first = false;
            if (!p.contains(".")) re.append("(.*\\.)?");
            re.append(glob(p));
        }
        return re.append(")$").toString();
    }

    private static String glob(String pattern) {
        StringBuilder out = new StringBuilder();
        String[] literals = pattern.split("\\*", -1);
        for (int i = 0; i < literals.length; i++) {
            if (i > 0) out.append(".*");
            if (!literals[i].isEmpty()) out.append(Pattern.quote(literals[i]));
        }
        return out.toString();
    }

    static List<String> singleWorkerArgs(Path testClassesDir, List<String> classNames) {
        List<String> args = new ArrayList<>();
        args.add("--scan-classpath=" + testClassesDir);
        if (classNames == null || classNames.isEmpty()) return args;
        StringBuilder re = new StringBuilder("^(");
        for (int i = 0; i < classNames.size(); i++) {
            if (i > 0) re.append('|');
            re.append(Pattern.quote(classNames.get(i)));
        }
        re.append(")$");
        args.add("--filter=" + re);
        return args;
    }

    /**
     * A {@code --class} that matched nothing is a failure naming the patterns, not a green "No
     * tests": the usual cause is a typo, and a typo that passes is the one outcome the flag must
     * never produce. A result with tests, or with failures of its own, is returned as is.
     */
    static TestSummary noMatchAsFailure(TestSummary result, String moduleLabel, List<String> patterns) {
        if (result.total() != 0 || result.failed() != 0) return result;
        String why = "no test classes matched --class " + String.join(", ", patterns);
        return new TestSummary(
                1, 0, 1, 0, List.of(new TestFailureInfo(moduleLabel, "", "", "(test run)", "", why, "")));
    }
}
