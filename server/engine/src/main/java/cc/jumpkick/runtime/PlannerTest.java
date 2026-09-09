// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerKsp.contributedProvidedFor;
import static cc.jumpkick.runtime.PlannerKsp.pluginTestClasspath;
import static cc.jumpkick.runtime.PlannerLang.compileGroovySources;
import static cc.jumpkick.runtime.PlannerLang.compileKotlinSources;
import static cc.jumpkick.runtime.PlannerSupport.copyResources;
import static cc.jumpkick.runtime.PlannerSupport.effectiveSelection;
import static cc.jumpkick.runtime.PlannerSupport.groovyCompileJar;
import static cc.jumpkick.runtime.PlannerSupport.groovyRuntime;
import static cc.jumpkick.runtime.PlannerSupport.kotlinStdlib;
import static cc.jumpkick.runtime.PlannerSupport.needsNestedEngineIsolation;
import static cc.jumpkick.runtime.PlannerSupport.nestedEngineTestEnv;
import static cc.jumpkick.runtime.PlannerSupport.testStampExtras;
import static cc.jumpkick.runtime.PlannerSupport.testStampWorkerJars;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.runtime.base.TestFailureSource;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.AffectedTestRun;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.TestProgressListener;
import cc.jumpkick.test.TestWorkers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * compile-test and run-tests steps, including the process-wide test gate.
 */
public final class PlannerTest {

    private PlannerTest() {}

    /** Where compile-test records the suite selection that produced {@code classes/test}. */
    private static final String SUITE_MARKER = ".jk-suites";

    static Task compileTestStep(BuildPlanner.Ctx cx, boolean hasFixtures) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        boolean compact = cx.compact();
        return Task.builder(TaskNames.COMPILE_TEST)
                .stage(BuildStage.TEST)
                .label("Test Compile")
                .kind(TaskKind.CPU)
                // AFTER_COMPILE scripts may generate types tests import. copy-resources is a real
                // input, not just ordering: the test classpath (and its action-key fingerprint)
                // includes classes/main, which copy-resources writes — racing it fingerprints a
                // half-copied dir and intermittently crashes on vanishing files under -r.
                .requires(
                        hasFixtures
                                ? new String[] {
                                    TaskNames.BUILD_LOGIC_AFTER_COMPILE,
                                    TaskNames.RESOLVE_DEPS,
                                    TaskNames.COPY_RESOURCES,
                                    TaskNames.COMPILE_TEST_FIXTURES
                                }
                                : new String[] {
                                    TaskNames.BUILD_LOGIC_AFTER_COMPILE,
                                    TaskNames.RESOLVE_DEPS,
                                    TaskNames.COPY_RESOURCES
                                })
                .weight(() -> plan.get().compileTest())
                .interpolated() // opaque javac/kotlinc call — ease it over time
                .ticks(1)
                .execute(ctx -> {
                    List<String> suiteNames = selectedSuites(ctx, in, compact);
                    if (suiteNames == null) return;
                    TestSources src = TestSources.collect(ctx, in, compact, suiteNames);
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
                    mergeLanguageOutputs(src, testClasses, ktTestOut, gvTestOut, mixedTest, mixedTestGv);
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
     * The selected suites' sources by language. {@code javaTestExtra} — [test] extra-src: roots in
     * the test tier that belong to no suite, shared helpers a sibling reaches through a {@code kind
     * = "tests"} edge. They compile with whichever suites were selected rather than being
     * selectable themselves, because there is nothing in them to run. Held separately from {@code
     * javaTest} (which also carries them) because javac is driven from the primary root plus an
     * explicit extra list, and that list is what {@code CompileRequest.sources} hashes — so these
     * roots land in the compile-test action key without a second key to keep in step.
     */
    record TestSources(
            Path javaTestSrc,
            List<Path> javaTest,
            List<Path> javaTestExtra,
            List<Path> ktTest,
            List<Path> gvTest,
            List<Path> scTest) {

        static TestSources collect(TaskContext ctx, BuildPlanner.Inputs in, boolean compact, List<String> suiteNames)
                throws Exception {
            Path javaTestSrc = TestSuites.primaryJavaRoot(in.dir(), compact, suiteNames);
            List<Path> javaTest = new ArrayList<>(TestSuites.collectJavaSources(in.dir(), compact, suiteNames));
            List<Path> javaTestExtra = TestSupport.testExtraSources(ctx.require(PROJECT), in.dir(), ".java");
            javaTest.addAll(javaTestExtra);
            return new TestSources(
                    javaTestSrc,
                    javaTest,
                    javaTestExtra,
                    TestSuites.collectKotlinSources(in.dir(), compact, suiteNames),
                    TestSuites.collectGroovySources(in.dir(), compact, suiteNames),
                    TestSuites.collectScalaSources(in.dir(), compact, suiteNames));
        }

        boolean isEmpty() {
            return javaTest.isEmpty() && ktTest.isEmpty() && gvTest.isEmpty() && scTest.isEmpty();
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
        String gvTaskId = ActionKey.qualifiedTaskId("compile-test-groovy", testClasses);
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
                ctx, in, cas, actionCache, src.gvTest(), baseCp, gvTestOut, gvTaskId, gvJavaRoots, null);
        if (!gr.success()) {
            PlannerSupport.forwardWorkerDiagnostics(
                    ctx, "groovyc", gr.diagnostics(), "groovyc failed without diagnostics");
            throw new RuntimeException("test groovyc reported errors");
        }
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
        String ktTaskId = ActionKey.qualifiedTaskId("compile-test-kotlin", testClasses);
        Path ktWorkingDir = ActionTree.INCREMENTAL_KOTLIN
                .under(CacheTree.ACTIONS.under(in.cache()))
                .resolve(ktTaskId);
        LangCompile.Result kr = compileKotlinSources(
                ctx,
                in,
                cas,
                actionCache,
                src.ktTest(),
                baseCp,
                ktTestOut,
                ktTaskId,
                ktWorkingDir,
                mixedTest ? List.of(src.javaTestSrc()) : null);
        if (!kr.success()) {
            PlannerSupport.forwardWorkerDiagnostics(
                    ctx, "kotlinc", kr.diagnostics(), "kotlinc failed without diagnostics");
            throw new RuntimeException("test kotlinc reported errors");
        }
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
        // Run the same declared annotation processors over test sources:
        // modern javac only honors processors named by -processorpath, so
        // without this a Lombok-using test wouldn't see its generated modules.
        List<Path> processorCp = ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations", "test");
        Files.createDirectories(genDir);
        ScalaCompile.Setup scalaSetup = null;
        if (!src.scTest().isEmpty()) {
            scalaSetup = ScalaCompile.prepare(ctx.require(PROJECT), ctx.require(LOCKFILE), cas);
            javaCp = new ArrayList<>(javaCp);
            for (Path lib : scalaSetup.libraryJars()) {
                if (!javaCp.contains(lib)) javaCp.add(lib);
            }
        }
        boolean ok = TestSupport.compileWithCache(
                ctx,
                TaskNames.COMPILE_TEST,
                src.javaTestSrc(),
                javaTestOut,
                javaCp,
                processorCp,
                ctx.require(RELEASE),
                javacArgs,
                ctx.require(JAVA_HOME),
                genDir,
                cas,
                in.cache(),
                CompileSupport.concatDistinct(src.scTest(), src.javaTestExtra()),
                scalaSetup);
        if (!ok) throw new RuntimeException("test compile failed");
    }

    /**
     * In mixed test mode, kotlin/groovy output needs to be merged into testClasses (java/test/).
     * Java test output already went there directly.
     */
    private static void mergeLanguageOutputs(
            TestSources src, Path testClasses, Path ktTestOut, Path gvTestOut, boolean mixedTest, boolean mixedTestGv)
            throws IOException {
        if (mixedTest && !src.ktTest().isEmpty()) {
            Files.createDirectories(testClasses);
            copyResources(ktTestOut, testClasses);
        }
        if (mixedTestGv && !src.gvTest().isEmpty()) {
            Files.createDirectories(testClasses);
            copyResources(gvTestOut, testClasses);
        }
    }

    /**
     * Test resources ride the test classpath next to compiled tests (Gradle's
     * processTestResources). Without this, getResourceAsStream fixtures NPE under self-host. Every
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
            BuildPlanner.Ctx cx, PluginBuild.@Nullable Declarations pluginDecls, List<String> extraRequires) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        List<String> testRequires = new ArrayList<>();
        testRequires.add(TaskNames.COMPILE_TEST);
        testRequires.add(TaskNames.COPY_RESOURCES);
        testRequires.addAll(extraRequires);
        if (pluginDecls != null) {
            for (PluginBuild.TaskDecl step : pluginDecls.steps()) {
                if (step.testOnly() || !step.contributesTestClasspath().isEmpty()) {
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
                    List<Path> testRtCp = testRuntimeClasspath(ctx, pluginDecls);
                    Path testClassesForStamp = ctx.require(TEST_CLASSES);
                    List<Path> testSrcs = ctx.get(TEST_SOURCES).orElse(List.of());
                    JkBuild projectUnderTest = ctx.require(PROJECT);
                    // Worker jars feed both the forked JVM and the TestStamp
                    // nested-engine CLI modules enrich with engine + every PluginJar so the stamp
                    // matches what the suite actually loads — same set forecast uses.
                    Map<String, String> workerJars = testStampWorkerJars(in.dir(), projectUnderTest);
                    Map<String, String> testEnv = testEnvironment(ctx, in, projectUnderTest);
                    List<Path> testResDirs = ctx.get(TEST_RESOURCE_DIRS).orElse(List.of());
                    // [test] exclude-tags for jk build / BSP: CLI resolves tags for `jk test`;
                    // when the session selection carries no tags at all, apply this module's
                    // own config here. The effective selection feeds BOTH the stamp and the runner.
                    var effectiveSel = effectiveSelection(in.session().testSelection(), in.dir());
                    AffectedTestRun.Outcome affected = affectedRun(ctx, in, effectiveSel);
                    if (affected != null && affected.classNames().isEmpty()) {
                        return; // nothing affected — no stamp store
                    }
                    List<String> extras = new ArrayList<>(testStampExtras(
                            workerJars, effectiveSel, projectUnderTest.build().testEnv(), in.dir()));
                    if (affected != null && !affected.stampToken().isBlank()) {
                        extras.add("affected:" + affected.stampToken());
                    }
                    String stampKey = stampKey(ctx, in, testSrcs, testResDirs, testRtCp, extras);
                    String testTaskId = ActionKey.qualifiedTaskId(TaskNames.RUN_TESTS, testClassesForStamp);
                    // --force forces a real test run, matching the compile/package
                    // freshness checks above (which all guard on !rerun). Without
                    // this guard the action record would skip the runner even when
                    // the user explicitly asked to bypass build caches.
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun && stampKey != null && replayGreenRun(ctx, actionCache, stampKey)) {
                        return; // skip — nothing changed since last green run
                    }
                    reweightForRealRun(ctx, in);
                    List<Path> runtimeCp = testRuntimeCpWithLanguageRuntimes(ctx, cx, cas, testRtCp, testSrcs);
                    // Module pin ([test] workers / [build] test-workers) wins over CLI for hermetic
                    // opt-out (Mill testParallelism = false). 0 = auto min(jobs, classes).
                    int testWorkers = dispatchWorkers(in, projectUnderTest.build());
                    String moduleLabel = projectUnderTest.project().group() + ":"
                            + projectUnderTest.project().name();
                    TestFailureSource.Cache snippets = new TestFailureSource.Cache();
                    TestProgressListener listener =
                            TestSupport.bridgeListener(ctx, testWorkers, in.verbose(), moduleLabel, in.dir(), snippets);
                    JUnitLauncher launcher = new JUnitLauncher()
                            .withModuleLabel(moduleLabel)
                            .withTagFilters(effectiveSel.includeTags(), effectiveSel.excludeTags())
                            // [test] serial-tags: those classes run on one trailing worker
                            // while the rest shard.
                            .withSerialTags(projectUnderTest.build().testSerialTags());
                    if (affected != null) launcher.withClassNames(affected.classNames());
                    TestSummary result =
                            launchGated(ctx, in, launcher, runtimeCp, testWorkers, workerJars, testEnv, listener);
                    ctx.put(TEST_RESULT, result);
                    recordOutcome(ctx, in, actionCache, testTaskId, stampKey, result, snippets);
                })
                .build();
    }

    /**
     * The resolved test runtime classpath plus own fixtures, plugin test-classpath contributions
     * (contributesTestClasspath — e.g. the android plugin's Robolectric test_config dir), and the
     * provided platform (android.jar) LAST: unit tests calling framework stubs get the platform's
     * throw-on-call contract (AGP's default posture), and anything real on the classpath shadows it.
     */
    private static List<Path> testRuntimeClasspath(TaskContext ctx, PluginBuild.@Nullable Declarations pluginDecls)
            throws Exception {
        List<Path> testRtCp = new ArrayList<>(ctx.require(TEST_RUNTIME_CP));
        testRtCp = PlannerFixtures.withOwnFixtures(ctx.require(PROJECT), ctx.require(LAYOUT), testRtCp);
        testRtCp.addAll(pluginTestClasspath(ctx.require(LAYOUT), pluginDecls));
        testRtCp.addAll(contributedProvidedFor(ctx));
        return testRtCp;
    }

    /**
     * Sandboxed JK_HOME/JK_M2_LOCAL plus this module's [test] env — without it a forked test JVM
     * inherits the engine's environment and runs against the developer's real product layout.
     * Nested-engine suites (jk-cli) isolate JK_STATE_DIR so EngineTestExtension cannot kill the
     * host engine running this test step; that isolation layers on top and wins on any key both set.
     */
    private static Map<String, String> testEnvironment(
            TaskContext ctx, BuildPlanner.Inputs in, JkBuild projectUnderTest) throws Exception {
        Map<String, String> testEnv =
                new LinkedHashMap<>(TestEnv.forModule(projectUnderTest, in.dir(), ctx.require(LAYOUT)));
        if (needsNestedEngineIsolation(projectUnderTest)) {
            testEnv.putAll(nestedEngineTestEnv(in.dir()));
        }
        return testEnv;
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
     * Incremental test skip: a content key over every input that affects the outcome — own main
     * output, test sources, the *content* of the runtime classpath (sibling modules included), the
     * lock, and the toolchain/runner/plugin identity. Unchanged → skip the runner. Null only when
     * computeKey failed open (unreadable input) — the caller treats that as "not cached".
     */
    private static @Nullable String stampKey(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            List<Path> testSrcs,
            List<Path> testResDirs,
            List<Path> testRtCp,
            List<String> extras)
            throws Exception {
        String stampKey =
                TestStamp.computeKey(testSrcs, ctx.require(MAIN_CLASSES), testResDirs, in.lockFile(), testRtCp, extras);
        if (Perf.ENABLED) {
            System.err.println("[jk-perf] live-test-stamp " + in.dir() + " key=" + stampKey
                    + " src=" + testSrcs.size() + " res=" + testResDirs.size()
                    + " rt=" + testRtCp.size() + " extras=" + extras.size() + " X=" + extras
                    + " cpFp=" + ClasspathFingerprint.of(testRtCp)
                    + " mainFp=" + ClasspathFingerprint.entry(ctx.require(MAIN_CLASSES)));
        }
        return stampKey;
    }

    /**
     * The "tests passed for this input" marker lives in the CAS (keyed by the content key), NOT in
     * target/ — so it survives {@code jk clean}: a later build that restores byte-identical classes
     * recomputes the same key and skips the runner, mirroring how the compile cache survives clean.
     * True when a green marker was found and the step marked itself skipped.
     */
    private static boolean replayGreenRun(TaskContext ctx, ActionCache actionCache, String stampKey)
            throws IOException {
        var marker = actionCache.lookup(stampKey);
        if (marker.isEmpty() || !TestStamp.green(marker.get())) return false;
        ctx.reweight(EffortWeights.TOKEN); // cache/stamp skip — token tick
        ctx.label("tests up-to-date");
        ctx.cached();
        // Replay the green run's counts (stored on the marker) so the summary
        // line reads "Passed N tests", not "No tests" — without this a
        // legitimate skip was indistinguishable from a module with no test
        // sources. Markers written before counts were stored replay nothing;
        // the next real run upgrades them.
        TestSummary previous = stampedSummary(marker.get());
        if (previous != null) ctx.put(TEST_RESULT, previous);
        return true;
    }

    /**
     * Tests are actually running: claim the real test slice, symmetric to how compile reweights
     * itself up. Without this a step the forecast under-sized (predicted SKIP, but the CAS marker
     * was missing so we run) stays pinned near-zero while the slow, serialized run executes — the
     * "stuck near 100% during tests" bug. Reweight through the SAME learned ledger predict used,
     * NOT the raw static per-method floor: the static constant is ~10× hot for a fast suite, so
     * reweighting to it ballooned the denominator (574 tests × 8 → ~11 min) the instant testing
     * began, then collapsed as the quick tests flew by — the wildly-jumping ETA. Learned == the
     * up-front estimate, so a correctly-forecast step reweights to the same value (a no-op) and
     * the countdown stays steady.
     */
    private static void reweightForRealRun(TaskContext ctx, BuildPlanner.Inputs in) {
        ctx.reweight(EffortWeights.learned(
                StepTimings.load(in.cache()),
                in.dir().toString(),
                TaskNames.RUN_TESTS,
                in.estimatedTestCount(),
                EffortWeights.runTestsWeight(in.estimatedTestCount()),
                in.projectModules().stream().map(Path::toString).toList()));
    }

    /**
     * classes/main, the test runtime classpath, and the language runtimes keyed on the SELECTED
     * suites' sources (TEST_SOURCES is selection-scoped) — the old default-suite-only collectors
     * missed a Kotlin/Groovy-only named suite and the forked JVM lacked the runtime.
     */
    private static List<Path> testRuntimeCpWithLanguageRuntimes(
            TaskContext ctx, BuildPlanner.Ctx cx, Cas cas, List<Path> testRtCp, List<Path> testSrcs) throws Exception {
        List<Path> runtimeCp = new ArrayList<>();
        runtimeCp.add(ctx.require(MAIN_CLASSES));
        runtimeCp.addAll(testRtCp);
        boolean ktTestSources = testSrcs.stream()
                .anyMatch(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".kts"));
        boolean gvTestSources = testSrcs.stream().anyMatch(p -> p.toString().endsWith(".groovy"));
        if (cx.kotlinModule() || ktTestSources) {
            runtimeCp.add(kotlinStdlib(ctx, cas));
        }
        if (cx.groovyModule() || gvTestSources) {
            for (Path jar : groovyRuntime(ctx, cas)) {
                if (!runtimeCp.contains(jar)) runtimeCp.add(jar);
            }
        }
        return runtimeCp;
    }

    /**
     * Test JVMs for this module, decided now rather than at plan time.
     *
     * <p>A module pin and an explicit {@code -w N} are both answers the caller already gave, so they
     * stand. Auto is the one case with something left to decide: the plan share was the jobs budget
     * divided by the graph's widest point, and by the time the last module's suite dispatches that
     * width is long gone. {@link TestWorkers#liveShare} re-reads it, and can only widen.
     */
    private static int dispatchWorkers(BuildPlanner.Inputs in, JkBuild.Build module) {
        int planned = module.effectiveTestWorkers(in.workerCount());
        boolean pinned = module.effectiveTestWorkers(0) > 0 || in.session().requestedTestWorkers() > 0;
        if (pinned) return planned;
        return TestWorkers.liveShare(planned, TestWorkers.effectiveJobs());
    }

    /**
     * Runs the suite, serializing test execution across concurrently-built units unless the user
     * opted into parallel tests — shared ports/locks/fixtures.
     */
    private static TestSummary launchGated(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JUnitLauncher launcher,
            List<Path> runtimeCp,
            int testWorkers,
            Map<String, String> workerJars,
            Map<String, String> testEnv,
            TestProgressListener listener)
            throws Exception {
        boolean gated = !in.session().parallelTests();
        if (gated) TEST_GATE.acquireUninterruptibly();
        try {
            return launcher.run(
                    ctx.require(JAVA_HOME),
                    ctx.require(TEST_CLASSES),
                    runtimeCp,
                    in.cache(),
                    testWorkers,
                    workerJars,
                    testEnv,
                    listener,
                    ctx.require(LAYOUT).testResultsDir());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.error("test", "interrupted");
            throw new RuntimeException(e);
        } catch (IOException e) {
            ctx.error("test", Errors.text(e));
            throw e;
        } finally {
            if (gated) TEST_GATE.release();
        }
    }

    /**
     * A red marker on failure: the next build re-runs (only a green marker skips), and the
     * forecast prices that re-run as a suite rather than as a drifted stamp — a module whose only
     * dirty step is its suite looks the same either way without it. Each failure (name + stack
     * trace) surfaces above the bar, not just the count — like Maven/Gradle.
     *
     * <p>On success a CAS marker keyed by the content key lets a later build / explain skip the
     * runner when inputs are unchanged. It lives in the CAS (not target/), so it survives {@code jk
     * clean}: after clean+build the compile cache restores byte-identical classes, the key
     * recomputes the same, and the marker is found. The green counts ride the record so the skip
     * path can replay them in its summary. Always stored on success — including --redo/--force.
     * Rerun only means "do not restore/skip the runner"; the marker still uses the normal content
     * key (not a verify scratch salt), so the next explain must see it (same contract as compile).
     * Skipped only when the key failed open.
     */
    private static void recordOutcome(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            ActionCache actionCache,
            String testTaskId,
            @Nullable String stampKey,
            TestSummary result,
            TestFailureSource.Cache snippets)
            throws Exception {
        if (!result.allPassed()) {
            if (stampKey != null) {
                actionCache.storeWithOutputs(
                        testTaskId,
                        stampKey,
                        Map.of(),
                        TestStamp.outcome(result.total(), result.succeeded(), result.skipped(), result.failed()));
            }
            for (String line : TestSupport.renderFailures(result, in.dir(), snippets)) ctx.output(line);
            if (SessionCancel.cancelled()) throw new RuntimeException("test run cancelled");
            throw new RuntimeException(result.failed() + " test failure" + (result.failed() == 1 ? "" : "s"));
        }
        if (stampKey != null) {
            actionCache.storeWithOutputs(
                    testTaskId,
                    stampKey,
                    Map.of(),
                    TestStamp.outcome(result.total(), result.succeeded(), result.skipped(), 0));
        }
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
