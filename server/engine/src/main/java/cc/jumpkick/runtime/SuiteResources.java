// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;

import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.FilteredResources;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.CompiledTests;
import cc.jumpkick.task.MirroredOutputs;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.AffectedTestRun;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.SuiteClassFilter;
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
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Each directory suite's own resource roots, when a run compiles more than one directory suite into
 * the shared test-classes tree. Copied into that one tree, a resource two suites ship at the same
 * path (a {@code META-INF/services} file, a {@code junit-platform.properties}) would be whichever
 * copy came last, and every suite would read it. So such a run copies no suite's resources into the
 * tree: each suite runs in its own JVMs with its own roots ahead of the shared classpath, as it
 * would run alone. The default suite's roots carry the class-pattern suites' and its configured
 * extra roots. Empty when the run compiles one directory suite, whose resources the tree holds.
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
    static SuiteResources of(
            Path moduleDir,
            boolean compact,
            List<String> compiled,
            Set<String> patternSuites,
            List<Path> defaultExtra) {
        List<String> directory =
                compiled.stream().filter(s -> !patternSuites.contains(s)).toList();
        if (directory.size() < 2) return NONE;
        Map<String, List<Path>> out = new LinkedHashMap<>();
        for (String suite : directory) {
            out.put(suite, new ArrayList<>(ModuleLayout.suiteResourceDirs(moduleDir, compact, List.of(suite))));
        }
        List<Path> withDefault = out.computeIfAbsent(TestSuites.DEFAULT, k -> new ArrayList<>());
        for (String suite : compiled) {
            if (patternSuites.contains(suite)) {
                withDefault.addAll(ModuleLayout.suiteResourceDirs(moduleDir, compact, List.of(suite)));
            }
        }
        withDefault.addAll(defaultExtra);
        return new SuiteResources(out);
    }

    /**
     * Test resources ride the test classpath next to compiled tests, so getResourceAsStream
     * fixtures resolve: the test KSP round's output and, when the run compiles one directory suite,
     * that suite's resources (plus the class-pattern suites' and its configured extra roots) are
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
        List<Path> kspRoots = PlannerKsp.outputRoots(PlannerKsp.kspTestOutBase(ctx.require(LAYOUT)));
        List<Path> extraRoots = new ArrayList<>();
        if (suiteNames.contains(TestSuites.DEFAULT)) {
            Set<String> unresolved = new TreeSet<>();
            extraRoots.addAll(
                    FilteredResources.testRoots(in.dir(), ctx.require(PROJECT), ctx.require(LAYOUT), unresolved));
            PlannerResources.warnUnresolved(ctx, unresolved);
        }
        SuiteResources apart = SuiteResources.of(
                in.dir(),
                compact,
                suiteNames,
                ctx.require(PROJECT).build().testClassSuites().keySet(),
                extraRoots);
        List<Path> suiteResDirs = new ArrayList<>(kspRoots);
        suiteResDirs.addAll(ModuleLayout.suiteResourceDirs(in.dir(), compact, suiteNames));
        suiteResDirs.addAll(extraRoots);
        Files.createDirectories(testClasses);
        // adoptUnowned: a test-resource copy that predates the ledger left files this mirror
        // must still remove. A file the compile just wrote is protected; main classes already
        // carry a ledger, so they do not adopt.
        Path ledger = ctx.require(LAYOUT).buildDir().resolve("incremental").resolve(ResourceMirror.TEST_LEDGER);
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

    /** Whether the suites run apart, each with its own roots, rather than over the tree's copy. */
    boolean split() {
        return !bySuite.isEmpty();
    }

    /** One test JVM run: a launcher over the shared classpath with {@code classpath} after the test classes. */
    @FunctionalInterface
    interface Launch {
        TestSummary run(JUnitLauncher launcher, List<Path> classpath) throws Exception;
    }

    /**
     * Run the selection: in one launch over {@code runtimeCp} when the tree holds the resources,
     * else one launch per directory suite, its roots first, its classes alone, the results as one
     * summary. A part the {@code --class} patterns or the affected classes name nothing in is not
     * started.
     */
    TestSummary launch(
            Path testClasses,
            SuiteClassFilter suites,
            List<String> classPatterns,
            List<String> excludeClasses,
            AffectedTestRun.@Nullable Outcome affected,
            Supplier<JUnitLauncher> launcher,
            List<Path> runtimeCp,
            Launch launch)
            throws Exception {
        if (!split()) return launch.run(launcher.get().withSuites(suites), runtimeCp);
        TestSummary total = null;
        for (Map.Entry<String, List<Path>> suite : bySuite.entrySet()) {
            SuiteClassFilter part = suites.part(suite.getKey());
            if (part == null) continue;
            JUnitLauncher one = launcher.get().withSuites(part);
            if (affected != null) {
                List<String> names =
                        affected.classNames().stream().filter(part.acceptor()).toList();
                if (names.isEmpty()) continue;
                one.withClassNames(names);
            } else if (!classPatterns.isEmpty()
                    && !part.namesCompiledClass(testClasses, classPatterns, excludeClasses)) {
                continue;
            }
            List<Path> classpath = new ArrayList<>(suite.getValue());
            classpath.addAll(runtimeCp);
            TestSummary ran = launch.run(one, classpath);
            total = total == null ? ran : JUnitLauncher.merge(total, ran);
        }
        return total != null ? total : new TestSummary(0, 0, 0, 0, List.of());
    }
}
