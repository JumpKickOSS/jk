// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
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
     * {@code suiteHint} (the engine's {@code SuiteFilter.classHint}) when the patterns name
     * classes another suite of the module owns.
     */
    public static String skipLabel(List<String> patterns, @Nullable String suiteHint) {
        String label = "skipped: no classes match --class " + String.join(", ", patterns);
        return suiteHint == null ? label : label + "; " + suiteHint;
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
     * What one module's run leaves under {@code selection}.
     *
     * @param result the summary of the tests that ran; {@code null} when none did or the module skipped
     * @param noMatch the failure line when the selection named something that did not run
     * @param skipped a workspace module the patterns matched nothing in: the run-wide verdict judges it
     * @param otherSuite the suite hint to warn with when tests ran but a named class belongs to
     *     another suite
     */
    public record Verdict(
            @Nullable TestSummary result,
            @Nullable String noMatch,
            boolean skipped,
            @Nullable String otherSuite) {}

    /**
     * The verdict of a module's run of {@code selection}. A workspace module the patterns matched
     * nothing in skips, so the run-wide verdict judges the patterns across modules; a standalone
     * project fails on the spot, naming the runner's {@code tagExcluded} warning when the tag
     * filter emptied the run. A class named without a wildcard that the tag filter dropped fails
     * the module, whatever else ran. A {@code --class} naming a class another suite owns says which
     * suite ({@code suiteHint}), matched or not.
     *
     * @param workspace whether the module is one of several the run covers
     */
    public static Verdict judge(
            TestSelection selection,
            boolean exactClassList,
            @Nullable String tagExcluded,
            @Nullable String suiteHint,
            boolean workspace,
            TestSummary result) {
        boolean nothing = nothingMatched(selection, exactClassList, result);
        if (!exactClassList && tagExcluded != null && namesClasses(selection)) {
            return nothing
                    ? new Verdict(null, noMatchMessage(selection, tagExcluded, suiteHint), false, null)
                    : new Verdict(result, tagExcluded, false, null);
        }
        if (!nothing) return new Verdict(result, null, false, suiteHint);
        if (workspace) return new Verdict(null, null, true, null);
        return new Verdict(null, noMatchMessage(selection, tagExcluded, suiteHint), false, null);
    }

    /**
     * The workspace's verdict once every module has finished: the no-match message when the session
     * asked for classes, the run executed suites, and no module's suite ran a test — otherwise
     * {@code null}. {@code runs} holds what each module's test step left. A module that matched
     * carries a {@link TestRun#result} with tests in it, whether it ran them or replayed a green
     * stamp for the same patterns. A module whose tag filter dropped a named class left {@link
     * TestRun#tagExcluded}, and a module whose unselected suite owns a named class left {@link
     * TestRun#suiteHint}; the verdict carries them.
     */
    public static @Nullable String runWideVerdict(Session session, boolean skipTests, Collection<TestRun> runs) {
        TestSelection selection = session.testSelection();
        if (selection.classes().isEmpty() || session.affected()) return null;
        if (skipTests || selection.scriptsOnly()) return null;
        for (TestRun run : runs) {
            TestSummary result = run.result();
            if (result != null && result.total() > 0) return null;
        }
        List<String> excluded = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        for (TestRun run : runs) {
            String tagExcluded = run.tagExcluded();
            String suiteHint = run.suiteHint();
            if (tagExcluded != null) excluded.add(tagExcluded);
            if (suiteHint != null) hints.add(suiteHint);
        }
        return noMatchMessage(
                selection,
                excluded.isEmpty() ? null : String.join("; ", excluded),
                hints.isEmpty() ? null : String.join("; ", hints));
    }
}
