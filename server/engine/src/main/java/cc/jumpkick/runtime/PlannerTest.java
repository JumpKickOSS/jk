// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerLang.compileGroovySources;
import static cc.jumpkick.runtime.PlannerLang.compileKotlinSources;
import static cc.jumpkick.runtime.PlannerSupport.effectiveSelection;
import static cc.jumpkick.runtime.PlannerSupport.groovyCompileJar;
import static cc.jumpkick.runtime.PlannerSupport.testStampWorkerJars;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.TierTags;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.ClassSuite;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.LiveUnits;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.runtime.base.TestFailureSource;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.task.WorkerClosure;
import cc.jumpkick.test.AffectedTestRun;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.SuiteClassFilter;
import cc.jumpkick.test.TestLauncherFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * compile-test and run-tests steps, including the process-wide test gate.
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

    static Task compileTestStep(BuildPlanner.Ctx cx, boolean hasFixtures, @Nullable PluginDeclarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        boolean compact = cx.compact();
        // AFTER_COMPILE scripts may generate types tests import. copy-resources is a real
        // input, not just ordering: the test classpath (and its action-key fingerprint)
        // includes target/classes, which copy-resources writes — racing it fingerprints a
        // half-copied dir and intermittently crashes on vanishing files under -r.
        List<String> requires = new ArrayList<>(
                List.of(TaskNames.BUILD_LOGIC_AFTER_COMPILE, TaskNames.RESOLVE_DEPS, TaskNames.COPY_RESOURCES));
        if (hasFixtures) requires.add(TaskNames.COMPILE_TEST_FIXTURES);
        requires.addAll(testSourceGenSteps(pluginDecls));
        return Task.builder(TaskNames.COMPILE_TEST)
                .forecast(StepForecast.priced(s -> PlannerTestForecast.compileTest(ForecastScope.of(s))))
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
                    JkBuild project = ctx.require(PROJECT);
                    TestSources src = TestSources.collect(
                            project, in.dir(), compact, suiteNames, ctx.require(LAYOUT), pluginDecls);
                    // A class-pattern suite compiles the default suite's roots: the suites whose
                    // directories this compile reads, which is what the shared output records.
                    List<String> compiledSuites = TestSuites.compiled(
                            in.dir(), compact, suiteNames, project.build().testClassSuites());
                    if (src.isEmpty()) {
                        ctx.label("no test sources");
                        ctx.put(NO_TEST_SOURCES, true);
                        warnDeclaredTestDepsButNoSources(ctx, project, in.dir());
                        ctx.cached(); // SKIPPED — nothing to compile
                        ctx.progress(1);
                        return;
                    }
                    PlannerSetup.awaitSiblingTestCompile(ctx, in);
                    List<Path> baseCp = testCompileClasspath(ctx, cx, cas, src);
                    PlannerKsp.RoundOutcome round =
                            PlannerKsp.runTestRound(ctx, cx, src, baseCp, compiledSuites, pluginDecls);
                    if (round != PlannerKsp.RoundOutcome.KEPT) {
                        src = TestSources.collect( // the round rewrote its generated sources
                                project, in.dir(), compact, suiteNames, ctx.require(LAYOUT), pluginDecls);
                    }
                    // Store combined test sources for the TestStamp in run-tests.
                    ctx.put(TEST_SOURCES, src.all());
                    Path testClasses = ctx.require(TEST_CLASSES);
                    String selectionKey = String.join(",", compiledSuites);
                    resetOnSelectionChange(ctx, testClasses, selectionKey);
                    // Identity before the compilers run. Adoption may remove a copy that predates
                    // the ledger, and must not remove a file this compile rewrote.
                    Map<String, String> beforeNonClass = ResourceMirror.nonClassIdentity(testClasses);
                    Path testLedger = ctx.require(LAYOUT)
                            .buildDir()
                            .resolve("incremental")
                            .resolve(ResourceMirror.TEST_LEDGER);
                    if (!Files.isRegularFile(testLedger)) forgetTestCompileState(in.cache(), testClasses);
                    TestOutputs outs = TestOutputs.of(src, ctx.require(LAYOUT), testClasses);
                    boolean javaTests = !src.javaTest().isEmpty();
                    // The stamps' instant: before any test compiler reads its first source.
                    long readClock = FreshnessStamp.clockNow(testClasses);
                    // Kotlin first, then Groovy against its output (Gradle's order), then Java against
                    // both. The step did no work when the round and every compiler restored.
                    boolean restored = round != PlannerKsp.RoundOutcome.RAN;
                    restored &= compileKotlinTests(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            src,
                            baseCp,
                            outs.kotlinOut(),
                            testClasses,
                            outs.mixedKotlin() && javaTests);
                    restored &= compileGroovyTests(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            src,
                            outs.groovyClasspath(baseCp),
                            outs.groovyOut(),
                            testClasses,
                            compiledSuites,
                            compact,
                            outs.mixedGroovy() && javaTests);
                    restored &= compileJavaTests(ctx, in, cas, src, outs.javacClasspath(baseCp), testClasses);
                    mergeLanguageOutputs(src, outs, ctx.require(LAYOUT).buildDir());
                    SuiteResources.mirror(
                            ctx,
                            in,
                            actionCache,
                            compact,
                            compiledSuites,
                            testClasses,
                            beforeNonClass,
                            outs.mixedKotlin(),
                            outs.mixedGroovy());
                    Files.createDirectories(testClasses);
                    Files.writeString(suiteMarker(testClasses), selectionKey);
                    TestClassStamps.write(
                            ctx, src.javacSources(), src.ktTest(), src.gvTest(), baseCp, testClasses, readClock);
                    if (restored) ctx.cached(); // SKIPPED — every compiler restored its record
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The suites this run compiles, or null after marking the step skipped. A workspace run names
     * a suite that need not exist in EVERY module — the IDE-generated {@code jk test -s
     * integration} config must run where the suite exists and skip the rest, not fail the
     * workspace. Single-module runs keep the hard error (typo protection).
     */
    private static @Nullable List<String> selectedSuites(TaskContext ctx, BuildPlanner.Inputs in, boolean compact) {
        var resolved = resolveSuites(in, compact, ctx.require(PROJECT));
        if (resolved.ok()) return resolved.suites();
        if (in.projectModules().size() > 1) {
            ctx.label("skipped: suite not in this module");
            ctx.put(NO_TEST_SOURCES, true);
            ctx.cached(); // SKIPPED — suite absent in this workspace module
            ctx.progress(1);
            return null;
        }
        throw new IllegalArgumentException(resolved.missingMessage());
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

    /** target/classes, own fixtures, the resolved test compile classpath, and the Groovy jar when needed. */
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
     * All suites share target/test-classes and run-tests scans it: when the SELECTION changes, wipe the
     * shared output and the per-language merge sources, or the previous selection's classes and
     * copied resources keep running/shadowing under the new one indefinitely. The {@link
     * #suiteMarker} beside the tree records the selection that produced it.
     */
    private static void resetOnSelectionChange(TaskContext ctx, Path testClasses, String selectionKey)
            throws IOException {
        Path suiteMarker = suiteMarker(testClasses);
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
     * Groovy test sources before Java's (joint mode sweeps the Java test roots for resolution), so
     * Java tests can reference Groovy test types. {@code jointWithJava} sweeps the Java roots. True
     * when groovyc restored its record or had nothing to compile.
     */
    private static boolean compileGroovyTests(
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
            boolean jointWithJava)
            throws Exception {
        if (src.gvTest().isEmpty()) return true;
        ctx.label("compiling " + src.gvTest().size() + " Groovy test sources");
        String gvTaskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_GROOVY, testClasses);
        List<Path> gvJavaRoots = null;
        if (jointWithJava) {
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
        return gr.cacheHit();
    }

    /**
     * Kotlin test sources first, so Groovy and Java tests can reference Kotlin test types (mirrors
     * the main ordering). {@code readsJava} has kotlinc read the Java test sources' declarations.
     * True when kotlinc restored its record or had nothing to compile.
     */
    private static boolean compileKotlinTests(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            ActionCache actionCache,
            TestSources src,
            List<Path> baseCp,
            Path ktTestOut,
            Path testClasses,
            boolean readsJava)
            throws Exception {
        if (src.ktTest().isEmpty()) return true;
        ctx.label("compiling " + src.ktTest().size() + " Kotlin test sources");
        String ktTaskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_KOTLIN, testClasses);
        Path ktWorkingDir = ActionKey.stateDir(
                ActionTree.INCREMENTAL_KOTLIN.under(CacheTree.ACTIONS.under(in.cache())),
                TaskNames.COMPILE_TEST_KOTLIN,
                testClasses);
        List<Path> javaRoots = readsJava ? List.of(src.javaTestSrc()) : null;
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
                    ctx,
                    "kotlinc",
                    PackageProviders.enrich(ctx, baseCp, ClasspathResolver.COMPILE_TEST, kr.diagnostics()),
                    "kotlinc failed without diagnostics");
            throw new RuntimeException("test kotlinc reported errors");
        }
        ctx.put(COMPILE_TEST_KOTLIN_ACTION_KEY, kr.actionKey());
        return kr.cacheHit();
    }

    /**
     * Java/Scala test sources over {@code javaCp} ({@link TestOutputs#javacClasspath}). True when
     * javac restored its record or had nothing to compile.
     */
    private static boolean compileJavaTests(
            TaskContext ctx, BuildPlanner.Inputs in, Cas cas, TestSources src, List<Path> javaCp, Path testClasses)
            throws Exception {
        if (src.javaTest().isEmpty() && src.scTest().isEmpty()) return true;
        Path javaTestOut = testClasses; // javac always writes to java/test/
        List<String> javacArgs = ctx.require(JAVAC_ARGS);
        // The declared annotation processors run over test sources — the shared table's javac
        // half, then the test table's own, or that table alone under its inherit = false; the
        // request builder discovers classpath processors
        // when none are declared, exactly as compile-main does, so a Lombok-using test sees its
        // generated members.
        List<Path> shared = ctx.require(PROCESSOR_CP);
        List<Path> processorCp = ProcessorPaths.forTest(
                ctx.require(PROJECT),
                shared,
                ctx.get(JAVAC_PROCESSOR_CP).orElse(shared),
                ctx.get(TEST_PROCESSOR_CP).orElse(shared));
        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations", "test");
        Files.createDirectories(genDir);
        ScalaCompile.Setup scalaSetup =
                src.scTest().isEmpty() ? null : ScalaCompile.prepare(ctx.require(PROJECT), ctx.require(LOCKFILE), cas);
        TestSupport.Compiled compiled = TestSupport.compileWithCache(
                ctx,
                TaskNames.COMPILE_TEST,
                new PlannerCompile.TestCompile(
                        src.javacSources(),
                        javaCp,
                        processorCp,
                        ctx.require(CONTRIBUTED_TEST_PROCESSOR_CP),
                        javaTestOut,
                        ctx.require(PROJECT).javac().testRelease(ctx.require(RELEASE)),
                        javacArgs,
                        ctx.require(PROJECT).javac().forTests(),
                        ctx.require(JAVA_HOME),
                        scalaSetup,
                        ctx.require(MAIN_CLASSES),
                        ProcessorPaths.discovers(ctx.require(PROJECT))),
                genDir,
                cas,
                in.cache(),
                WorkerEnv.forModule(
                        ctx.require(PROJECT).build().env(),
                        in.dir(),
                        ctx.require(LAYOUT).moduleTargetDir()));
        if (!compiled.ok()) throw new RuntimeException("test compile failed");
        return compiled == TestSupport.Compiled.RESTORED;
    }

    /**
     * In mixed test mode, kotlin/groovy output needs to be merged into testClasses (java/test/).
     * Java test output already went there directly.
     */
    private static void mergeLanguageOutputs(TestSources src, TestOutputs outs, Path buildDir) throws IOException {
        Path testClasses = outs.testClasses();
        if (outs.mixedKotlin() && !src.ktTest().isEmpty()) {
            Files.createDirectories(testClasses);
            PlannerSupport.mergeLanguageOutput(outs.kotlinOut(), testClasses, buildDir, "kotlin-test");
        }
        if (outs.mixedGroovy() && !src.gvTest().isEmpty()) {
            Files.createDirectories(testClasses);
            PlannerSupport.mergeLanguageOutput(outs.groovyOut(), testClasses, buildDir, "groovy-test");
        }
    }

    /**
     * No test-resource ledger yet, so this compile has to be whole: an incremental one can leave a
     * generated resource untouched, and adoption would then remove it with the stale copies.
     */
    private static void forgetTestCompileState(Path cache, Path testClasses) throws IOException {
        Path actions = CacheTree.ACTIONS.under(cache);
        Path javaState =
                ActionKey.stateDir(ActionTree.INCREMENTAL_JAVA.under(actions), TaskNames.COMPILE_TEST, testClasses);
        Path kotlinState = ActionKey.stateDir(
                ActionTree.INCREMENTAL_KOTLIN.under(actions), TaskNames.COMPILE_TEST_KOTLIN, testClasses);
        for (Path state : List.of(javaState, kotlinState)) {
            if (Files.isDirectory(state)) PathUtil.deleteRecursively(state);
        }
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

    static Task runTestsStep(
            BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls, List<String> extraRequires) {
        BuildPlanner.Inputs in = cx.in();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        List<String> testRequires = runTestsRequires(pluginDecls, extraRequires);
        return Task.builder(TaskNames.RUN_TESTS)
                .forecast(StepForecast.priced(s -> PlannerTestForecast.runTests(ForecastScope.of(s))))
                .stage(BuildStage.TEST)
                .label("Testing")
                .kind(TaskKind.IO)
                .requires(testRequires.toArray(new String[0]))
                .weight(() -> plan.get().runTests())
                .ticks(in.estimatedTestCount())
                .execute(ctx -> runTests(ctx, cx, pluginDecls))
                .build();
    }

    /** Whether {@code moduleDir}'s suite runs: always, unless {@code --exclusive} left it out of the selection. */
    static boolean selectedForTests(TestSelection selection, Path moduleDir) {
        if (!selection.exclusive()) return true;
        Path dir = BuildGraph.canonicalPath(moduleDir);
        return selection.onlyModules().stream()
                .map(Path::of)
                .map(BuildGraph::canonicalPath)
                .anyMatch(dir::equals);
    }

    /** Run one module's suite, or replay its last green run when nothing it reads has changed. */
    private static void runTests(TaskContext ctx, BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls)
            throws Exception {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        if (!selectedForTests(in.session().testSelection(), in.dir())) {
            ctx.label(TaskNames.TESTS_NOT_SELECTED);
            ctx.cached();
            return;
        }
        if (ctx.get(NO_TEST_SOURCES).orElse(false)) {
            ctx.label("no tests to run");
            return;
        }
        Path testClasses = ctx.require(TEST_CLASSES);
        List<Path> testSrcs = ctx.get(TEST_SOURCES).orElse(List.of());
        JkBuild projectUnderTest = ctx.require(PROJECT);
        String moduleLabel = projectUnderTest.project().group() + ":"
                + projectUnderTest.project().name();
        String testTaskId = ActionKey.qualifiedTaskId(TaskNames.RUN_TESTS, testClasses);
        TestFailureSource.Cache snippets = new TestFailureSource.Cache();
        // jk build / BSP / MCP: when the session selection carries no tags, resolve the
        // workspace's here as the CLI does for `jk test`. It feeds BOTH the stamp and the runner.
        var effectiveSel = effectiveSelection(in.session().testSelection(), in.dir(), in.profileName());
        SuiteClassFilter suites = suiteFilter(in, cx.compact(), projectUnderTest);
        AffectedTestRun.Outcome affected = affectedRun(ctx, in, effectiveSel, suites);
        if (affected != null && affected.classNames().isEmpty()) {
            return; // nothing affected — no stamp store
        }
        // A --class selection that names no compiled class of this module is judged
        // from the class files, before the siblings' jars, the stamp and a test JVM:
        // in a workspace most modules do not hold the class the user named.
        if (affected == null
                && !effectiveSel.classes().isEmpty()
                && !suites.namesCompiledClass(
                        testClasses,
                        effectiveSel.classes(),
                        projectUnderTest.build().testExcludeClasses())) {
            settleUnmatched(ctx, in, effectiveSel, suites, actionCache, testTaskId, !testSrcs.isEmpty(), snippets);
            return;
        }
        // The test runtime classpath is the siblings' jars: wait for them to be written.
        PlannerSetup.awaitSiblingTestRuntime(ctx, in);
        List<Path> testRtCp = TestLaunch.testRuntimeClasspath(ctx, pluginDecls);
        // Worker jars feed both the forked JVM and the TestStamp
        // nested-engine CLI modules enrich with engine + every PluginJar so the stamp
        // matches what the suite actually loads — same set forecast uses.
        Map<String, String> workerJars =
                PlannerSetup.awaitWorkerSiblings(ctx, in, testStampWorkerJars(in.dir(), projectUnderTest));
        Map<String, String> profileEnv = TestLaunch.profileEnv(projectUnderTest, in.profileName());
        WorkerEnv testEnv = TestLaunch.withFastTierWalls(
                TestLaunch.testEnvironment(ctx, in, projectUnderTest).with(profileEnv), effectiveSel);
        TestTier tier = TestTier.of(effectiveSel, in.profileName(), fastMethodBudget(projectUnderTest, in.dir()));
        List<String> testJvmArgs = tier.suiteJvmArgs(TestLaunch.testJvmArgs(ctx, in, projectUnderTest, pluginDecls));
        InputGuard suiteGuard = suiteGuard(ctx, testClasses, testRtCp);
        SuiteDiscovery.Rekey rekey = () -> testStampKey(
                ctx, in, projectUnderTest, effectiveSel, affected, workerJars, testJvmArgs, testSrcs, testRtCp);
        String stampKey = rekey.key();
        // --force forces a real test run, matching the compile/package
        // freshness checks above (which all guard on !rerun). Without
        // this guard the action record would skip the runner even when
        // the user explicitly asked to bypass build caches.
        // A debug request is a request for a JVM to attach to; a replayed green
        // marker would leave the debugger with nothing to reach.
        // A coverage run is a request for a report; a replayed green marker has none.
        boolean coverage = in.session().coverage() || projectUnderTest.build().testCoverage();
        boolean rerun = in.session().config().rebuildOr(false) || in.session().debugJvm() != null || coverage;
        if (!rerun && stampKey != null && TestLaunch.replayGreenRun(ctx, actionCache, stampKey)) {
            return; // skip — nothing changed since last green run
        }
        TestLaunch.reweightForRealRun(ctx, in);
        if (suites.describe() != null) ctx.output(suites.describe());
        List<Path> runtimeCp = TestLaunch.testRuntimeCpWithLanguageRuntimes(ctx, cx, cas, testRtCp, testSrcs);
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
        TagExcludedCapture tagExcluded;
        SuiteDiscovery.Run reads = recordReads(ctx, in, projectUnderTest, effectiveSel, testEnv, runtimeCp, affected);
        try {
            // Module pin ([test] workers / [build] test-workers) wins over CLI for
            // hermetic opt-out. Auto hands the launcher the share; it applies the
            // class-wall rule once the selection is known. A debug run is one JVM.
            boolean debug = in.session().debugJvm() != null;
            boolean auto = !debug && TestLaunch.autoDispatch(in, projectUnderTest.build(), pluginDecls);
            int testWorkers = debug ? 1 : TestLaunch.dispatchWorkers(in, projectUnderTest.build(), pluginDecls);
            tagExcluded = new TagExcludedCapture(TestSupport.bridgeListener(
                    ctx, testWorkers, in.verbose(), moduleLabel, in.dir(), snippets, suites, tier));
            // The module path is read off the runtime classpath, which the stamp already keys.
            List<String> forkJvmArgs = new ArrayList<>(testJvmArgs);
            forkJvmArgs.addAll(JvmModulePath.args(projectUnderTest, in.dir(), runtimeCp));
            Supplier<JUnitLauncher> launcher = () -> TestLaunch.launcher(
                            in, projectUnderTest, effectiveSel, forkJvmArgs, affected, jacoco, coverageExec)
                    .withIntegrationResults(ctx.require(LAYOUT).integrationResultsDir())
                    .withAutoShare(auto)
                    .withReadRecording(reads.flag());
            TagExcludedCapture listener = tagExcluded;
            try {
                result = ctx.get(SUITE_RESOURCES)
                        .orElse(SuiteResources.NONE)
                        .launch(
                                testClasses,
                                suites,
                                effectiveSel.classes(),
                                projectUnderTest.build().testExcludeClasses(),
                                affected,
                                launcher,
                                runtimeCp,
                                (one, cp) -> TestLaunch.launch(
                                        ctx, in, one, cp, testWorkers, workerJars, testEnv, listener));
            } catch (TestLauncherFailure e) {
                TestLaunch.reportLauncherFailure(ctx, in, projectUnderTest, actionCache, testTaskId, stampKey, e);
                throw e;
            }
        } finally {
            HeapNotes.flush(ctx);
            if (gated) TEST_GATE.release();
        }
        if (jacoco != null && coverageExec != null) {
            TestLaunch.writeCoverageReport(ctx, in, jacoco, coverageExec, moduleLabel);
        }
        stampKey = reads.settle(ctx, unlessMoved(ctx, suiteGuard, stampKey), rekey);
        settle(
                ctx,
                in,
                effectiveSel,
                affected != null,
                suites,
                tagExcluded.message(),
                result.withSuites(suites::suiteOf),
                actionCache,
                testTaskId,
                stampKey,
                !testSrcs.isEmpty(),
                snippets);
    }

    /** Settle a {@code --class} selection that names no compiled class of this module: nothing ran. */
    private static void settleUnmatched(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestSelection sel,
            SuiteClassFilter suites,
            ActionCache actionCache,
            String testTaskId,
            boolean testSourcesExist,
            TestFailureSource.Cache snippets)
            throws Exception {
        TestSummary none = new TestSummary(0, 0, 0, 0, List.of());
        settle(ctx, in, sel, false, suites, null, none, actionCache, testTaskId, null, testSourcesExist, snippets);
    }

    /**
     * What the suite reads as it runs: discovery lists the test classes, and the classpath and
     * resources are read through. Taken before the stamp key, held against them after the run.
     */
    private static InputGuard suiteGuard(TaskContext ctx, Path testClasses, List<Path> testRtCp) throws IOException {
        List<Path> suiteInputs = new ArrayList<>(List.of(testClasses, ctx.require(MAIN_CLASSES)));
        suiteInputs.addAll(ctx.get(TEST_RESOURCE_DIRS).orElse(List.of()));
        suiteInputs.addAll(testRtCp);
        return InputGuard.of(suiteInputs);
    }

    /** Start recording what the suite reads, held to what its stamp already keys. */
    private static SuiteDiscovery.Run recordReads(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JkBuild project,
            TestSelection selection,
            WorkerEnv testEnv,
            List<Path> runtimeCp,
            AffectedTestRun.@Nullable Outcome affected)
            throws IOException {
        List<Path> keyed = keyedRoots(
                ctx,
                in,
                testEnv,
                ctx.get(TEST_SOURCES).orElse(List.of()),
                runtimeCp,
                testStampWorkerJars(in.dir(), project));
        return SuiteDiscovery.start(
                ctx.require(LAYOUT),
                in.dir(),
                selection,
                keyed,
                project.build().testNetworkAllowed(),
                affected == null);
    }

    /** Everything the run-tests stamp already hashes, as roots: what a suite reads there is no discovery. */
    private static List<Path> keyedRoots(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            WorkerEnv testEnv,
            List<Path> testSrcs,
            List<Path> runtimeCp,
            Map<String, String> workerJars)
            throws IOException {
        List<Path> roots =
                new ArrayList<>(List.of(ctx.require(TEST_CLASSES), ctx.require(MAIN_CLASSES), in.lockFile()));
        // The temp roots the suite was handed: what it makes there is its own.
        for (String name : List.of("TMPDIR", "TMP", "TEMP")) {
            String tmp = testEnv.extras().get(name);
            if (tmp != null && !tmp.isBlank()) roots.add(Path.of(tmp));
        }
        roots.addAll(ctx.get(TEST_RESOURCE_DIRS).orElse(List.of()));
        roots.addAll(runtimeCp);
        roots.addAll(testSrcs);
        ctx.get(JAVA_HOME).ifPresent(roots::add);
        for (String jar : workerJars.values()) roots.add(Path.of(jar));
        roots.addAll(WorkerClosure.paths(PluginJar.TEST_RUNNER));
        return roots;
    }

    /**
     * {@code stampKey}, or null when an input the suite read moved while it ran: the outcome stands
     * for this build and no marker vouches for the key.
     */
    private static @Nullable String unlessMoved(TaskContext ctx, InputGuard guard, @Nullable String stampKey)
            throws IOException {
        if (stampKey == null) return null;
        List<Path> moved = guard.moved();
        if (moved.isEmpty()) return stampKey;
        ctx.warn("inputs-moved", InputGuard.describe(TaskNames.RUN_TESTS, moved));
        return null;
    }

    /**
     * Judge the {@code --class} selection, then record the suite's outcome. A selection that
     * matched no test raises {@link TestClassMatch#NO_TESTS_MATCHED} and fails the step without a
     * red test; a named class the tag filter dropped beside tests that ran does the same after
     * their summary is kept.
     */
    private static void settle(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestSelection sel,
            boolean exactClassList,
            SuiteClassFilter suites,
            @Nullable String tagExcluded,
            TestSummary result,
            ActionCache actionCache,
            String testTaskId,
            @Nullable String stampKey,
            boolean testSourcesExist,
            TestFailureSource.Cache snippets)
            throws Exception {
        Judged judged = judgeClassSelection(ctx, in, sel, exactClassList, suites, tagExcluded, result);
        if (judged == null) return;
        TestSummary ran = judged.result();
        if (ran != null) {
            TagExcludedSuite.note(ctx, sel, in.profileName(), exactClassList, ran);
            ctx.put(TEST_RESULT, ran);
        }
        if (judged.noMatch() != null) {
            ctx.error(TestClassMatch.NO_TESTS_MATCHED, judged.noMatch());
            if (ran == null || ran.allPassed()) throw new IllegalArgumentException(judged.noMatch());
        }
        if (ran != null) {
            TestLaunch.recordOutcome(ctx, in, actionCache, testTaskId, stampKey, ran, testSourcesExist, snippets);
        }
    }

    /**
     * What a {@code --class} selection left: the summary of the tests that ran, or {@code null} when
     * none did, and the no-match message when the selection named something that did not run.
     */
    private record Judged(
            @Nullable TestSummary result, @Nullable String noMatch) {}

    /**
     * {@code [test] fast-method-budget} in milliseconds: the module's own, else its workspace
     * root's, so one line at the root holds every member to it; 0 when neither sets one.
     */
    static long fastMethodBudget(JkBuild module, Path moduleDir) throws IOException {
        long own = module.build().testFastMethodBudgetMs();
        if (own > 0) return own;
        Path root = WorkspaceScan.owningRoot(moduleDir).orElse(null);
        if (root == null || root.normalize().equals(moduleDir.normalize())) return 0;
        return JkBuildParser.parseLocal(ManifestPaths.manifestIn(root)).build().testFastMethodBudgetMs();
    }

    /**
     * The action key a green run of this suite is stored under: its sources, resources, runtime
     * classpath, worker jars, JVM arguments, selection and the test compiles' keys.
     */
    private static @Nullable String testStampKey(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JkBuild projectUnderTest,
            TestSelection effectiveSel,
            AffectedTestRun.@Nullable Outcome affected,
            Map<String, String> workerJars,
            List<String> testJvmArgs,
            List<Path> testSrcs,
            List<Path> testRtCp)
            throws Exception {
        List<Path> testResDirs = ctx.get(TEST_RESOURCE_DIRS).orElse(List.of());
        List<String> extras = new ArrayList<>(TestStamp.withCompileTest(
                NodeTestStamp.testStampExtras(
                        workerJars,
                        effectiveSel,
                        projectUnderTest,
                        testJvmArgs,
                        in.dir(),
                        ClasspathFingerprint.ON_DISK),
                compileTestKeys(ctx)));
        extras.addAll(SuiteEnvKeys.profileEnv(projectUnderTest, in.profileName()));
        if (affected != null && !affected.stampToken().isBlank()) {
            extras.add("affected:" + affected.stampToken());
        }
        return TestLaunch.stampKey(ctx, in, testSrcs, testResDirs, testRtCp, extras);
    }

    /**
     * The verdict a {@code --class} selection leaves, or null when this workspace module skips: a
     * workspace judges the patterns across its modules, a standalone project fails on the spot,
     * naming the runner's {@code tagExcluded} warning, with the tier that runs the class, when the
     * tag filter emptied the run. A class named without a wildcard that the tag filter dropped fails
     * the module, whatever else ran. A {@code --class} naming a class another suite owns says which
     * suite, matched or not.
     */
    private static @Nullable Judged judgeClassSelection(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestSelection sel,
            boolean exactClassList,
            SuiteClassFilter suites,
            @Nullable String tagExcluded,
            TestSummary result)
            throws IOException {
        if (tagExcluded != null) {
            JkBuild project = ctx.require(PROJECT);
            tagExcluded = TestClassMatch.explainTagExcluded(
                    tagExcluded,
                    project.profiles().byName().keySet(),
                    name -> TierTags.profile(TierTags.rootOf(in.dir()), name),
                    in.profileName(),
                    defaultTier(in.dir()));
        }
        String suiteHint = exactClassList ? null : suites.classHint(ctx.require(TEST_CLASSES), sel.classes());
        boolean nothing = TestClassMatch.nothingMatched(sel, exactClassList, result);
        if (!exactClassList && tagExcluded != null && TestClassMatch.namesClasses(sel)) {
            return nothing
                    ? new Judged(null, TestClassMatch.noMatchMessage(sel, in.profileName(), tagExcluded, suiteHint))
                    : new Judged(result, tagExcluded);
        }
        if (!nothing) {
            if (suiteHint != null) {
                ctx.warn("class-in-other-suite", "--class " + String.join(", ", sel.classes()) + ": " + suiteHint);
            }
            return new Judged(result, null);
        }
        if (in.projectModules().size() > 1) {
            TestClassMatch.skip(ctx, sel, tagExcluded, suiteHint);
            return null;
        }
        return new Judged(null, TestClassMatch.noMatchMessage(sel, in.profileName(), tagExcluded, suiteHint));
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

    /** The affected-test selection when the session asked for one; null otherwise. */
    private static AffectedTestRun.@Nullable Outcome affectedRun(
            TaskContext ctx, BuildPlanner.Inputs in, TestSelection effectiveSel, SuiteClassFilter suites)
            throws Exception {
        if (!in.session().affected()) return null;
        try {
            return AffectedTestRun.apply(ctx, in, effectiveSel, suites);
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
