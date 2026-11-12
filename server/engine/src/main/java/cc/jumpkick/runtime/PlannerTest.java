// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.TierTags;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.ClassSuite;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.SuiteClassFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * What compile-test and run-tests share and what reads them from outside: the suite marker beside
 * the test classes, the suite selection, the require edges, the order around the process-wide test
 * gate, and what a finished run's marker vouches for. The steps themselves are {@link
 * TestCompileSteps} and {@link TestSteps}.
 */
public final class PlannerTest {

    private PlannerTest() {}

    /**
     * The suffix of the file beside {@code target/test-classes} where compile-test records the suite
     * selection that produced the tree. Beside the tree, not in it: everything in the tree is its
     * content, keyed, stored and packed.
     */
    private static final String SUITE_MARKER = ".suites";

    /** The suite marker of {@code testClasses}: {@code test-classes.suites} beside it. */
    public static Path suiteMarker(Path testClasses) {
        return testClasses.resolveSibling(testClasses.getFileName() + SUITE_MARKER);
    }

    /**
     * The suites whose sources compiled into {@code testClasses}, as its suite marker records them;
     * the default suite alone when there is no marker.
     */
    public static Set<String> compiledSuites(Path testClasses) {
        Path marker = suiteMarker(testClasses);
        try {
            if (!Files.isRegularFile(marker)) return Set.of(TestSuites.DEFAULT);
            Set<String> out = new TreeSet<>();
            for (String suite : Files.readString(marker).split(",")) {
                if (!suite.isBlank()) out.add(suite.strip());
            }
            return out;
        } catch (IOException unreadable) {
            return Set.of(TestSuites.DEFAULT);
        }
    }

    /** The session's suite selection resolved against the suites this module has, directories and class patterns. */
    static TestSelection.Resolved resolveSuites(BuildPlanner.Inputs in, boolean compact, JkBuild project) {
        var sel = in.session() == null ? TestSelection.DEFAULT : in.session().testSelection();
        return sel.resolve(
                TestSuites.available(in.dir(), compact, project.build().testClassSuites()));
    }

    /** The plugin steps whose output compile-test reads: every test-source generator. */
    static List<String> testSourceGenSteps(@Nullable PluginDeclarations decls) {
        List<String> out = new ArrayList<>();
        if (decls == null) return out;
        for (TaskDecl step : decls.steps()) {
            if (step.testSourceGenerating()) out.add("plugin-" + step.name());
        }
        return out;
    }

    /**
     * A Groovy module's classes (main or test) implement groovy.lang.GroovyObject: javac and groovyc
     * must resolve it from the version-matched jar.
     */
    static boolean needsGroovyJar(boolean groovyModule, TestSources src) {
        return groovyModule || !src.gvTest().isEmpty();
    }

    /**
     * What the suite waits for: its compiled tests, the resources, and every plugin step that
     * shapes what the forked JVM loads (its own classpath and arguments, or the main classes and
     * resources, which a transform such as bytecode enhancement replaces).
     */
    static List<String> runTestsRequires(@Nullable PluginDeclarations pluginDecls, List<String> extraRequires) {
        List<String> testRequires = new ArrayList<>();
        testRequires.add(TaskNames.COMPILE_TEST);
        testRequires.add(TaskNames.COPY_RESOURCES);
        testRequires.addAll(extraRequires);
        if (pluginDecls != null) {
            for (TaskDecl step : pluginDecls.steps()) {
                if (step.testOnly() || step.feedsTests() || step.shapesMainOutput()) {
                    testRequires.add("plugin-" + step.name());
                }
            }
        }
        return testRequires;
    }

    /** The tags the run uses without {@code -p}: the workspace root's, as {@link TierTags} resolves them. */
    static TestClassMatch.Tier defaultTier(Path moduleDir) {
        TierTags tiers = TierTags.of(moduleDir, null);
        return new TestClassMatch.Tier(tiers.includeTags(), tiers.excludeTags());
    }

    /** Which compiled test classes the run's suites own, and which suite each runs in. */
    static SuiteClassFilter suiteFilter(BuildPlanner.Inputs in, boolean compact, JkBuild project) {
        Map<String, ClassSuite> patternSuites = project.build().testClassSuites();
        var resolved = resolveSuites(in, compact, project);
        List<String> suites = resolved.ok() ? resolved.suites() : List.of(TestSuites.DEFAULT);
        return SuiteClassFilter.of(in.dir(), compact, suites, patternSuites);
    }

    /**
     * Coverage tooling resolves before the serial test gate is taken, never inside it. The fetch
     * can take seconds or fail outright, and the gate is the checkout's: a fetch behind it would park
     * every other module's suite behind a download, and a failed fetch would leave the gate held
     * for good. So a failing {@code resolve} throws with the gate untouched, and {@code gate} runs
     * only once {@code resolve} has answered.
     *
     * @param resolve the coverage tooling to fetch, or null when the run measures no coverage
     * @param gated whether the run takes the serial gate at all
     * @param gate takes the gate; the caller releases it
     */
    static <T> @Nullable T resolveBeforeGate(@Nullable Callable<T> resolve, boolean gated, Runnable gate)
            throws Exception {
        T tools = resolve == null ? null : resolve.call();
        if (gated) gate.run();
        return tools;
    }

    /** What a finished run leaves for later builds under the same inputs. */
    enum Stamp {
        /** Tests ran and passed: the next build replays them as up-to-date. */
        GREEN,
        /** Nothing was there to run: the next build skips the fork and reports no tests. */
        NO_TESTS,
        /** No evidence either way: the next build runs the suite again. */
        NONE
    }

    /**
     * A passing run with tests is the green marker later builds skip on. A run that passed by
     * executing nothing in a module with test sources is not evidence that tests pass — but it is
     * evidence that there is nothing to run: helpers only, every test behind an excluded tag — and
     * it gets a stamp of its own, so the module does not fork a discovery JVM on every build
     * forever. A crashed discovery reports a failure and earns nothing. A module without test
     * sources has nothing to run, and its empty run is the whole truth. A run the session
     * cancelled earns nothing: its counts may cover only the part of the suite that ran.
     */
    static Stamp stampFor(TestSummary result, boolean testSourcesExist, boolean cancelled) {
        if (cancelled || !result.allPassed()) return Stamp.NONE;
        if (result.total() > 0 || !testSourcesExist) return Stamp.GREEN;
        return Stamp.NO_TESTS;
    }

    /** The green run's counts replayed off a run-tests marker; {@code null} for markers written
     * before counts were stored (or with unparseable ones) — the caller then replays nothing. */
    static @Nullable TestSummary stampedSummary(ActionCache.ActionRecord record) {
        try {
            String total = record.outputs().get(TestStamp.TOTAL);
            if (total == null) return null;
            long succeeded = Long.parseLong(record.outputs().getOrDefault(TestStamp.SUCCEEDED, total));
            long skipped = Long.parseLong(record.outputs().getOrDefault(TestStamp.SKIPPED, "0"));
            return new TestSummary(Long.parseLong(total), succeeded, 0, skipped, List.of());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
