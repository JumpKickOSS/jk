// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.TestRun;
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
 * run, so there the empty match fails on the spot. Either way it is a {@link #NO_TESTS_MATCHED}
 * usage error, not a failed test.
 */
public final class TestClassMatch {

    /** The diagnostic code of a selection that matched no test: a usage error, never a red test. */
    public static final String NO_TESTS_MATCHED = "no-tests-matched";

    private TestClassMatch() {}

    /**
     * True when no pattern's class half carries a {@code *}: the selection names its classes, so a named class
     * the tag filter drops fails the run instead of passing green without it. A wildcard composes
     * with the tag filter, and the classes it reaches that the filter drops are a warning.
     */
    public static boolean namesClasses(TestSelection selection) {
        return !selection.classes().isEmpty()
                && selection.classes().stream().noneMatch(p -> {
                    int hash = p.indexOf('#');
                    return (hash < 0 ? p : p.substring(0, hash)).indexOf('*') >= 0;
                });
    }

    /**
     * True when {@code result} is the empty summary of a {@code --class} run: the selection named
     * patterns, no exact class list ({@code --affected}) overrode them, and the suite neither ran a
     * test nor failed. A suite that crashed before its first test has a failure and is not this.
     */
    public static boolean nothingMatched(TestSelection selection, boolean exactClassList, TestSummary result) {
        if (exactClassList || selection.classes().isEmpty()) return false;
        return result.total() == 0 && result.failed() == 0;
    }

    /**
     * The step label of a module the patterns skipped, same shape as the absent-suite skip, with
     * {@code suiteHint} ({@link cc.jumpkick.test.SuiteFilter#classHint}) when the patterns name
     * classes another suite of the module owns.
     */
    public static String skipLabel(List<String> patterns, @Nullable String suiteHint) {
        String label = "skipped: no classes match --class " + String.join(", ", patterns);
        return suiteHint == null ? label : label + "; " + suiteHint;
    }

    /**
     * Skip a workspace module the patterns matched nothing in, leaving the runner's
     * {@code tag-excluded} warning and the suite hint, when there are, for {@link #runWideVerdict}.
     */
    public static void skip(
            TaskContext ctx, TestSelection selection, @Nullable String tagExcluded, @Nullable String suiteHint) {
        if (tagExcluded != null || suiteHint != null) {
            ctx.put(BuildPlanner.TEST_RUN, new TestRun(null, tagExcluded, suiteHint));
        }
        ctx.label(skipLabel(selection.classes(), suiteHint));
        ctx.cached();
    }

    /** The failure line: the patterns, so the typo is on the screen; "tests" when one names a method. */
    public static String noMatchMessage(List<String> patterns) {
        boolean method = patterns.stream().anyMatch(p -> p.indexOf('#') >= 0);
        return "no test " + (method ? "methods" : "classes") + " matched --class " + String.join(", ", patterns);
    }

    /**
     * The failure line. It opens with the patterns; when they name classes a suite the run did not
     * select owns, {@code suiteHint} naming that suite follows, then any {@code tag-excluded}
     * warning. Otherwise the {@code tag-excluded} warning alone when the {@code -I}/{@code -E}
     * filter dropped what the patterns named, since it names each class, its tags and the flag to
     * drop.
     */
    public static String noMatchMessage(
            TestSelection selection, @Nullable String tagExcluded, @Nullable String suiteHint) {
        String head = noMatchMessage(selection.classes());
        String tagged = tagExcluded == null || tagExcluded.isBlank() ? null : tagExcluded;
        if (suiteHint != null && !suiteHint.isBlank()) {
            String line = head + ": " + suiteHint;
            return tagged != null ? line + "; " + tagged : line;
        }
        return tagged == null ? head : tagged;
    }

    /**
     * The workspace's verdict once every module has finished: the no-match message when the session
     * asked for classes, the run executed suites, and no module's suite ran a test — otherwise
     * {@code null}. A module that matched carries a {@link TestRun#result} with tests in it, whether
     * it ran them or replayed a green stamp for the same patterns. A module whose tag filter dropped
     * a named class left {@link TestRun#tagExcluded}, and a module whose unselected suite owns a
     * named class left {@link TestRun#suiteHint}; the verdict carries them.
     */
    public static @Nullable String runWideVerdict(Session session, boolean skipTests, Collection<BuildPlan> plans) {
        TestSelection selection = session.testSelection();
        if (selection.classes().isEmpty() || session.affected()) return null;
        if (skipTests || selection.scriptsOnly()) return null;
        for (BuildPlan plan : plans) {
            TestSummary result =
                    plan.get(BuildPlanner.TEST_RUN).map(TestRun::result).orElse(null);
            if (result != null && result.total() > 0) return null;
        }
        List<String> excluded = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        for (BuildPlan plan : plans) {
            plan.get(BuildPlanner.TEST_RUN).map(TestRun::tagExcluded).ifPresent(excluded::add);
            plan.get(BuildPlanner.TEST_RUN).map(TestRun::suiteHint).ifPresent(hints::add);
        }
        return noMatchMessage(
                selection,
                excluded.isEmpty() ? null : String.join("; ", excluded),
                hints.isEmpty() ? null : String.join("; ", hints));
    }
}
