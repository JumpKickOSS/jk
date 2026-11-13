// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerLang.compileGroovySources;
import static cc.jumpkick.runtime.PlannerLang.compileKotlinSources;
import static cc.jumpkick.runtime.PlannerTest.resolveSuites;
import static cc.jumpkick.runtime.PlannerTest.suiteMarker;
import static cc.jumpkick.runtime.PlannerTest.testSourceGenSteps;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompilerJdk;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.CompiledTests;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.task.TestStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One plan's compile-test: the step, its body over the selected suites' Kotlin, Groovy and Java
 * test sources, and its predictor beside it. Built once per plan from what its {@link
 * BuildPlanner.Ctx} holds; the suite run is {@link TestSteps}, and what both read is {@link
 * PlannerTest}'s.
 */
final class TestCompileSteps {

    private final BuildPlanner.Inputs in;
    private final Cas cas;
    private final ActionCache actionCache;
    private final Supplier<EffortWeights.Plan> plan;
    private final boolean compact;
    private final boolean groovyModule;
    private final boolean hasFixtures;
    private final @Nullable PluginDeclarations pluginDecls;
    private final KspArm ksp;

    TestCompileSteps(BuildPlanner.Ctx cx, boolean hasFixtures, @Nullable PluginDeclarations pluginDecls, KspArm ksp) {
        this.in = cx.in();
        this.cas = cx.cas();
        this.actionCache = cx.actionCache();
        this.plan = cx.plan();
        this.compact = cx.compact();
        this.groovyModule = cx.groovyModule();
        this.hasFixtures = hasFixtures;
        this.pluginDecls = pluginDecls;
        this.ksp = ksp;
    }

    Task compileTest() {
        // AFTER_COMPILE scripts may generate types tests import. copy-resources is a real
        // input, not just ordering: the test classpath (and its action-key fingerprint)
        // includes target/classes, which copy-resources writes — racing it fingerprints a
        // half-copied dir and intermittently crashes on vanishing files under -r.
        List<String> requires = new ArrayList<>(
                List.of(TaskNames.BUILD_LOGIC_AFTER_COMPILE, TaskNames.RESOLVE_DEPS, TaskNames.COPY_RESOURCES));
        if (hasFixtures) requires.add(TaskNames.COMPILE_TEST_FIXTURES);
        requires.addAll(testSourceGenSteps(pluginDecls));
        return Task.builder(TaskNames.COMPILE_TEST)
                .forecast(StepForecast.priced(this::forecastCompileTest))
                .stage(BuildStage.TEST)
                .label("Test Compile")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .weight(() -> plan.get().compileTest())
                .interpolated() // opaque javac/kotlinc call — ease it over time
                .ticks(1)
                .execute(this::runCompileTest)
                .build();
    }

    /**
     * The compile-test body: the KSP test round, then kotlinc, groovyc and javac over the selected
     * suites, then the suites' resources.
     */
    private void runCompileTest(TaskContext ctx) throws Exception {
        List<String> suiteNames = selectedSuites(ctx);
        if (suiteNames == null) return;
        JkBuild project = ctx.require(PROJECT);
        TestSources src = TestSources.collect(project, in.dir(), compact, suiteNames, ctx.require(LAYOUT), pluginDecls);
        // A class-pattern suite compiles the default suite's roots: the suites whose
        // directories this compile reads, which is what the shared output records.
        List<String> compiledSuites = TestSuites.compiled(
                in.dir(), compact, suiteNames, project.build().testClassSuites());
        if (src.isEmpty()) {
            ctx.label("no test sources");
            StepWarnings.clear(ctx, TaskNames.COMPILE_TEST);
            ctx.put(COMPILED_TESTS, CompiledTests.NO_SOURCES);
            warnDeclaredTestDepsButNoSources(ctx, project, in.dir());
            ctx.cached(); // SKIPPED — nothing to compile
            ctx.progress(1);
            return;
        }
        SiblingWaits.awaitSiblingTestCompile(ctx, in);
        List<Path> baseCp = testCompileClasspath(ctx, src);
        KspArm.RoundOutcome round = ksp.runTestRound(ctx, src, baseCp, compiledSuites);
        if (round != KspArm.RoundOutcome.KEPT) {
            src = TestSources.collect( // the round rewrote its generated sources
                    project, in.dir(), compact, suiteNames, ctx.require(LAYOUT), pluginDecls);
        }
        Path testClasses = ctx.require(TEST_CLASSES);
        String selectionKey = String.join(",", compiledSuites);
        resetOnSelectionChange(ctx, testClasses, selectionKey);
        // Identity before the compilers run. Adoption may remove a copy that predates
        // the ledger, and must not remove a file this compile rewrote.
        Map<String, String> beforeNonClass = ResourceMirror.nonClassIdentity(testClasses);
        Path testLedger = ctx.require(LAYOUT).buildDir().resolve("incremental").resolve(ResourceMirror.TEST_LEDGER);
        if (!Files.isRegularFile(testLedger)) forgetTestCompileState(in.cache(), testClasses);
        TestOutputs outs = TestOutputs.of(src, ctx.require(LAYOUT), testClasses);
        boolean javaTests = !src.javaTest().isEmpty();
        // The stamps' instant: before any test compiler reads its first source.
        long readClock = FreshnessStamp.clockNow(testClasses);
        // Kotlin first, then Groovy against its output (Gradle's order), then Java against
        // both. The step did no work when the round and every compiler restored.
        StepWarnings warnings = new StepWarnings();
        TestSupport.Compiled kotlin = compileKotlinTests(
                ctx, warnings, src, baseCp, outs.kotlinOut(), testClasses, outs.mixedKotlin() && javaTests);
        TestSupport.Compiled groovy = compileGroovyTests(
                ctx,
                warnings,
                src,
                outs.groovyClasspath(baseCp),
                outs.groovyOut(),
                testClasses,
                compiledSuites,
                outs.mixedGroovy() && javaTests);
        TestSupport.Compiled javac = compileJavaTests(ctx, warnings, src, outs.javacClasspath(baseCp), testClasses);
        boolean restored =
                round != KspArm.RoundOutcome.RAN && kotlin.restored() && groovy.restored() && javac.restored();
        TestStamp.CompileTestKeys keys =
                new TestStamp.CompileTestKeys(javac.actionKey(), kotlin.actionKey(), groovy.actionKey());
        warnings.write(ctx, TaskNames.COMPILE_TEST);
        mergeLanguageOutputs(src, outs, ctx.require(LAYOUT).buildDir());
        ctx.put(
                COMPILED_TESTS,
                SuiteResources.mirror(
                        ctx,
                        in,
                        actionCache,
                        compact,
                        compiledSuites,
                        testClasses,
                        beforeNonClass,
                        outs.mixedKotlin(),
                        outs.mixedGroovy(),
                        src.all(),
                        keys));
        Files.createDirectories(testClasses);
        Files.writeString(suiteMarker(testClasses), selectionKey);
        TestClassStamps.write(
                ctx, keys, src.javacSources(), src.ktTest(), src.gvTest(), baseCp, testClasses, readClock);
        if (restored) ctx.cached(); // SKIPPED — every compiler restored its record
        ctx.progress(1);
    }

    /**
     * The suites this run compiles, or null after marking the step skipped. A workspace run names
     * a suite that need not exist in EVERY module — the IDE-generated {@code jk test -s
     * integration} config must run where the suite exists and skip the rest, not fail the
     * workspace. Single-module runs keep the hard error (typo protection).
     */
    private @Nullable List<String> selectedSuites(TaskContext ctx) {
        var resolved = resolveSuites(in, compact, ctx.require(PROJECT));
        if (resolved.ok()) return resolved.suites();
        if (in.projectModules().size() > 1) {
            ctx.label("skipped: suite not in this module");
            ctx.put(COMPILED_TESTS, CompiledTests.NO_SOURCES);
            ctx.cached(); // SKIPPED — suite absent in this workspace module
            ctx.progress(1);
            return null;
        }
        throw new IllegalArgumentException(resolved.missingMessage());
    }

    /** target/classes, own fixtures, the resolved test compile classpath, and the Groovy jar when needed. */
    private List<Path> testCompileClasspath(TaskContext ctx, TestSources src) throws Exception {
        return TestOutputs.baseClasspath(
                mainClasses(ctx),
                ctx.require(PROJECT),
                ctx.require(LAYOUT),
                ctx.require(CLASSPATHS).testCompile(),
                ModuleClasspaths.groovyJar(
                        ctx.require(PROJECT),
                        ctx.require(LOCKFILE),
                        cas,
                        groovyModule || !src.gvTest().isEmpty()));
    }

    /**
     * All suites share target/test-classes and run-tests scans it: when the SELECTION changes, wipe the
     * shared output and the per-language merge sources, or the previous selection's classes and
     * copied resources keep running/shadowing under the new one indefinitely. The {@link
     * PlannerTest#suiteMarker} beside the tree records the selection that produced it.
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
     * Java tests can reference Groovy test types. {@code jointWithJava} sweeps the Java roots.
     */
    private TestSupport.Compiled compileGroovyTests(
            TaskContext ctx,
            StepWarnings warnings,
            TestSources src,
            List<Path> baseCp,
            Path gvTestOut,
            Path testClasses,
            List<String> suiteNames,
            boolean jointWithJava)
            throws Exception {
        if (src.gvTest().isEmpty()) return TestSupport.Compiled.NOTHING;
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
            StepDiagnostics.forwardWorkerDiagnostics(
                    ctx, "groovyc", gr.diagnostics(), "groovyc failed without diagnostics");
            throw new RuntimeException("test groovyc reported errors");
        }
        JavacDiagnostics.reportWarnings(ctx, JavacDiagnostics.GROOVYC, gr.freshWarnings(), gr.replayed());
        warnings.add(JavacDiagnostics.GROOVYC, gr.warnings());
        return new TestSupport.Compiled(true, gr.cacheHit(), gr.actionKey());
    }

    /**
     * Kotlin test sources first, so Groovy and Java tests can reference Kotlin test types (mirrors
     * the main ordering). {@code readsJava} has kotlinc read the Java test sources' declarations.
     */
    private TestSupport.Compiled compileKotlinTests(
            TaskContext ctx,
            StepWarnings warnings,
            TestSources src,
            List<Path> baseCp,
            Path ktTestOut,
            Path testClasses,
            boolean readsJava)
            throws Exception {
        if (src.ktTest().isEmpty()) return TestSupport.Compiled.NOTHING;
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
            StepDiagnostics.forwardWorkerDiagnostics(
                    ctx,
                    "kotlinc",
                    PackageProviders.enrich(ctx, baseCp, ClasspathResolver.COMPILE_TEST, kr.diagnostics()),
                    "kotlinc failed without diagnostics");
            throw new RuntimeException("test kotlinc reported errors");
        }
        JavacDiagnostics.reportWarnings(ctx, JavacDiagnostics.KOTLINC, kr.freshWarnings(), kr.replayed());
        warnings.add(JavacDiagnostics.KOTLINC, kr.warnings());
        return new TestSupport.Compiled(true, kr.cacheHit(), kr.actionKey());
    }

    /** Java/Scala test sources over {@code javaCp} ({@link TestOutputs#javacClasspath}). */
    private TestSupport.Compiled compileJavaTests(
            TaskContext ctx, StepWarnings warnings, TestSources src, List<Path> javaCp, Path testClasses)
            throws Exception {
        if (src.javaTest().isEmpty() && src.scTest().isEmpty()) return TestSupport.Compiled.NOTHING;
        Path javaTestOut = testClasses; // javac always writes to java/test/
        List<String> javacArgs = ctx.require(JAVAC_ARGS);
        // The declared annotation processors run over test sources — the shared table's javac
        // half, then the test table's own, or that table alone under its inherit = false; the
        // request builder discovers classpath processors
        // when none are declared, exactly as compile-main does, so a Lombok-using test sees its
        // generated members.
        ModuleClasspaths classpaths = ctx.require(CLASSPATHS);
        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations", "test");
        Files.createDirectories(genDir);
        ScalaCompile.Setup scalaSetup =
                src.scTest().isEmpty() ? null : ScalaCompile.prepare(ctx.require(PROJECT), ctx.require(LOCKFILE), cas);
        TestSupport.Compiled compiled = TestSupport.compileWithCache(
                ctx,
                warnings,
                TaskNames.COMPILE_TEST,
                new PlannerCompile.TestCompile(
                        src.javacSources(),
                        javaCp,
                        classpaths.javacTestProcessor(),
                        classpaths.contributedTestProcessor(),
                        javaTestOut,
                        ctx.require(PROJECT).javac().testRelease(ctx.require(RELEASE)),
                        javacArgs,
                        ctx.require(PROJECT).javac().forTests(),
                        ctx.require(JAVA_HOME),
                        scalaSetup,
                        mainClasses(ctx),
                        classpaths.testProcessorDiscovery(),
                        CompilerJdk.pinned(ctx.require(PROJECT), in.session())),
                genDir,
                cas,
                in.cache(),
                WorkerEnv.forModule(
                        ctx.require(PROJECT).build().env(),
                        in.dir(),
                        ctx.require(LAYOUT).moduleTargetDir()));
        if (!compiled.ok()) throw new RuntimeException("test compile failed");
        return compiled;
    }

    /**
     * In mixed test mode, kotlin/groovy output needs to be merged into testClasses (java/test/).
     * Java test output already went there directly.
     */
    private static void mergeLanguageOutputs(TestSources src, TestOutputs outs, Path buildDir) throws IOException {
        Path testClasses = outs.testClasses();
        if (outs.mixedKotlin() && !src.ktTest().isEmpty()) {
            Files.createDirectories(testClasses);
            ResourceMirror.mergeLanguageOutput(outs.kotlinOut(), testClasses, buildDir, "kotlin-test");
        }
        if (outs.mixedGroovy() && !src.gvTest().isEmpty()) {
            Files.createDirectories(testClasses);
            ResourceMirror.mergeLanguageOutput(outs.groovyOut(), testClasses, buildDir, "groovy-test");
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

    /**
     * compile-test's price: dirty with the main compile, else {@link JavaCompile#predict} on the
     * shared {@link PlannerCompile#testCompileRequest}; a cached compile whose suites' resources
     * drifted from {@code target/test-classes} still runs, since this step copies them.
     */
    private TaskForecast.@Nullable Task forecastCompileTest(StepForecast.Scope scope) throws Exception {
        ForecastScope s = ForecastScope.of(scope);
        if (s.skipTests || !s.haveTests()) return null;
        s.projectOwnOutputs();
        ForecastScope.Prepared prepared = s.prepared();
        BuildLayout layout = prepared.parsed().layout();
        TestSources testSources = s.testSources();
        if (!s.compileApiDirty()) {
            s.compileTestKotlinKey = testSources.ktTest().isEmpty()
                    ? null
                    : lastTestCompileKey(s.actionCache, TaskNames.COMPILE_TEST_KOTLIN, layout);
            s.compileTestGroovyKey = testSources.gvTest().isEmpty()
                    ? null
                    : lastTestCompileKey(s.actionCache, TaskNames.COMPILE_TEST_GROOVY, layout);
        }
        // Kotlin and Groovy tests keyed as the build's passes key them, ahead of javac, which reads
        // their outputs.
        TaskForecast.Task langMiss = s.testRound().runs() || s.compileApiDirty() || s.kspRuns()
                ? null
                : testLangMiss(s, prepared, testSources);
        TaskForecast.Task step;
        if (s.testRound().runs()) {
            // The round runs inside this step, and its output is unknown until it does.
            step = new TaskForecast.Task(TaskNames.COMPILE_TEST, TaskForecast.Status.RUN, "KSP test round runs", null);
            s.testDirty = true;
        } else if (s.compileApiDirty() || s.kspRuns()) {
            // Dependency-only dirtiness: the tests recompile only if main really does.
            step = new TaskForecast.Task(
                    TaskNames.COMPILE_TEST,
                    TaskForecast.Status.RUN,
                    s.depOnlyDirty ? "recompile · " + TaskForecast.ONLY_IF_COMPILE_RUNS : "recompile · main changed",
                    null);
            s.testDirty = true;
        } else if (langMiss != null) {
            step = langMiss;
            s.testDirty = true;
        } else if (!testSources.javacSources().isEmpty()) {
            int release = prepared.parsed().release();
            List<Path> baseCp = TestOutputs.baseClasspath(
                    layout.classesDir(),
                    s.project,
                    layout,
                    prepared.classpaths().testCompile(),
                    ModuleClasspaths.groovyJar(
                            s.project,
                            prepared.parsed().lock(),
                            s.cas,
                            prepared.parsed().shape().groovy()
                                    || !testSources.gvTest().isEmpty()));
            Path testOut = layout.testClassesDir();
            // javac reads the Kotlin and Groovy test outputs beside it, as their records restore them.
            TestOutputs outs = TestOutputs.of(testSources, layout, testOut);
            if (outs.mixedKotlin()) s.restored.projectFromRecord(outs.kotlinOut(), s.compileTestKotlinKey);
            if (outs.mixedGroovy()) s.restored.projectFromRecord(outs.groovyOut(), s.compileTestGroovyKey);
            List<Path> javacCp = outs.javacClasspath(baseCp);
            ScalaCompile.Setup testScala = testSources.scTest().isEmpty()
                    ? null
                    : ScalaCompile.prepare(s.project, prepared.parsed().lock(), s.cas);
            List<Path> testSrc = testSources.javacSources();
            CompileRequest req = PlannerCompile.testCompileRequest(new PlannerCompile.TestCompile(
                    testSrc,
                    javacCp,
                    prepared.classpaths().javacTestProcessor(),
                    prepared.classpaths().contributedTestProcessor(),
                    testOut,
                    s.project.javac().testRelease(release),
                    prepared.parsed().javacArgs(),
                    s.project.javac().forTests(),
                    prepared.javaHome(),
                    testScala,
                    layout.classesDir(),
                    prepared.classpaths().testProcessorDiscovery(),
                    CompilerJdk.pinned(s.project, SessionContext.current())));
            String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST, testOut);
            Path stateDir = ActionKey.stateDir(
                    ActionTree.INCREMENTAL_JAVA.under(CacheTree.ACTIONS.under(s.cache)),
                    TaskNames.COMPILE_TEST,
                    testOut);
            long tt = Perf.start();
            var pred = JavaCompile.predict(
                    taskId,
                    req,
                    BuildIdentity.cacheKeyVersion(),
                    s.actionCache,
                    stateDir,
                    s.workerJar,
                    layout.generatedSourcesDir("annotations", "test"),
                    WorkerEnv.forModule(s.project.build().env(), layout.moduleRoot(), layout.moduleTargetDir()),
                    s.restored.abiToken());
            Perf.end("  predict-compile-test", tt);
            Perf.note(
                    "forecast-compile-test " + s.dir,
                    "key",
                    pred.actionKey(),
                    "outcome",
                    pred.outcome(),
                    "reason",
                    pred.reason(),
                    "cp",
                    javacCp.size(),
                    "src",
                    testSrc.size(),
                    "release",
                    release,
                    "out",
                    testOut);
            // The prediction keys a test-scope sibling's jar as it is on disk, before the build
            // rebuilds it: an API edit there (a core edit a plugin's test fixtures read) hit here
            // and recompiled 17 sources in the build. A dirty test-scope sibling keeps the step
            // running unless its edit is body-only, as a compile-scope one does for main.
            step = ForecastSteps.compileStep(TaskNames.COMPILE_TEST, pred, s.testDepDirty, s.force, req, s.testDepHint);
            s.compileTestKey = pred.actionKey();
            if (!step.cached()) s.testDirty = true;
        } else {
            // Kotlin/Groovy-only tests whose passes' records are all present (testLangMiss).
            step = new TaskForecast.Task(TaskNames.COMPILE_TEST, TaskForecast.Status.CACHED, "", null);
        }
        if (step.cached()
                && !s.testDirty
                && Files.isDirectory(layout.classesDir())
                && Files.isDirectory(layout.testClassesDir())
                && TaskForecaster.resourcesOutOfSync(
                        ModuleLayout.testResourcesDir(s.dir, prepared.parsed().compact()), layout.testClassesDir())) {
            return new TaskForecast.Task(
                    TaskNames.COMPILE_TEST, TaskForecast.Status.RUN, TaskForecast.TEST_RESOURCES_CHANGED, null);
        }
        return step;
    }

    /**
     * The test compilers' base classpath, as the build's own ({@link TestOutputs#baseClasspath}), or
     * null when the Groovy jar it needs cannot be resolved here.
     */
    private static @Nullable List<Path> testBaseClasspath(
            ForecastScope s, ForecastScope.Prepared prepared, TestSources src) throws Exception {
        BuildLayout layout = prepared.parsed().layout();
        try {
            return TestOutputs.baseClasspath(
                    layout.classesDir(),
                    s.project,
                    layout,
                    prepared.classpaths().testCompile(),
                    ModuleClasspaths.groovyJar(
                            s.project,
                            prepared.parsed().lock(),
                            s.cas,
                            prepared.parsed().shape().groovy() || !src.gvTest().isEmpty()));
        } catch (Exception e) {
            Log.debug("testBaseClasspath: the Groovy compile jar could not be resolved read-only", e);
            return null;
        }
    }

    /**
     * compile-test's Kotlin and Groovy passes, keyed with the requests the build's passes build
     * (Kotlin first, Groovy against its output): the step's RUN when a pass's record is missing or
     * its toolchain cannot be resolved read-only, null when every pass hits. A hit records the key,
     * so the javac pass and the classes projection read that pass's record.
     */
    private static TaskForecast.@Nullable Task testLangMiss(
            ForecastScope s, ForecastScope.Prepared prepared, TestSources src) throws Exception {
        if (src.ktTest().isEmpty() && src.gvTest().isEmpty()) return null;
        BuildLayout layout = prepared.parsed().layout();
        Path testClasses = layout.testClassesDir();
        List<Path> baseCp = testBaseClasspath(s, prepared, src);
        if (baseCp == null) return langRun(src.ktTest().isEmpty() ? "Groovy" : "Kotlin", 1, " · toolchain unresolved");
        TestOutputs outs = TestOutputs.of(src, layout, testClasses);
        boolean javaTests = !src.javaTest().isEmpty();
        ForecastLangArms arms = s.arms();
        CompileInputs.Module module = CompileSteps.forecastInputs(s, prepared);
        if (!src.ktTest().isEmpty()) {
            String key = arms.kotlinTestKey(
                    module,
                    src.ktTest(),
                    baseCp,
                    outs.kotlinOut(),
                    testClasses,
                    outs.mixedKotlin() && javaTests ? List.of(src.javaTestSrc()) : null);
            if (key == null) return langRun("Kotlin", src.ktTest().size(), " · toolchain unresolved");
            if (!ForecastSteps.present(s.actionCache, key))
                return langRun("Kotlin", src.ktTest().size(), "");
            s.compileTestKotlinKey = key;
        }
        if (!src.gvTest().isEmpty()) {
            String key = arms.groovyTestKey(
                    module,
                    src.gvTest(),
                    outs.groovyClasspath(baseCp),
                    outs.groovyOut(),
                    testClasses,
                    outs.mixedGroovy() && javaTests ? groovyJointRoots(s, prepared) : null);
            if (key == null) return langRun("Groovy", src.gvTest().size(), " · toolchain unresolved");
            if (!ForecastSteps.present(s.actionCache, key))
                return langRun("Groovy", src.gvTest().size(), "");
            s.compileTestGroovyKey = key;
        }
        return null;
    }

    private static TaskForecast.Task langRun(String lang, int sources, String why) {
        return new TaskForecast.Task(
                TaskNames.COMPILE_TEST,
                TaskForecast.Status.RUN,
                "compile · " + ForecastSteps.count(sources, lang + " test source") + why,
                null);
    }

    /** The Java test roots a joint Groovy pass sweeps: every compiled suite's, as the build lists them. */
    private static List<Path> groovyJointRoots(ForecastScope s, ForecastScope.Prepared prepared) {
        boolean compact = prepared.parsed().compact();
        List<String> selected = TestSupport.selectedSuites(
                s.dir, compact, s.project.build(), SessionContext.current().testSelection());
        List<Path> roots = new ArrayList<>();
        for (String suite :
                TestSuites.compiled(s.dir, compact, selected, s.project.build().testClassSuites())) {
            for (Path root : TestSuites.javaRoots(s.dir, compact, suite)) {
                if (Files.isDirectory(root)) roots.add(root);
            }
        }
        return roots;
    }

    /**
     * The key the live Kotlin or Groovy test compile will replay: the record its {@code tasks/}
     * pointer names. Neither compiler has a content predictor here, so the forecast assumes the
     * compile fresh and folds the key of the record a fresh compile restores from — the same key
     * the live stamp folded when it was stored. A module whose tests have never compiled has no
     * pointer and folds nothing, as the live run does on its first build.
     */
    private static @Nullable String lastTestCompileKey(ActionCache actionCache, String task, BuildLayout layout) {
        try {
            return actionCache
                    .lastFor(ActionKey.qualifiedTaskId(task, layout.testClassesDir()))
                    .map(ActionCache.ActionRecord::actionKey)
                    .orElse(null);
        } catch (IOException e) {
            Log.debug("lastTestCompileKey: no key for " + task, e);
            return null;
        }
    }
}
