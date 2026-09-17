// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerLang.compileGroovySources;
import static cc.jumpkick.runtime.PlannerLang.compileKotlinSources;
import static cc.jumpkick.runtime.PlannerSupport.copyResources;
import static cc.jumpkick.runtime.PlannerSupport.effectiveSelection;
import static cc.jumpkick.runtime.PlannerSupport.groovyCompileJar;
import static cc.jumpkick.runtime.PlannerSupport.testStampExtras;
import static cc.jumpkick.runtime.PlannerSupport.testStampWorkerJars;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.LiveUnits;
import cc.jumpkick.runtime.base.TestFailureSource;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.AffectedTestRun;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.TestLauncherFailure;
import cc.jumpkick.test.TestProgressListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * compile-test and run-tests steps, including the process-wide test gate.
 */
public final class PlannerTest {

    private PlannerTest() {}

    /** Where compile-test records the suite selection that produced {@code classes/test}. */
    private static final String SUITE_MARKER = ".jk-suites";

    static Task compileTestStep(BuildPlanner.Ctx cx, boolean hasFixtures, @Nullable PluginDeclarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        boolean compact = cx.compact();
        // AFTER_COMPILE scripts may generate types tests import. copy-resources is a real
        // input, not just ordering: the test classpath (and its action-key fingerprint)
        // includes classes/main, which copy-resources writes — racing it fingerprints a
        // half-copied dir and intermittently crashes on vanishing files under -r.
        List<String> requires = new ArrayList<>(
                List.of(TaskNames.BUILD_LOGIC_AFTER_COMPILE, TaskNames.RESOLVE_DEPS, TaskNames.COPY_RESOURCES));
        if (hasFixtures) requires.add(TaskNames.COMPILE_TEST_FIXTURES);
        requires.addAll(testSourceGenSteps(pluginDecls));
        return Task.builder(TaskNames.COMPILE_TEST)
                .stage(BuildStage.TEST)
                .label("Test Compile")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .weight(() -> plan.get().compileTest())
                .interpolated() // opaque javac/kotlinc call — ease it over time
                .ticks(1)
                .execute(ctx -> {
                    List<String> suiteNames = selectedSuites(ctx, in, compact);
                    if (suiteNames == null) return;
                    TestSources src = TestSources.collect(
                            ctx.require(PROJECT), in.dir(), compact, suiteNames, ctx.require(LAYOUT), pluginDecls);
                    if (src.isEmpty()) {
                        ctx.label("no test sources");
                        ctx.put(NO_TEST_SOURCES, true);
                        warnDeclaredTestDepsButNoSources(ctx, ctx.require(PROJECT), in.dir());
                        ctx.cached(); // SKIPPED — nothing to compile
                        ctx.progress(1);
                        return;
                    }
                    // Store combined test sources for the TestStamp in run-tests.
                    ctx.put(TEST_SOURCES, src.all());
                    PlannerSetup.awaitSiblingTestOutputs(ctx, in);
                    List<Path> baseCp = testCompileClasspath(ctx, cx, cas, src);
                    Path testClasses = ctx.require(TEST_CLASSES);
                    String selectionKey = String.join(",", suiteNames);
                    resetOnSelectionChange(ctx, testClasses, selectionKey);
                    boolean mixedTest =
                            !src.javaTest().isEmpty() && !src.ktTest().isEmpty();
                    boolean mixedTestGv =
                            !src.javaTest().isEmpty() && !src.gvTest().isEmpty();
                    // In a mixed test module each language gets its own output dir, merged below.
                    Path gvTestOut = mixedTestGv ? ctx.require(LAYOUT).groovyTestClassesDir() : testClasses;
                    Path ktTestOut = mixedTest ? ctx.require(LAYOUT).kotlinTestClassesDir() : testClasses;
                    compileGroovyTests(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            src,
                            baseCp,
                            gvTestOut,
                            testClasses,
                            suiteNames,
                            compact,
                            mixedTestGv);
                    compileKotlinTests(ctx, in, cas, actionCache, src, baseCp, ktTestOut, testClasses, mixedTest);
                    compileJavaTests(
                            ctx, in, cas, src, baseCp, testClasses, ktTestOut, gvTestOut, mixedTest, mixedTestGv);
                    mergeLanguageOutputs(
                            src,
                            testClasses,
                            ktTestOut,
                            gvTestOut,
                            ctx.require(LAYOUT).buildDir(),
                            mixedTest,
                            mixedTestGv);
                    copySuiteResources(ctx, in, compact, suiteNames, testClasses);
                    Files.createDirectories(testClasses);
                    Files.writeString(testClasses.resolve(SUITE_MARKER), selectionKey);
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The suites this run compiles, or null after marking the step skipped. A workspace run names
     * a suite that need not exist in EVERY module — the IDE-generated {@code jk test --suite
     * integration} config must run where the suite exists and skip the rest, not fail the
     * workspace. Single-module runs keep the hard error (typo protection).
     */
    private static @Nullable List<String> selectedSuites(TaskContext ctx, BuildPlanner.Inputs in, boolean compact) {
        var sel = in.session() == null ? TestSelection.DEFAULT : in.session().testSelection();
        List<String> discovered = TestSuites.discover(in.dir(), compact);
        var resolved = sel.resolve(discovered);
        if (resolved.ok()) return resolved.suites();
        if (in.projectModules().size() > 1) {
            ctx.label("suite not present — skipped");
            ctx.put(NO_TEST_SOURCES, true);
            ctx.cached(); // SKIPPED — suite absent in this workspace module
            ctx.progress(1);
            return null;
        }
        throw new IllegalArgumentException(resolved.missingMessage());
    }

    /**
     * The selected suites' sources by language, derived once for the build and the forecast so the
     * compile-test key and the run-tests stamp hash the same files on both sides. {@code javaTest}
     * carries every selected {@code .java} — the suites' roots and {@code [test] extra-src}, roots in
     * the test tier that belong to no suite and compile with whichever suites were selected because
     * there is nothing in them to run. {@code javaTestSrc} is the primary suite root, which a mixed
     * Kotlin test compile reads Java from. Each list holds each path once.
     */
    record TestSources(Path javaTestSrc, List<Path> javaTest, List<Path> ktTest, List<Path> gvTest, List<Path> scTest) {

        static TestSources collect(
                JkBuild project,
                Path dir,
                boolean compact,
                List<String> suiteNames,
                BuildLayout layout,
                @Nullable PluginDeclarations decls)
                throws IOException {
            LinkedHashSet<Path> javaTest = new LinkedHashSet<>(TestSuites.collectJavaSources(dir, compact, suiteNames));
            javaTest.addAll(TestSupport.testExtraSources(project, dir, ".java"));
            javaTest.addAll(PlannerKsp.pluginContributedTestSources(layout, decls, ".java"));
            return new TestSources(
                    TestSuites.primaryJavaRoot(dir, compact, suiteNames),
                    List.copyOf(javaTest),
                    CompileSupport.concatDistinct(
                            TestSuites.collectKotlinSources(dir, compact, suiteNames),
                            PlannerKsp.pluginContributedTestSources(layout, decls, ".kt")),
                    CompileSupport.concatDistinct(
                            TestSuites.collectGroovySources(dir, compact, suiteNames),
                            PlannerKsp.pluginContributedTestSources(layout, decls, ".groovy")),
                    TestSuites.collectScalaSources(dir, compact, suiteNames));
        }

        boolean isEmpty() {
            return javaTest.isEmpty() && ktTest.isEmpty() && gvTest.isEmpty() && scTest.isEmpty();
        }

        /**
         * What compile-test hands javac: every selected Java source and, through the one Zinc
         * session, the Scala ones. This list is what the compile-test action key hashes.
         */
        List<Path> javacSources() {
            return CompileSupport.concatDistinct(javaTest, scTest);
        }

        /** Every selected source, for the TestStamp in run-tests. */
        List<Path> all() {
            List<Path> allTestSources = new ArrayList<>();
            allTestSources.addAll(javaTest);
            allTestSources.addAll(ktTest);
            allTestSources.addAll(gvTest);
            allTestSources.addAll(scTest);
            return allTestSources;
        }
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

    /** classes/main, own fixtures, the resolved test compile classpath, and the Groovy jar when needed. */
    private static List<Path> testCompileClasspath(TaskContext ctx, BuildPlanner.Ctx cx, Cas cas, TestSources src)
            throws Exception {
        List<Path> compileCp = ctx.require(COMPILE_TEST_CP);
        List<Path> baseCp = new ArrayList<>();
        baseCp.add(ctx.require(MAIN_CLASSES));
        baseCp = PlannerFixtures.withOwnFixtures(ctx.require(PROJECT), ctx.require(LAYOUT), baseCp);
        baseCp.addAll(compileCp);
        // A Groovy module's classes (main or test) implement groovy.lang.GroovyObject
        // javac (and groovyc itself) must resolve it from the version-matched jar.
        if (cx.groovyModule() || !src.gvTest().isEmpty()) {
            baseCp.add(groovyCompileJar(ctx, cas));
        }
        return baseCp;
    }

    /**
     * All suites share classes/test and run-tests scans it: when the SELECTION changes, wipe the
     * shared output and the per-language merge sources, or the previous selection's classes and
     * copied resources keep running/shadowing under the new one indefinitely. {@value #SUITE_MARKER}
     * records the selection that produced the tree (excluded from action stores/fingerprints by
     * the .jk- rule).
     */
    private static void resetOnSelectionChange(TaskContext ctx, Path testClasses, String selectionKey)
            throws IOException {
        Path suiteMarker = testClasses.resolve(SUITE_MARKER);
        String prevSelection =
                Files.isRegularFile(suiteMarker) ? Files.readString(suiteMarker).trim() : null;
        if (prevSelection != null && !prevSelection.equals(selectionKey)) {
            PathUtil.deleteRecursively(testClasses);
            for (Path langOut : List.of(
                    ctx.require(LAYOUT).kotlinTestClassesDir(),
                    ctx.require(LAYOUT).groovyTestClassesDir())) {
                if (Files.isDirectory(langOut)) {
                    PathUtil.deleteRecursively(langOut);
                }
            }
        }
    }

    /**
     * Groovy test sources first (joint mode sweeps the Java test roots for resolution), so Java
     * tests can reference Groovy test types.
     */
    private static void compileGroovyTests(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            ActionCache actionCache,
            TestSources src,
            List<Path> baseCp,
            Path gvTestOut,
            Path testClasses,
            List<String> suiteNames,
            boolean compact,
            boolean mixedTestGv)
            throws Exception {
        if (src.gvTest().isEmpty()) return;
        ctx.label("compiling " + src.gvTest().size() + " Groovy test sources");
        String gvTaskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_GROOVY, testClasses);
        List<Path> gvJavaRoots = null;
        if (mixedTestGv) {
            gvJavaRoots = new ArrayList<>();
            for (String suite : suiteNames) {
                for (Path root : TestSuites.javaRoots(in.dir(), compact, suite)) {
                    if (Files.isDirectory(root)) gvJavaRoots.add(root);
                }
            }
        }
        LangCompile.Result gr = compileGroovySources(
                ctx,
                in,
                actionCache,
                PlannerLang.groovyRequest(ctx, in, cas, src.gvTest(), baseCp, gvTestOut, gvJavaRoots, null),
                gvTaskId);
        if (!gr.success()) {
            PlannerSupport.forwardWorkerDiagnostics(
                    ctx, "groovyc", gr.diagnostics(), "groovyc failed without diagnostics");
            throw new RuntimeException("test groovyc reported errors");
        }
        ctx.put(COMPILE_TEST_GROOVY_ACTION_KEY, gr.actionKey());
    }

    /**
     * Kotlin test sources first, so Java tests can reference Kotlin test types (mirrors the main
     * mixed-module ordering).
     */
    private static void compileKotlinTests(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            ActionCache actionCache,
            TestSources src,
            List<Path> baseCp,
            Path ktTestOut,
            Path testClasses,
            boolean mixedTest)
            throws Exception {
        if (src.ktTest().isEmpty()) return;
        ctx.label("compiling " + src.ktTest().size() + " Kotlin test sources");
        String ktTaskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_KOTLIN, testClasses);
        Path ktWorkingDir = ActionTree.INCREMENTAL_KOTLIN
                .under(CacheTree.ACTIONS.under(in.cache()))
                .resolve(ktTaskId);
        List<Path> javaRoots = mixedTest ? List.of(src.javaTestSrc()) : null;
        PlannerLang.KotlinWorker worker = PlannerLang.kotlinWorker(
                ctx,
                in,
                cas,
                src.ktTest(),
                baseCp,
                ktTestOut,
                ktWorkingDir,
                PlannerLang.kotlinConfig(ctx, in.dir(), javaRoots));
        LangCompile.Result kr = compileKotlinSources(ctx, in, actionCache, ktTaskId, worker);
        if (!kr.success()) {
            PlannerSupport.forwardWorkerDiagnostics(
                    ctx, "kotlinc", kr.diagnostics(), "kotlinc failed without diagnostics");
            throw new RuntimeException("test kotlinc reported errors");
        }
        ctx.put(COMPILE_TEST_KOTLIN_ACTION_KEY, kr.actionKey());
    }

    /** Java/Scala test sources, against the Kotlin/Groovy test output in a mixed module. */
    private static void compileJavaTests(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            TestSources src,
            List<Path> baseCp,
            Path testClasses,
            Path ktTestOut,
            Path gvTestOut,
            boolean mixedTest,
            boolean mixedTestGv)
            throws Exception {
        if (src.javaTest().isEmpty() && src.scTest().isEmpty()) return;
        Path javaTestOut = testClasses; // javac always writes to java/test/
        List<Path> javaCp = baseCp;
        if (mixedTest || mixedTestGv) {
            javaCp = new ArrayList<>(baseCp);
            if (mixedTest) javaCp.add(ktTestOut);
            if (mixedTestGv) javaCp.add(gvTestOut);
        }
        List<String> javacArgs = ctx.require(JAVAC_ARGS);
        // The declared annotation processors run over test sources — the shared table's javac
        // half, then the test table's own; the request builder discovers classpath processors
        // when none are declared, exactly as compile-main does, so a Lombok-using test sees its
        // generated members.
        List<Path> shared = ctx.require(PROCESSOR_CP);
        List<Path> processorCp = ProcessorPaths.forTest(
                shared,
                ctx.get(JAVAC_PROCESSOR_CP).orElse(shared),
                ctx.get(TEST_PROCESSOR_CP).orElse(shared));
        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations", "test");
        Files.createDirectories(genDir);
        ScalaCompile.Setup scalaSetup =
                src.scTest().isEmpty() ? null : ScalaCompile.prepare(ctx.require(PROJECT), ctx.require(LOCKFILE), cas);
        boolean ok = TestSupport.compileWithCache(
                ctx,
                TaskNames.COMPILE_TEST,
                new PlannerCompile.TestCompile(
                        src.javacSources(),
                        javaCp,
                        processorCp,
                        javaTestOut,
                        ctx.require(PROJECT).build().javac().testRelease(ctx.require(RELEASE)),
                        javacArgs,
                        ctx.require(PROJECT).build().javac().forTests(),
                        ctx.require(JAVA_HOME),
                        scalaSetup,
                        ctx.require(MAIN_CLASSES)),
                genDir,
                cas,
                in.cache(),
                WorkerEnv.forModule(
                        ctx.require(PROJECT).build().env(),
                        in.dir(),
                        ctx.require(LAYOUT).moduleTargetDir()));
        if (!ok) throw new RuntimeException("test compile failed");
    }

    /**
     * In mixed test mode, kotlin/groovy output needs to be merged into testClasses (java/test/).
     * Java test output already went there directly.
     */
    private static void mergeLanguageOutputs(
            TestSources src,
            Path testClasses,
            Path ktTestOut,
            Path gvTestOut,
            Path buildDir,
            boolean mixedTest,
            boolean mixedTestGv)
            throws IOException {
        if (mixedTest && !src.ktTest().isEmpty()) {
            Files.createDirectories(testClasses);
            PlannerSupport.mergeLanguageOutput(ktTestOut, testClasses, buildDir, "kotlin-test");
        }
        if (mixedTestGv && !src.gvTest().isEmpty()) {
            Files.createDirectories(testClasses);
            PlannerSupport.mergeLanguageOutput(gvTestOut, testClasses, buildDir, "groovy-test");
        }
    }

    /**
     * Test resources ride the test classpath next to compiled tests, so getResourceAsStream
     * fixtures resolve. Every
     * suite in this run's selection is copied (default test/resources/ + e.g.
     * integration/resources/). Fixtures affect test outcomes but classes/test is not on the
     * runtime cp — run-tests folds these dirs into its TestStamp key.
     */
    private static void copySuiteResources(
            TaskContext ctx, BuildPlanner.Inputs in, boolean compact, List<String> suiteNames, Path testClasses)
            throws IOException {
        List<Path> suiteResDirs = ModuleLayout.suiteResourceDirs(in.dir(), compact, suiteNames);
        for (Path resTest : suiteResDirs) {
            Files.createDirectories(testClasses);
            copyResources(resTest, testClasses);
        }
        ctx.put(TEST_RESOURCE_DIRS, suiteResDirs);
    }

    static Task runTestsStep(
            BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls, List<String> extraRequires) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        List<String> testRequires = new ArrayList<>();
        testRequires.add(TaskNames.COMPILE_TEST);
        testRequires.add(TaskNames.COPY_RESOURCES);
        testRequires.addAll(extraRequires);
        if (pluginDecls != null) {
            for (TaskDecl step : pluginDecls.steps()) {
                if (step.testOnly() || step.feedsTests()) {
                    testRequires.add("plugin-" + step.name());
                }
            }
        }
        return Task.builder(TaskNames.RUN_TESTS)
                .stage(BuildStage.TEST)
                .label("Testing")
                .kind(TaskKind.IO)
                .requires(testRequires.toArray(new String[0]))
                .weight(() -> plan.get().runTests())
                .ticks(in.estimatedTestCount())
                .execute(ctx -> {
                    if (ctx.get(NO_TEST_SOURCES).orElse(false)) {
                        ctx.label("no tests to run");
                        return;
                    }
                    // The test runtime classpath is the siblings' jars: wait for them to be written.
                    PlannerSetup.awaitSiblingArtifacts(ctx, in);
                    List<Path> testRtCp = TestLaunch.testRuntimeClasspath(ctx, pluginDecls);
                    Path testClassesForStamp = ctx.require(TEST_CLASSES);
                    List<Path> testSrcs = ctx.get(TEST_SOURCES).orElse(List.of());
                    JkBuild projectUnderTest = ctx.require(PROJECT);
                    // Worker jars feed both the forked JVM and the TestStamp
                    // nested-engine CLI modules enrich with engine + every PluginJar so the stamp
                    // matches what the suite actually loads — same set forecast uses.
                    Map<String, String> workerJars = testStampWorkerJars(in.dir(), projectUnderTest);
                    WorkerEnv testEnv = TestLaunch.testEnvironment(ctx, in, projectUnderTest);
                    List<Path> testResDirs = ctx.get(TEST_RESOURCE_DIRS).orElse(List.of());
                    // [test] exclude-tags for jk build / BSP: CLI resolves tags for `jk test`;
                    // when the session selection carries no tags at all, apply this module's
                    // own config here. The effective selection feeds BOTH the stamp and the runner.
                    var effectiveSel = effectiveSelection(in.session().testSelection(), in.dir());
                    testEnv = TestLaunch.withHostWall(testEnv, effectiveSel);
                    AffectedTestRun.Outcome affected = affectedRun(ctx, in, effectiveSel);
                    if (affected != null && affected.classNames().isEmpty()) {
                        return; // nothing affected — no stamp store
                    }
                    List<String> testJvmArgs = TestLaunch.testJvmArgs(ctx, in, projectUnderTest, pluginDecls);
                    List<String> extras = new ArrayList<>(TestStamp.withCompileTest(
                            testStampExtras(
                                    workerJars,
                                    effectiveSel,
                                    projectUnderTest.build(),
                                    testJvmArgs,
                                    in.dir(),
                                    ClasspathFingerprint.ON_DISK),
                            compileTestKeys(ctx)));
                    if (affected != null && !affected.stampToken().isBlank()) {
                        extras.add("affected:" + affected.stampToken());
                    }
                    String stampKey = TestLaunch.stampKey(ctx, in, testSrcs, testResDirs, testRtCp, extras);
                    String testTaskId = ActionKey.qualifiedTaskId(TaskNames.RUN_TESTS, testClassesForStamp);
                    // --force forces a real test run, matching the compile/package
                    // freshness checks above (which all guard on !rerun). Without
                    // this guard the action record would skip the runner even when
                    // the user explicitly asked to bypass build caches.
                    // A debug request is a request for a JVM to attach to; a replayed green
                    // marker would leave the debugger with nothing to reach.
                    // A coverage run is a request for a report; a replayed green marker has none.
                    boolean coverage =
                            in.session().coverage() || projectUnderTest.build().testCoverage();
                    boolean rerun = in.session().config().rebuildOr(false)
                            || in.session().debugJvm() != null
                            || coverage;
                    if (!rerun && stampKey != null && TestLaunch.replayGreenRun(ctx, actionCache, stampKey)) {
                        return; // skip — nothing changed since last green run
                    }
                    TestLaunch.reweightForRealRun(ctx, in);
                    TestLaunch.noteJvmArgs(ctx, testJvmArgs);
                    List<Path> runtimeCp =
                            TestLaunch.testRuntimeCpWithLanguageRuntimes(ctx, cx, cas, testRtCp, testSrcs);
                    String moduleLabel = projectUnderTest.project().group() + ":"
                            + projectUnderTest.project().name();
                    TestFailureSource.Cache snippets = new TestFailureSource.Cache();
                    // Test execution is serialized across concurrently-built units unless the
                    // user opted into parallel tests — shared ports/locks/fixtures. The worker
                    // count is decided once the gate is held: a suite parked at the gate is
                    // not running, and the ones running when it starts are what it shares with.
                    boolean gated = !in.session().parallelTests();
                    Path coverageExec = coverage ? TestLaunch.coverageExecFile(ctx) : null;
                    // The agent and the report tool come first: a coverage run that cannot fetch
                    // JaCoCo fails before any suite starts, not after the suite ran uninstrumented.
                    CoverageTools.Jacoco jacoco = resolveBeforeGate(
                            coverage ? () -> CoverageTools.resolve(projectUnderTest, cas) : null,
                            gated,
                            PlannerTest::awaitTestGate);
                    TestSummary result;
                    try {
                        // Module pin ([test] workers / [build] test-workers) wins over CLI for
                        // hermetic opt-out (Mill testParallelism = false). 0 = auto min(jobs, classes).
                        // One debugger attaches to one JVM: a debug run is a one-worker run.
                        int testWorkers = in.session().debugJvm() != null
                                ? 1
                                : TestLaunch.dispatchWorkers(in, projectUnderTest.build());
                        TestProgressListener listener = TestSupport.bridgeListener(
                                ctx, testWorkers, in.verbose(), moduleLabel, in.dir(), snippets);
                        JUnitLauncher launcher = TestLaunch.launcher(
                                in, projectUnderTest, effectiveSel, testJvmArgs, affected, jacoco, coverageExec);
                        try {
                            result = TestLaunch.launch(
                                    ctx, in, launcher, runtimeCp, testWorkers, workerJars, testEnv, listener);
                        } catch (TestLauncherFailure e) {
                            TestLaunch.reportLauncherFailure(
                                    ctx, in, projectUnderTest, actionCache, testTaskId, stampKey, e);
                            throw e;
                        }
                    } finally {
                        if (gated) TEST_GATE.release();
                    }
                    if (jacoco != null && coverageExec != null) {
                        TestLaunch.writeCoverageReport(ctx, in, jacoco, coverageExec, moduleLabel);
                    }
                    if (TestClassMatch.nothingMatched(effectiveSel, affected != null, result)) {
                        // A workspace judges the patterns across its modules; this one skips.
                        if (in.projectModules().size() > 1) {
                            ctx.label(TestClassMatch.skipLabel(effectiveSel.classes()));
                            ctx.cached();
                            return;
                        }
                        result = TestClassMatch.asFailure(moduleLabel, effectiveSel);
                    }
                    TagExcludedSuite.note(ctx, effectiveSel, in.profileName(), affected != null, result);
                    ctx.put(TEST_RESULT, result);
                    TestLaunch.recordOutcome(
                            ctx, in, actionCache, testTaskId, stampKey, result, !testSrcs.isEmpty(), snippets);
                })
                .build();
    }

    /** The affected-test selection when the session asked for one; null otherwise. */
    private static AffectedTestRun.@Nullable Outcome affectedRun(
            TaskContext ctx, BuildPlanner.Inputs in, TestSelection effectiveSel) throws Exception {
        if (!in.session().affected()) return null;
        try {
            return AffectedTestRun.apply(ctx, in, effectiveSel);
        } catch (AffectedTestRun.RankingRefused e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * The test compiles' action keys compile-test published, one per language it compiled tests
     * in. Each is an input to the suite's outcome — the compiler options, plugins and target that
     * shaped the test classes — and nothing else in the stamp reads them.
     */
    static TestStamp.CompileTestKeys compileTestKeys(TaskContext ctx) {
        return new TestStamp.CompileTestKeys(
                ctx.get(COMPILE_TEST_ACTION_KEY).orElse(null),
                ctx.get(COMPILE_TEST_KOTLIN_ACTION_KEY).orElse(null),
                ctx.get(COMPILE_TEST_GROOVY_ACTION_KEY).orElse(null));
    }

    /**
     * Coverage tooling resolves before the serial test gate is taken, never inside it. The fetch
     * can take seconds or fail outright, and the gate is process-wide: a fetch behind it would park
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

    /**
     * Wait for the serial-test gate without counting as a running unit: a suite parked here is
     * sharing the machine with nobody yet, and counting it would hand every gated suite a fraction
     * of the machine it then uses alone.
     */
    static void awaitTestGate() {
        try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
            TEST_GATE.acquireUninterruptibly();
        }
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
     * sources has nothing to run, and its empty run is the whole truth.
     */
    static Stamp stampFor(TestSummary result, boolean testSourcesExist) {
        if (!result.allPassed()) return Stamp.NONE;
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

    /**
     * Warn when a module declares {@code [test-dependencies]} but has no test source files.
     * Raised via {@link TaskContext#warn} so it is attributed to this module/step in results.
     */
    private static void warnDeclaredTestDepsButNoSources(TaskContext ctx, JkBuild project, Path dir) {
        if (project == null) return;
        if (project.dependencies().of(Scope.TEST).isEmpty()
                && project.dependencies().of(Scope.TEST_DEV).isEmpty()) {
            return;
        }
        ctx.warn("no-test-sources", dir + " declares [test-dependencies] but has no test source files");
    }
}
