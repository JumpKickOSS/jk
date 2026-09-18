// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Class-name filters for {@link JUnitLauncher}: exact names ({@code --affected}) and {@code --class}
 * patterns, each a class pattern or {@code <class pattern>#<method>}.
 */
final class JUnitClassFilter {

    private JUnitClassFilter() {}

    /**
     * One anchored regex over class names for {@code --class} patterns: a name with a dot is a
     * fully qualified class, a bare name matches that simple name in any package, and {@code *}
     * stands for any run of characters in either form. A {@code #method} suffix selects methods,
     * not classes, and is left to {@link #methodArgs}.
     */
    static String patternRegex(List<String> patterns) {
        StringBuilder re = new StringBuilder("^(");
        boolean first = true;
        for (String raw : patterns) {
            String p = classPart(raw);
            if (p.isEmpty()) continue;
            if (!first) re.append('|');
            first = false;
            re.append(classRegex(p));
        }
        return re.append(")$").toString();
    }

    /**
     * The runner's {@code --method=<class regex>#<method>} arguments, one per pattern, when any
     * pattern names a method; a pattern without one becomes {@code #*} so the whole class it names
     * still runs beside the selected methods. Empty when every pattern names whole classes.
     */
    static List<String> methodArgs(List<String> patterns) {
        boolean any = false;
        for (String raw : patterns) {
            if (raw.indexOf('#') >= 0) any = true;
        }
        if (!any) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : patterns) {
            String cls = classPart(raw);
            if (cls.isEmpty()) continue;
            out.add("--method=^(" + classRegex(cls) + ")$#" + methodPart(raw));
        }
        return out;
    }

    /** The class half of a pattern, trimmed: everything before the first {@code #}. */
    static String classPart(String pattern) {
        int hash = pattern.indexOf('#');
        return (hash < 0 ? pattern : pattern.substring(0, hash)).trim();
    }

    /** The method half of a pattern, or {@code *} for a pattern that names a whole class. */
    static String methodPart(String pattern) {
        int hash = pattern.indexOf('#');
        if (hash < 0) return "*";
        String method = pattern.substring(hash + 1).trim();
        return method.isEmpty() ? "*" : method;
    }

    private static String classRegex(String p) {
        return (p.contains(".") ? "" : "(.*\\.)?") + glob(p);
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
}
