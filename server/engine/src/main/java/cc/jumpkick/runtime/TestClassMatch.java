// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code --class} selection that matched nothing means, per module and for the run.
 *
 * <p>The patterns are judged against the whole run, not each module: in a workspace most modules
 * do not contain the class the user named, and each of them skips. Only when no module matched
 * anything is the pattern a typo, and a typo must never pass green. A standalone project is its own
 * run, so there the empty match fails on the spot.
 */
public final class TestClassMatch {

    private TestClassMatch() {}

    /**
     * True when {@code result} is the empty summary of a {@code --class} run: the selection named
     * patterns, no exact class list ({@code --affected}) overrode them, and the suite neither ran a
     * test nor failed. A suite that crashed before its first test has a failure and is not this.
     */
    public static boolean nothingMatched(TestSelection selection, boolean exactClassList, TestSummary result) {
        if (exactClassList || selection.classes().isEmpty()) return false;
        return result.total() == 0 && result.failed() == 0;
    }

    /** The step label of a module the patterns skipped, same shape as the absent-suite skip. */
    public static String skipLabel(List<String> patterns) {
        return "no classes matched --class " + String.join(", ", patterns) + " — skipped";
    }

    /**
     * Skip a workspace module the patterns matched nothing in, leaving the runner's
     * {@code tag-excluded} warning, when it raised one, for {@link #runWideVerdict}.
     */
    public static void skip(TaskContext ctx, TestSelection selection, @Nullable String tagExcluded) {
        if (tagExcluded != null) ctx.put(BuildPlanner.TAG_EXCLUDED, tagExcluded);
        ctx.label(skipLabel(selection.classes()));
        ctx.cached();
    }

    /** The failure line: the patterns, so the typo is on the screen; "tests" when one names a method. */
    public static String noMatchMessage(List<String> patterns) {
        boolean method = patterns.stream().anyMatch(p -> p.indexOf('#') >= 0);
        return "no test " + (method ? "methods" : "classes") + " matched --class " + String.join(", ", patterns);
    }

    /**
     * The failure line: the runner's {@code tag-excluded} warning when the tag filter dropped what
     * the patterns named, since it names each class, its tags and the flag that runs it; otherwise
     * the patterns themselves.
     */
    public static String noMatchMessage(TestSelection selection, @Nullable String tagExcluded) {
        return tagExcluded != null && !tagExcluded.isBlank() ? tagExcluded : noMatchMessage(selection.classes());
    }

    /** A standalone run's verdict: one synthetic failure naming the cause, attributed to the module. */
    public static TestSummary asFailure(String moduleLabel, TestSelection selection, @Nullable String tagExcluded) {
        return new TestSummary(
                1,
                0,
                1,
                0,
                List.of(new TestFailureInfo(
                        moduleLabel, "", "", "(test run)", "", noMatchMessage(selection, tagExcluded), "")));
    }

    /**
     * The workspace's verdict once every module has finished: the no-match message when the session
     * asked for classes, the run executed suites, and no module's suite ran a test — otherwise
     * {@code null}. A module that matched carries a {@link BuildPlanner#TEST_RESULT} with tests in
     * it, whether it ran them or replayed a green stamp for the same patterns. A module whose tag
     * filter dropped a named class left {@link BuildPlanner#TAG_EXCLUDED}, and that is the verdict.
     */
    public static @Nullable String runWideVerdict(Session session, boolean skipTests, Collection<BuildPlan> plans) {
        TestSelection selection = session.testSelection();
        if (selection.classes().isEmpty() || session.affected()) return null;
        if (skipTests || selection.scriptsOnly()) return null;
        for (BuildPlan plan : plans) {
            TestSummary result = plan.get(BuildPlanner.TEST_RESULT).orElse(null);
            if (result != null && result.total() > 0) return null;
        }
        List<String> excluded = new ArrayList<>();
        for (BuildPlan plan : plans) plan.get(BuildPlanner.TAG_EXCLUDED).ifPresent(excluded::add);
        return noMatchMessage(selection, excluded.isEmpty() ? null : String.join("; ", excluded));
    }
}
