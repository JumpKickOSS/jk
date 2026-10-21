// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** A dropped class's tag list in the runner's {@code tag-excluded} warning: {@code a.FooIT [integration, slow]}. */
    private static final Pattern TAG_LIST = Pattern.compile("\\[([^\\]]+)]");

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
     * {@code result} failed by the tag filter dropping a class the selection named: the dropped
     * class as one more failure beside whatever ran, or {@link #asFailure} when nothing ran.
     */
    public static TestSummary withTagExcluded(
            TestSummary result,
            String moduleLabel,
            TestSelection selection,
            String tagExcluded,
            @Nullable String suiteHint) {
        if (nothingMatched(selection, false, result)) return asFailure(moduleLabel, selection, tagExcluded, suiteHint);
        List<TestFailureInfo> failures = new ArrayList<>(result.failures());
        failures.add(new TestFailureInfo(moduleLabel, "", "", "(test run)", "", tagExcluded, ""));
        return new TestSummary(
                result.total() + 1,
                result.succeeded(),
                result.failed() + 1,
                result.skipped(),
                result.classes(),
                failures,
                result.classWallMs(),
                result.workers());
    }

    /**
     * The runner's {@code tag-excluded} warning with the profiles of {@code profiles}, other than
     * the {@code active} one, whose tag lists run every tag it names: {@code ; --profile
     * integration runs it}. Unchanged when no profile does.
     */
    public static @Nullable String namingProfiles(
            @Nullable String tagExcluded, Profiles profiles, @Nullable String active) {
        if (tagExcluded == null || tagExcluded.isBlank()) return tagExcluded;
        Set<String> tags = new TreeSet<>();
        Matcher m = TAG_LIST.matcher(tagExcluded);
        while (m.find()) {
            for (String tag : m.group(1).split(",")) {
                if (!tag.isBlank()) tags.add(tag.trim());
            }
        }
        if (tags.isEmpty()) return tagExcluded;
        List<String> running = new ArrayList<>();
        for (String name : new TreeSet<>(profiles.byName().keySet())) {
            if (name.equals(active)) continue;
            if (runs(profiles.resolve(name), tags)) running.add("-p " + name);
        }
        if (running.isEmpty()) return tagExcluded;
        boolean one = !tagExcluded.contains(" classes the tag filter excluded");
        return tagExcluded + "; " + String.join(" or ", running) + " runs " + (one ? "it" : "them");
    }

    /**
     * Whether {@code profile} admits a class carrying {@code tags}: it sets its own exclude list
     * and that list names none of them, and its include list, when it has one, names one.
     */
    private static boolean runs(Profile profile, Set<String> tags) {
        if (!profile.excludeTagsSet()) return false;
        if (profile.excludeTags().stream().map(String::trim).anyMatch(tags::contains)) return false;
        List<String> include = profile.includeTags();
        return include.isEmpty() || include.stream().map(String::trim).anyMatch(tags::contains);
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
     * {@code suiteHint} ({@link cc.jumpkick.test.SuiteClassFilter#classHint}) when the patterns name
     * classes another suite of the module owns.
     */
    public static String skipLabel(List<String> patterns, @Nullable String suiteHint) {
        String label = "no classes matched --class " + String.join(", ", patterns) + " — skipped";
        return suiteHint == null ? label : label + "; " + suiteHint;
    }

    /**
     * Skip a workspace module the patterns matched nothing in, leaving the runner's
     * {@code tag-excluded} warning and the suite hint, when there are, for {@link #runWideVerdict}.
     */
    public static void skip(
            TaskContext ctx, TestSelection selection, @Nullable String tagExcluded, @Nullable String suiteHint) {
        if (tagExcluded != null) ctx.put(BuildPlanner.TAG_EXCLUDED, tagExcluded);
        if (suiteHint != null) ctx.put(BuildPlanner.CLASS_SUITE_HINT, suiteHint);
        ctx.label(skipLabel(selection.classes(), suiteHint));
        ctx.cached();
    }

    /** The failure line: the patterns, so the typo is on the screen; "tests" when one names a method. */
    public static String noMatchMessage(List<String> patterns) {
        boolean method = patterns.stream().anyMatch(p -> p.indexOf('#') >= 0);
        return "no test " + (method ? "methods" : "classes") + " matched --class " + String.join(", ", patterns);
    }

    /**
     * The failure line. When the patterns name classes a suite the run did not select owns, the
     * patterns and {@code suiteHint} naming that suite come first, then any {@code tag-excluded}
     * warning. Otherwise the runner's {@code tag-excluded} warning when the tag filter dropped what
     * the patterns named, since it names each class, its tags and the flag that runs it; otherwise
     * the patterns themselves.
     */
    public static String noMatchMessage(
            TestSelection selection, @Nullable String tagExcluded, @Nullable String suiteHint) {
        String tagged = tagExcluded == null || tagExcluded.isBlank() ? null : tagExcluded;
        if (suiteHint != null && !suiteHint.isBlank()) {
            String line = noMatchMessage(selection.classes()) + " — " + suiteHint;
            return tagged != null ? line + "; " + tagged : line;
        }
        return tagged != null ? tagged : noMatchMessage(selection.classes());
    }

    /** A standalone run's verdict: one synthetic failure naming the cause, attributed to the module. */
    public static TestSummary asFailure(
            String moduleLabel, TestSelection selection, @Nullable String tagExcluded, @Nullable String suiteHint) {
        String message = noMatchMessage(selection, tagExcluded, suiteHint);
        return new TestSummary(
                1, 0, 1, 0, List.of(new TestFailureInfo(moduleLabel, "", "", "(test run)", "", message, "")));
    }

    /**
     * The workspace's verdict once every module has finished: the no-match message when the session
     * asked for classes, the run executed suites, and no module's suite ran a test — otherwise
     * {@code null}. A module that matched carries a {@link BuildPlanner#TEST_RESULT} with tests in
     * it, whether it ran them or replayed a green stamp for the same patterns. A module whose tag
     * filter dropped a named class left {@link BuildPlanner#TAG_EXCLUDED}, and a module whose
     * unselected suite owns a named class left {@link BuildPlanner#CLASS_SUITE_HINT}; the verdict
     * carries them.
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
        List<String> hints = new ArrayList<>();
        for (BuildPlan plan : plans) {
            plan.get(BuildPlanner.TAG_EXCLUDED).ifPresent(excluded::add);
            plan.get(BuildPlanner.CLASS_SUITE_HINT).ifPresent(hints::add);
        }
        return noMatchMessage(
                selection,
                excluded.isEmpty() ? null : String.join("; ", excluded),
                hints.isEmpty() ? null : String.join("; ", hints));
    }
}
