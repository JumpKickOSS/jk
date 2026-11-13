// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;

/**
 * A {@code [test.suites.<name>]} table: the tests the suite claims beyond its own directory.
 * {@code classes} ({@code --class} syntax, no {@code #method}) claims classes of the default suite's
 * sources; {@code tags} claims every test carrying one of them, from any of the module's test
 * sources. A test {@code excludeClasses} or {@code excludeTags} also names stays claimed, so no other
 * suite runs it, but this suite does not run it either, as a Failsafe {@code <excludes>} entry does.
 * {@code settings} are what the suite's own test JVMs get; an {@code explicit} suite runs only when
 * named ({@code -s}, {@code guard-suites}), never under {@code --all}.
 */
public record DeclaredSuite(
        List<String> classes,
        List<String> tags,
        List<String> excludeClasses,
        List<String> excludeTags,
        SuiteSettings settings,
        boolean explicit) {

    public DeclaredSuite {
        classes = classes == null ? List.of() : List.copyOf(classes);
        tags = tags == null ? List.of() : List.copyOf(tags);
        excludeClasses = excludeClasses == null ? List.of() : List.copyOf(excludeClasses);
        excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
        settings = settings == null ? SuiteSettings.NONE : settings;
    }

    /** A suite claiming by class and tag, with no settings of its own. */
    public DeclaredSuite(
            List<String> classes, List<String> tags, List<String> excludeClasses, List<String> excludeTags) {
        this(classes, tags, excludeClasses, excludeTags, SuiteSettings.NONE, false);
    }

    /** A suite of {@code classes} that leaves none out. */
    public static DeclaredSuite ofClasses(List<String> classes) {
        return new DeclaredSuite(classes, List.of(), List.of(), List.of());
    }

    /** A suite of {@code classes} less {@code excludeClasses}, claiming no tag. */
    public static DeclaredSuite ofClasses(List<String> classes, List<String> excludeClasses) {
        return new DeclaredSuite(classes, List.of(), excludeClasses, List.of());
    }

    /** A suite of the tests tagged with any of {@code tags}. */
    public static DeclaredSuite ofTags(List<String> tags) {
        return new DeclaredSuite(List.of(), tags, List.of(), List.of());
    }
}
