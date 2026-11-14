// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The results line of a module whose {@code -I} / {@code -E} filter left nothing to run. Every
 * test class compiled, discovery ran, and the filter dropped each test of the selected suites: said
 * in one plain line naming the filter, and the tags it both includes and excludes when it does, since
 * those select nothing.
 */
final class TagExcludedSuite {

    private TagExcludedSuite() {}

    /** Writes the line as the step's own output when it applies; silent otherwise. */
    static void note(TaskContext ctx, TestSelection selection, boolean exactClassList, TestSummary result) {
        String line = line(selection, exactClassList, result);
        if (line != null) ctx.output(line);
    }

    /**
     * The line, or {@code null} when it does not apply: no tag filter is in force, the selection
     * names classes (that verdict is {@link TestClassMatch}), an exact class list overrode the
     * filter, a test ran, or the suite failed.
     */
    static @Nullable String line(TestSelection selection, boolean exactClassList, TestSummary result) {
        if (exactClassList || !selection.classes().isEmpty()) return null;
        if (selection.includeTags().isEmpty() && selection.excludeTags().isEmpty()) return null;
        if (result.total() != 0 || result.failed() != 0) return null;
        List<String> both = selection.includeTags().stream()
                .filter(selection.excludeTags()::contains)
                .toList();
        if (!both.isEmpty()) {
            return "0 tests: --include-tags and --exclude-tags both name " + String.join(", ", both);
        }
        return "0 tests (all excluded by " + tagFilter(selection) + ")";
    }

    private static String tagFilter(TestSelection selection) {
        List<String> parts = new ArrayList<>();
        if (!selection.includeTags().isEmpty())
            parts.add("--include-tags " + String.join(",", selection.includeTags()));
        if (!selection.excludeTags().isEmpty())
            parts.add("--exclude-tags " + String.join(",", selection.excludeTags()));
        return String.join(" and ", parts);
    }
}
