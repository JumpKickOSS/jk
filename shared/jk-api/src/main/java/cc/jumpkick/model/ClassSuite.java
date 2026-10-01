// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;

/**
 * A {@code [test.suites.<name>]} suite selected by class pattern over the default suite's classes.
 * Both lists hold {@code --class} syntax patterns with no {@code #method}. A class {@code classes}
 * matches belongs to the suite; one {@code excludeClasses} also matches stays the suite's, so the
 * default suite never runs it, but runs in no suite, as a Failsafe {@code <excludes>} entry does.
 */
public record ClassSuite(List<String> classes, List<String> excludeClasses) {

    public ClassSuite {
        classes = List.copyOf(classes);
        excludeClasses = excludeClasses == null ? List.of() : List.copyOf(excludeClasses);
    }

    /** A suite of {@code classes} that leaves none out. */
    public static ClassSuite of(List<String> classes) {
        return new ClassSuite(classes, List.of());
    }
}
