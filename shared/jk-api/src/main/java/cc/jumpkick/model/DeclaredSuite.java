// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    /** The built-in suite every module has, the tag it claims by convention, and its directory name. */
    public static final String INTEGRATION = "integration";

    /**
     * {@code tables} with the built-in {@link #INTEGRATION} suite: every module has one, claiming the
     * tests tagged {@code integration} beside its own directory, with no table written. A
     * {@code [test.suites.integration]} table extends it: its tags add to the convention's, its
     * classes claim more, and its exclusions and settings apply as written. No other tag becomes a
     * suite on its own, and no class-name pattern ({@code *IT}) is implied.
     */
    public static Map<String, DeclaredSuite> withConvention(Map<String, DeclaredSuite> tables) {
        DeclaredSuite table = tables.get(INTEGRATION);
        if (table != null && table.tags().contains(INTEGRATION)) return tables;
        Map<String, DeclaredSuite> out = new LinkedHashMap<>(tables);
        if (table == null) {
            out.put(INTEGRATION, ofTags(List.of(INTEGRATION)));
        } else {
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            tags.add(INTEGRATION);
            tags.addAll(table.tags());
            out.put(
                    INTEGRATION,
                    new DeclaredSuite(
                            table.classes(),
                            List.copyOf(tags),
                            table.excludeClasses(),
                            table.excludeTags(),
                            table.settings(),
                            table.explicit()));
        }
        return Collections.unmodifiableMap(out);
    }

    /** A suite of the tests tagged with any of {@code tags}. */
    public static DeclaredSuite ofTags(List<String> tags) {
        return new DeclaredSuite(List.of(), tags, List.of(), List.of());
    }
}
