// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.MODULE;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.SuiteSettings;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.FilteredResources;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.CompiledTests;
import cc.jumpkick.task.MirroredOutputs;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.SuiteFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Each directory suite's own resource roots, when a run compiles more than one directory suite into
 * the shared test-classes tree. Copied into that one tree, a resource two suites ship at the same
 * path (a {@code META-INF/services} file, a {@code junit-platform.properties}) would be whichever
 * copy came last, and every suite would read it. So such a run copies no suite's resources into the
 * tree: each suite runs in its own JVMs with its own roots ahead of the shared classpath, as it
 * would run alone. The default suite's roots carry its configured extra roots. Empty when the run
 * compiles one directory suite, whose resources the tree holds.
 */
record SuiteResources(Map<String, List<Path>> bySuite) {

    /** A run of one directory suite: its resources are in the test-classes tree. */
    static final SuiteResources NONE = new SuiteResources(Map.of());

    SuiteResources {
        bySuite = Collections.unmodifiableMap(new LinkedHashMap<>(bySuite));
    }

    /**
     * The resource roots of each directory suite in {@code compiled}, in its order; {@link #NONE}
     * when fewer than two are directory suites. {@code defaultExtra} are the default suite's
     * configured roots beyond its resources directory.
     */
    static SuiteResources of(Path moduleDir, boolean compact, List<String> compiled, List<Path> defaultExtra) {
        if (!splits(compiled)) return NONE;
        Map<String, List<Path>> out = new LinkedHashMap<>();
        for (String suite : compiled) {
            out.put(suite, new ArrayList<>(ModuleLayout.suiteResourceDirs(moduleDir, compact, List.of(suite))));
        }
        out.computeIfAbsent(TestSuites.DEFAULT, k -> new ArrayList<>()).addAll(defaultExtra);
        return new SuiteResources(out);
    }

    /**
     * Test resources ride the test classpath next to compiled tests, so getResourceAsStream
     * fixtures resolve: the test KSP round's output and, when the run compiles one directory suite,
     * that suite's resources (plus its configured extra roots) are
     * mirrored into target/test-classes. A run of several directory suites mirrors none of them:
     * this record runs each with its own roots, so a same-path resource never collides.
     * run-tests folds every root into its TestStamp key. Returns what compile-test publishes: its
     * {@code sources}, the test compiles' action {@code keys}, whose records drop the mirrored
     * paths, and the resource roots.
     */
    static CompiledTests mirror(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            ActionCache actionCache,
            boolean compact,
            List<String> suiteNames,
            Path testClasses,
            Map<String, String> beforeNonClass,
            boolean mixedTest,
            boolean mixedTestGv,
            List<Path> sources,
            TestStamp.CompileTestKeys keys)
            throws IOException {
        List<Path> kspRoots = PlannerKsp.outputRoots(
                PlannerKsp.kspTestOutBase(ctx.require(MODULE).layout()));
        List<Path> extraRoots = new ArrayList<>();
        if (suiteNames.contains(TestSuites.DEFAULT)) {
            Set<String> unresolved = new TreeSet<>();
            extraRoots.addAll(FilteredResources.testRoots(
                    in.dir(), ctx.require(MODULE).project(), ctx.require(MODULE).layout(), unresolved));
            PlannerResources.warnUnresolved(ctx, unresolved);
        }
        SuiteResources apart = SuiteResources.of(in.dir(), compact, suiteNames, extraRoots);
        List<Path> suiteResDirs = new ArrayList<>(kspRoots);
        suiteResDirs.addAll(ModuleLayout.suiteResourceDirs(in.dir(), compact, suiteNames));
        suiteResDirs.addAll(extraRoots);
        Files.createDirectories(testClasses);
        // adoptUnowned: a test-resource copy that predates the ledger left files this mirror
        // must still remove. A file the compile just wrote is protected; main classes already
        // carry a ledger, so they do not adopt.
        Path ledger =
                ctx.require(MODULE).layout().buildDir().resolve("incremental").resolve(ResourceMirror.TEST_LEDGER);
        Set<String> mirrored = ResourceMirror.sync(
                apart.split() ? kspRoots : suiteResDirs,
                testClasses,
                ledger,
                true,
                ResourceMirror.changedNonClass(testClasses, beforeNonClass));
        // The compile stored whatever was in the tree, including a resource it did not produce.
        // Drop those paths so a later restore cannot put a deleted one back.
        List<String> released = new ArrayList<>();
        if (keys.javac() != null) released.add(keys.javac());
        if (!mixedTest && keys.kotlin() != null) released.add(keys.kotlin());
        if (!mixedTestGv && keys.groovy() != null) released.add(keys.groovy());
        for (String key : released) MirroredOutputs.release(actionCache, key, testClasses, mirrored);
        return new CompiledTests(false, sources, keys, suiteResDirs, apart.bySuite());
    }

    /** Whether a run compiling {@code compiled} runs its directory suites apart, mirroring none of their resources. */
    static boolean splits(List<String> compiled) {
        return compiled.size() >= 2;
    }

    /**
     * Whether {@code testClasses} lacks a suite resource a run compiling {@code compiled} mirrors
     * into it: the one directory suite's resource roots, none when the suites run apart. The
     * forecast's question, answered by the mirror's own rule.
     */
    static boolean mirroredResourcesOutOfSync(
            Path moduleDir, boolean compact, List<String> compiled, Path testClasses) {
        if (splits(compiled)) return false;
        for (Path root : ModuleLayout.suiteResourceDirs(moduleDir, compact, compiled)) {
            if (TaskForecaster.resourcesOutOfSync(root, testClasses)) return true;
        }
        return false;
    }

    /** Whether the suites run apart, each with its own roots, rather than over the tree's copy. */
    boolean split() {
        return !bySuite.isEmpty();
    }

    /**
     * One test JVM run: a launcher over the shared classpath with {@code classpath} after the test
     * classes, for {@code launch}'s suites, whose own settings its JVMs get.
     */
    @FunctionalInterface
    interface Launch {
        TestSummary run(JUnitLauncher launcher, List<Path> classpath, SuiteFilter.Launch launch) throws Exception;
    }

    /**
     * The launcher of the launch named {@code launch}, whose JVMs get {@code settings}. Asked for
     * each launch the selection names, before any JVM starts. The name is stable under one run-tests
     * stamp: {@link #ONE_LAUNCH} for a run in one launch, else the directory suite and the set of
     * suites the launch runs, so what a launch left out is answered per launch.
     */
    @FunctionalInterface
    interface Launcher {
        JUnitLauncher of(String launch, SuiteSettings settings);
    }

    /** The name of the only launch of a run that starts one. */
    static final String ONE_LAUNCH = "";

    /**
     * The launches the selection starts: one over {@code runtimeCp} when the tree holds the
     * resources and no selected suite has settings of its own, else one per directory suite, its
     * roots first, its classes alone, and within it one per {@link SuiteFilter#launches} set of
     * suites. A launch the {@code --class} patterns or the affected classes name nothing in is left
     * out, as is one whose suites claim no compiled class or whose every class passed earlier or
     * holds no test by {@code holdsTests} ({@link #runsAnything}). Each launch is still entered
     * through {@code launcher}, so the classes it leaves out count as replayed.
     */
    Starts starts(
            Path testClasses,
            SuiteFilter suites,
            List<String> classPatterns,
            List<String> excludeClasses,
            AffectedTestRun.@Nullable Outcome affected,
            Launcher launcher,
            Predicate<String> holdsTests,
            List<Path> runtimeCp) {
        List<SuiteFilter.Launch> launches = suites.launches();
        List<Start> out = new ArrayList<>();
        if (!split() && launches.size() == 1) {
            SuiteFilter.Launch only = launches.getFirst();
            JUnitLauncher one = launcher.of(ONE_LAUNCH, only.settings()).withSuites(suites);
            if (runsAnything(one, suites, testClasses, classPatterns, excludeClasses, holdsTests))
                out.add(new Start(one, runtimeCp, only));
            return new Starts(out);
        }
        for (Map.Entry<String, List<Path>> group : groups().entrySet()) {
            SuiteFilter part = split() ? suites.part(group.getKey()) : suites;
            if (part == null) continue;
            for (SuiteFilter.Launch shared : launches) {
                SuiteFilter unit = launches.size() == 1 ? part : part.only(shared.suites());
                if (unit == null) continue;
                List<String> names = null;
                if (affected != null) {
                    names = affected.classNames().stream()
                            .filter(unit.acceptor())
                            .toList();
                    if (names.isEmpty()) continue;
                } else if (!classPatterns.isEmpty()
                        && !unit.namesCompiledClass(testClasses, classPatterns, excludeClasses)) {
                    continue;
                } else if (!unit.runsCompiledClass(testClasses, excludeClasses)) {
                    continue;
                }
                String name = launchName(group.getKey(), shared);
                JUnitLauncher one = launcher.of(name, shared.settings()).withSuites(unit);
                if (!runsAnything(one, unit, testClasses, classPatterns, excludeClasses, holdsTests)) continue;
                if (names != null) one.withClassNames(names);
                List<Path> classpath = new ArrayList<>(group.getValue());
                classpath.addAll(runtimeCp);
                out.add(new Start(one, classpath, shared));
            }
        }
        return new Starts(out);
    }

    /** One launch that starts test JVMs: its launcher, its classpath after the test classes, its suites. */
    record Start(JUnitLauncher launcher, List<Path> classpath, SuiteFilter.Launch launch) {}

    /** The launches a run starts, in order; none when every launch was left out. */
    record Starts(List<Start> list) {

        Starts {
            list = List.copyOf(list);
        }

        /** Whether the run starts no test JVM. */
        boolean none() {
            return list.isEmpty();
        }

        /** Run each launch through {@code launch}, the results as one summary. */
        TestSummary run(Launch launch) throws Exception {
            TestSummary total = NOTHING_RAN;
            for (Start s : list) {
                TestSummary ran = launch.run(s.launcher(), s.classpath(), s.launch());
                total = total == NOTHING_RAN ? ran : total.plus(ran);
            }
            return total;
        }
    }

    /** The summary of a run that started no launch. */
    private static final TestSummary NOTHING_RAN = new TestSummary(0, 0, 0, 0, List.of());

    /**
     * Whether launch {@code one} of {@code unit} has a class left to run. It has unless it leaves
     * classes out ({@link JUnitLauncher#settled}) and each class {@code unit} runs is one of them,
     * nested in one, or in a top-level class {@code holdsTests} says holds no test: the JVMs of such
     * a launch would discover nothing. A run naming classes ({@code --class}) is the runner's to
     * judge.
     */
    private static boolean runsAnything(
            JUnitLauncher one,
            SuiteFilter unit,
            Path testClasses,
            List<String> classPatterns,
            List<String> excludeClasses,
            Predicate<String> holdsTests) {
        if (one.settled().isEmpty() || !classPatterns.isEmpty()) return true;
        Set<String> settled = Set.copyOf(one.settled());
        return unit.runsCompiledClass(testClasses, excludeClasses, name -> {
            String top = TestClassLedger.topLevel(name);
            return !settled.contains(top) && holdsTests.test(top);
        });
    }

    /**
     * The names {@link #starts} gives the launches a run of {@code suites} may start, before a
     * {@code --class} pattern or the affected classes leave one out: what a class's record names its
     * launch by, so the forecast reads only the records of the launches this selection starts.
     */
    Set<String> launchNames(SuiteFilter suites) {
        List<SuiteFilter.Launch> launches = suites.launches();
        if (!split() && launches.size() == 1) return Set.of(ONE_LAUNCH);
        Set<String> out = new TreeSet<>();
        for (String group : groups().keySet()) {
            SuiteFilter part = split() ? suites.part(group) : suites;
            if (part == null) continue;
            for (SuiteFilter.Launch shared : launches) {
                if (launches.size() == 1 || part.only(shared.suites()) != null) out.add(launchName(group, shared));
            }
        }
        return out;
    }

    /**
     * {@link #launchNames} of a run of {@code selection} in the module at {@code moduleDir}, whose
     * {@code [test.suites]} declare {@code declared}, as the run resolves and compiles its suites.
     */
    static Set<String> launchNames(
            Path moduleDir, boolean compact, TestSelection selection, Map<String, DeclaredSuite> declared) {
        var resolved = TestStampInputs.resolveSuites(selection, moduleDir, compact, declared);
        List<String> suites = resolved.ok() ? resolved.suites() : List.of(TestSuites.DEFAULT);
        List<String> compiled = TestSuites.compiled(moduleDir, compact, suites, declared);
        return of(moduleDir, compact, compiled, List.of())
                .launchNames(SuiteFilter.of(moduleDir, compact, suites, declared));
    }

    /** The directory suites a run launches apart, by name: one group of every class when it does not split. */
    private Map<String, List<Path>> groups() {
        return split() ? bySuite : Collections.singletonMap(TestSuites.DEFAULT, List.of());
    }

    /** A launch's name, stable under one run-tests stamp: its directory suite and the suites it runs. */
    private static String launchName(String group, SuiteFilter.Launch shared) {
        return group + ":" + String.join(",", new TreeSet<>(shared.suites()));
    }
}
