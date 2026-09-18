// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * One {@code --method=<class regex>#<method glob>} argument: within the classes the regex names,
 * only the methods the glob matches run. Applied as a JUnit Platform post-discovery filter, so it
 * holds for every engine that reports a {@link MethodSource} — Jupiter, Vintage, TestNG, Spock —
 * and a class whose every method the filter drops is pruned from the plan with its container.
 *
 * <p>A test whose declaring class no selection names is left alone: {@code --class Foo#bar
 * --class Baz} runs one method of Foo and all of Baz. A nested class is judged by its outermost
 * class too, so {@code Foo#inner} reaches a method of {@code Foo$Inner}.
 */
record MethodSelection(Pattern classPattern, Pattern methodPattern) {

    /** Parses every raw argument value; the class regex ends at the last {@code #}. */
    static List<MethodSelection> parse(List<String> raw) {
        List<MethodSelection> out = new ArrayList<>();
        for (String value : raw) {
            int hash = value.lastIndexOf('#');
            if (hash < 0) {
                throw new IllegalArgumentException("--method needs <class regex>#<method>: " + value);
            }
            String method = value.substring(hash + 1).trim();
            if (method.isEmpty()) {
                throw new IllegalArgumentException("--method names no method after '#': " + value);
            }
            out.add(new MethodSelection(Pattern.compile(value.substring(0, hash)), Pattern.compile(glob(method))));
        }
        return List.copyOf(out);
    }

    /** The post-discovery filter for {@code selections}, or {@code null} when there is none. */
    static @Nullable PostDiscoveryFilter filter(List<MethodSelection> selections) {
        if (selections.isEmpty()) return null;
        return descriptor -> {
            if (!(descriptor.getSource().orElse(null) instanceof MethodSource source)) {
                return FilterResult.included("not a method");
            }
            boolean named = false;
            for (MethodSelection s : selections) {
                if (!s.namesClass(source.getClassName())) continue;
                named = true;
                if (s.methodPattern().matcher(source.getMethodName()).matches()) {
                    return FilterResult.included("selected method");
                }
            }
            return named
                    ? FilterResult.excluded("not a selected method of " + source.getClassName())
                    : FilterResult.included("class not under a method selection");
        };
    }

    /** True when the regex names {@code className}, or the outermost class of a nested one. */
    boolean namesClass(String className) {
        if (classPattern.matcher(className).matches()) return true;
        int nested = className.indexOf('$');
        return nested > 0
                && classPattern.matcher(className.substring(0, nested)).matches();
    }

    /** {@code *} stands for any run of characters; everything else is literal. */
    private static String glob(String pattern) {
        StringBuilder out = new StringBuilder();
        String[] literals = pattern.split("\\*", -1);
        for (int i = 0; i < literals.length; i++) {
            if (i > 0) out.append(".*");
            if (!literals[i].isEmpty()) out.append(Pattern.quote(literals[i]));
        }
        return out.toString();
    }
}
