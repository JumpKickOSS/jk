// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The results line of a module whose tag filter left nothing to run. Every test class compiled,
 * discovery ran, and the filter dropped each test: the expected outcome of a tier the module has
 * no tests in, said in one plain line naming the profile (or the tags) that did the excluding.
 */
final class TagExcludedSuite {

    private TagExcludedSuite() {}

    /** Writes the line as the step's own output when it applies; silent otherwise. */
    static void note(
            TaskContext ctx,
            TestSelection selection,
            @Nullable String profile,
            boolean exactClassList,
            TestSummary result) {
        String line = line(selection, profile, exactClassList, result);
        if (line != null) ctx.output(line);
    }

    /**
     * The line, or {@code null} when it does not apply: no tag filter is in force, the selection
     * names classes (that verdict is {@link TestClassMatch}), an exact class list overrode the
     * filter, a test ran, or the suite failed.
     */
    static @Nullable String line(
            TestSelection selection, @Nullable String profile, boolean exactClassList, TestSummary result) {
        if (exactClassList || !selection.classes().isEmpty()) return null;
        if (selection.includeTags().isEmpty() && selection.excludeTags().isEmpty()) return null;
        if (result.total() != 0 || result.failed() != 0) return null;
        if (profile != null && !profile.isBlank()) return "0 tests (all excluded by profile " + profile + ")";
        return "0 tests (all excluded by " + tagFilter(selection) + ")";
    }

    private static String tagFilter(TestSelection selection) {
        List<String> parts = new ArrayList<>();
        if (!selection.includeTags().isEmpty()) parts.add("include-tags " + String.join(", ", selection.includeTags()));
        if (!selection.excludeTags().isEmpty()) parts.add("exclude-tags " + String.join(", ", selection.excludeTags()));
        return String.join(" and ", parts);
    }
}
