// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerTest.resolveBeforeGate;
import static cc.jumpkick.runtime.PlannerTest.runTestsRequires;
import static cc.jumpkick.runtime.PlannerTest.suiteFilter;
import static cc.jumpkick.runtime.TestStampInputs.testStampWorkerJars;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.TestCache;
import cc.jumpkick.orchestrator.OrchestratorCode;
import cc.jumpkick.orchestrator.TestClassMatch;
import cc.jumpkick.orchestrator.TestRun;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.TestFailureSource;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.CompiledTests;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.KeyAudit;
import cc.jumpkick.task.KeyContract;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.task.ToolchainPolicy;
import cc.jumpkick.task.WorkerClosure;
import cc.jumpkick.test.StampTokens;
import cc.jumpkick.test.SuiteEnvDelivery;
import cc.jumpkick.test.SuiteFilter;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.test.TestLauncherFailure;
import cc.jumpkick.test.TestTelemetry;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One plan's run-tests: the step, its body that runs the module's suite or replays its last green
 * run, and its predictor beside it. Built once per plan from what its {@link BuildPlanner.Ctx}
 * holds; the test compile is {@link TestCompileSteps}, and what both read is {@link PlannerTest}'s.
 */
final class TestSteps {

    private final BuildPlanner.Inputs in;
    private final Cas cas;
    private final ActionCache actionCache;
    private final Supplier<EffortWeights.Plan> plan;
    private final ModuleShape shape;
    private final @Nullable PluginDeclarations pluginDecls;

    TestSteps(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        this.in = cx.in();
        this.cas = cx.cas();
        this.actionCache = cx.actionCache();
        this.plan = cx.plan();
        this.shape = cx.shape();
        this.pluginDecls = pluginDecls;
    }

    /** run-tests, waiting on what the suite reads and on {@code extraRequires}. */
    Task runTests(List<String> extraRequires) {
        List<String> testRequires = runTestsRequires(pluginDecls, extraRequires);
        return Task.builder(TaskNames.RUN_TESTS)
                .forecast(StepForecast.priced(this::forecastRunTests))
                .stage(BuildStage.TEST)
                .label("Testing")
                .kind(TaskKind.IO)
                .requires(testRequires.toArray(new String[0]))
                .weight(() -> plan.get().runTests())
                .ticks(in.estimatedTestCount())
                .execute(this::runSuite)
                .build();
    }

    /** Whether the module has no suite to run: tests not selected for it, or no test sources; labelled so. */
    private boolean nothingToRun(TaskContext ctx) {
        if (!SuiteRunRules.selectedForTests(in.session().testSelection(), in.dir())) {
            ctx.label(TaskNames.TESTS_NOT_SELECTED);
            ctx.cached();
            return true;
        }
        if (ctx.get(COMPILED_TESTS).map(CompiledTests::noSources).orElse(false)) {
            // Nothing to run is nothing done: the module is checked, not built.
            ctx.label("no tests to run");
            ctx.cached();
            return true;
        }
        return false;
    }

    /** Run one module's suite, or replay its last green run when nothing it reads has changed. */
    private void runSuite(TaskContext ctx) throws Exception {
        if (nothingToRun(ctx)) return;
        Path testClasses = ctx.require(PARSED_BUILD).testClassesDir();
        List<Path> testSrcs =
                ctx.get(COMPILED_TESTS).map(CompiledTests::sources).orElse(List.of());
        JkBuild projectUnderTest = ctx.require(MODULE).project();
        String moduleLabel = coordinate(projectUnderTest);
        String testTaskId = ActionKey.qualifiedTaskId(TaskNames.RUN_TESTS, testClasses);
        TestFailureSource.Cache snippets = new TestFailureSource.Cache();
        // jk build / BSP / MCP: when the session selection carries no tags, resolve the
        // workspace's here as the CLI does for `jk test`. It feeds BOTH the stamp and the runner.
        var effectiveSel = in.session().testSelection();
        List<Path> tagCp = PlannerTest.tagClasspath(ctx);
        SuiteFilter suites = suiteFilter(in, shape.compact(), projectUnderTest, testClasses, tagCp);
        AffectedTestRun.Outcome affected = affectedRun(ctx, in, effectiveSel, suites, tagCp);
        if (affected != null && affected.classNames().isEmpty()) return; // nothing affected — no stamp store
        if (affected == null
                && settledFromClassFiles(ctx, effectiveSel, suites, testClasses, tagCp, projectUnderTest)) {
            settleUnmatched(ctx, in, effectiveSel, suites, actionCache, testTaskId, !testSrcs.isEmpty(), snippets);
            return;
        }
        // The test runtime classpath is the siblings' jars: wait for them to be written.
        SiblingWaits.awaitSiblingTestRuntime(ctx, in);
        // Worker jars feed both the forked JVM and the TestStamp: nested-engine CLI modules
        // enrich with the engine, every PluginJar and the native client, so the stamp matches what
        // the suite actually loads and starts — the same set the forecast uses.
        Map<String, String> workerJars =
                SiblingWaits.awaitWorkerSiblings(ctx, in, testStampWorkerJars(in.dir(), projectUnderTest));
        TestStampInputs stamp = stampInputs(ctx, in, projectUnderTest, pluginDecls, workerJars);
        List<Path> testRtCp = stamp.runtimeClasspath();
        WorkerEnv testEnv = TestLaunch.suiteEnvironment(ctx, in, projectUnderTest);
        TestTier tier = stamp.tier();
        InputGuard suiteGuard = suiteGuard(ctx, testClasses, testRtCp);
        SuiteDiscovery.Rekey rekey = () -> namedStampKey(ctx, in, stamp, affected, testSrcs);
        String stampKey = stampKey(rekey, affected, projectUnderTest, effectiveSel);
        KeyAudit.used(in.dir(), TaskNames.RUN_TESTS, stampKey, rekey::key);
        // --force forces a real test run, matching the compile/package
        // freshness checks above (which all guard on !rerun). Without
        // this guard the action record would skip the runner even when
        // the user explicitly asked to bypass build caches.
        // A debug request is a request for a JVM to attach to; a replayed green
        // marker would leave the debugger with nothing to reach.
        // A coverage run is a request for a report; a replayed green marker has none.
        boolean coverage = in.session().coverage() || projectUnderTest.build().testCoverage();
        boolean rerun = SuiteRunRules.forcedRunReason(in.session(), projectUnderTest) != null;
        if (replayedGreenRun(ctx, rerun, stampKey, projectUnderTest, testEnv, tier)
                || replayedGuardRun(ctx, rerun, affected, stamp, testSrcs, projectUnderTest, testEnv, suites)) return;
        List<Path> runtimeCp = TestLaunch.testRuntimeCpWithLanguageRuntimes(
                ctx, shape.kotlin(), shape.groovy(), cas, testRtCp, testSrcs);
        // Discovery writes under target/ and reads sibling manifests, so it can throw: it runs
        // before the gate too, never between the acquire and the try that releases it.
        SuiteDiscovery.Run reads = recordReads(ctx, in, projectUnderTest, effectiveSel, testEnv, runtimeCp, affected);
        // Under the same stamp the classes that passed before still stand: a red or cut-short
        // suite runs only what has not passed yet. Under [test] cache = "cone" so does a class that
        // passed under another stamp when nothing it reaches or read has moved since. A forced,
        // debug, coverage or affected run, and a module that says [test] cache = "module", runs its
        // whole selection.
        boolean whole = rerun || affected != null || stamp.cachesModule();
        TestClassLedger ledger = beginLedger(ctx, reads, stamp, whole, testClasses, runtimeCp, stampKey, moduleLabel);
        TestLaunch.reweightForRealRun(ctx, in);
        if (suites.describe() != null) ctx.output(suites.describe());
        // Test execution is serialized across the checkout's concurrently-built units
        // unless the user opted into parallel tests — shared ports/locks/fixtures. The
        // worker count is decided once the gate is held: a suite parked at the gate is
        // not running, and the ones running when it starts are what it shares with.
        boolean gated = !in.session().parallelTests();
        TestGates.Hold[] gate = new TestGates.Hold[1];
        Path coverageExec = coverage ? TestLaunch.coverageExecFile(ctx) : null;
        // The agent and the report tool come first: a coverage run that cannot fetch
        // JaCoCo fails before any suite starts, not after the suite ran uninstrumented.
        // What the suite's JVMs read of the caller's environment is fetched and recorded as they run.
        SuiteEnvDelivery env = envDelivery(ctx, projectUnderTest, testEnv);
        CoverageTools.Jacoco jacoco = resolveBeforeGate(
                coverage ? () -> CoverageTools.resolve(projectUnderTest, cas) : null,
                gated,
                () -> gate[0] =
                        TestGates.acquire(ctx.require(MODULE).layout().workspaceRoot(), SessionCancel::cancelled));
        // Started last before the launch, closed in its finally: nothing between can leave it listening.
        env.start();
        // Null until the launch returns: a run stopped part way leaves it so.
        TestSummary result = null;
        boolean launcherFailed = false;
        TagExcludedCapture tagExcluded;
        try {
            // Module pin ([test] workers) wins over CLI for
            // hermetic opt-out. Auto hands the launcher the share; it applies the
            // class-wall rule once the selection is known. A debug run is one JVM.
            boolean debug = in.session().debugJvm() != null;
            boolean auto = !debug && TestLaunch.autoDispatch(in, projectUnderTest.build(), pluginDecls);
            int testWorkers = debug ? 1 : TestLaunch.dispatchWorkers(in, projectUnderTest.build(), pluginDecls);
            tagExcluded =
                    listener(ctx, in, ledger, testWorkers, moduleLabel, snippets, suites, tier, affected, effectiveSel);
            // The module path is read off the runtime classpath, which the stamp already keys.
            List<String> forkJvmArgs = new ArrayList<>(stamp.jvmArgs());
            forkJvmArgs.addAll(JvmModulePath.args(projectUnderTest, in.dir(), runtimeCp));
            SuiteResources.Launcher launcher = ledger.launching((settled, s) -> TestLaunch.launcher(
                            in, projectUnderTest, effectiveSel, forkJvmArgs, s, affected, jacoco, coverageExec, settled)
                    .withRecording(env.probe())
                    .withIntegrationResults(ctx.require(MODULE).layout().integrationResultsDir())
                    .withAutoShare(auto)
                    .withWidening(auto ? TestLaunch.liveWorkers(in, projectUnderTest.build(), pluginDecls) : null)
                    .withRecording(reads.flag()));
            try {
                result = ctx.get(COMPILED_TESTS)
                        .map(c -> new SuiteResources(c.suiteRoots()))
                        .orElse(SuiteResources.NONE)
                        .launch(
                                testClasses,
                                suites,
                                effectiveSel.classes(),
                                // The settled classes are left out by the launcher alone: a launch
                                // whose only matches passed earlier still starts, and runs nothing.
                                projectUnderTest.build().testExcludeClasses(),
                                affected,
                                launcher,
                                runtimeCp,
                                (one, cp, l) -> TestLaunch.launch(
                                        ctx, in, one, cp, testWorkers, workerJars, env.launchEnv(), l, tagExcluded));
            } catch (TestLauncherFailure e) {
                launcherFailed = true;
                TestLaunch.reportLauncherFailure(ctx, in, projectUnderTest, actionCache, testTaskId, stampKey, e);
                throw e;
            }
        } finally {
            env.close();
            HeapNotes.flush(ctx);
            if (gate[0] != null) gate[0].close();
            if (result == null)
                storeCutShort(ledger, launcherFailed, actionCache, suiteGuard, rekey, testTaskId, stampKey);
        }
        // Green once every launch has run: the sandbox has nothing left to show. Red keeps it for a look.
        if (result.allPassed()) TestHomes.discard(in.dir());
        TestLaunch.writeCoverageReport(ctx, in, jacoco, coverageExec, moduleLabel);
        recordClasses(ctx, reads, ledger, moduleLabel, testClasses, runtimeCp, stamp, stampKey);
        String settledKey = reads.settle(ctx, unlessMoved(ctx, suiteGuard, stampKey), rekey, env.seen(), ledger);
        // The marker is stored under the key that folds in what the suite was found to read.
        KeyAudit.used(in.dir(), TaskNames.RUN_TESTS, settledKey, rekey::key);
        result = ledger.finish(ctx, actionCache, testTaskId, stampKey, settledKey, result);
        TestFlakes.record(ctx, ctx.require(MODULE).layout().workspaceRoot(), in.dir(), ledger.outcomes());
        List<String> said = EnvReadReport.report(ctx, in, projectUnderTest, testEnv, testClasses, runtimeCp, result);
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
                settledKey,
                !testSrcs.isEmpty(),
                snippets,
                said);
        keepTokens(effectiveSel, settledKey, result, affected, rekey);
    }

    /**
     * The class ledger under {@code stampKey}, with the classes whose key has not moved since they
     * passed settled too under {@code [test] cache = "cone"} ({@link SuiteDiscovery.Run#unmoved}).
     */
    private TestClassLedger beginLedger(
            TaskContext ctx,
            SuiteDiscovery.Run reads,
            TestStampInputs stamp,
            boolean whole,
            Path testClasses,
            List<Path> runtimeCp,
            @Nullable String stampKey,
            String moduleLabel)
            throws IOException {
        Map<String, TestClassLedger.Entry> unmoved = whole || !stamp.cachesByCone()
                ? Map.of()
                : reads.unmoved(
                        testClasses,
                        mainClasses(ctx),
                        runtimeCp,
                        stamp.extras(),
                        ctx.get(COMPILED_TESTS).map(CompiledTests::resourceDirs).orElse(List.of()),
                        stampKey);
        return TestClassLedger.begin(
                ctx, actionCache, stampKey, moduleLabel, stamp.tier().name(), whole, unmoved);
    }

    /**
     * The stamp key the run starts from: {@code rekey}'s, or, unless the run is forced or runs only
     * what an edit affects, a result's the remote tier found by its read list on a host that never
     * ran the suite ({@link SuiteDiscovery#adopt}).
     */
    private @Nullable String stampKey(
            SuiteDiscovery.Rekey rekey,
            AffectedTestRun.@Nullable Outcome affected,
            JkBuild project,
            TestSelection selection)
            throws Exception {
        String key = rekey.key();
        if (affected != null || SuiteRunRules.forcedRunReason(in.session(), project) != null) return key;
        return SuiteDiscovery.adopt(in.dir(), selection, key, rekey, actionCache::restorable);
    }

    /**
     * How the run's test JVMs reach the caller's environment ({@link SuiteEnvDelivery}), said once
     * per JDK when that JDK keeps the probe from the programs a test starts. A suite replayed by its
     * classes' cones has the probe mark the workspace's classes, its own and its siblings' jars, so
     * its records say which classes each one ran; a coverage run does not, since the coverage agent
     * must see the classes as compiled.
     */
    private SuiteEnvDelivery envDelivery(TaskContext ctx, JkBuild project, WorkerEnv testEnv) throws IOException {
        Path javaHome = ctx.require(JAVA_HOME);
        BuildLayout layout = ctx.require(MODULE).layout();
        boolean uses = TestTelemetry.shadow()
                && !in.session().coverage()
                && !project.build().testCoverage()
                && TestStampInputs.testCache(project, in.dir()) == TestCache.CONE;
        SuiteEnvDelivery env = SuiteEnvDelivery.prepare(
                in.cache(),
                EnvReadReport.recordDir(in.dir()),
                layout.classesDir(),
                javaHome,
                project,
                testEnv,
                uses ? layout.workspaceRoot() : null);
        String unhooked = env.unhooked();
        if (unhooked == null) return env;
        boolean[] first = {false};
        UNHOOKED_SAID.get(javaHome.toAbsolutePath().normalize(), "", () -> first[0] = true);
        if (first[0]) ctx.warn(SuiteEnvDelivery.UNHOOKED, unhooked);
        return env;
    }

    /** The JDKs whose refused hook this engine has said: once each, not on every run. */
    private static final StampedMemo<Path, String, Boolean> UNHOOKED_SAID = StampedMemo.bounded(32);

    /**
     * What a whole suite's green marker key was taken over, for a later forecast that misses it to
     * name what moved ({@link StampTokens}).
     */
    private void keepTokens(
            TestSelection selection,
            @Nullable String stampKey,
            TestSummary result,
            AffectedTestRun.@Nullable Outcome affected,
            SuiteDiscovery.Rekey rekey) {
        if (stampKey != null && result.allPassed() && affected == null)
            StampTokens.keep(in.dir(), selection, stampKey, rekey::key);
    }

    /**
     * Whether the class files alone settle this run as one with no test to run, before the
     * siblings' jars, the stamp and a test JVM: a {@code --class} selection that names no compiled
     * class of this module (in a workspace most modules do not hold the class the user named), or a
     * selection whose suites claim none (a tag suite no test carries, a class-pattern suite the
     * module has no class of), which is said as no tests to run.
     */
    private static boolean settledFromClassFiles(
            TaskContext ctx,
            TestSelection sel,
            SuiteFilter suites,
            Path testClasses,
            List<Path> tagClasspath,
            JkBuild project) {
        List<String> excluded = project.build().testExcludeClasses();
        if (!sel.classes().isEmpty()) return !suites.namesCompiledClass(testClasses, sel.classes(), excluded);
        if (suites.selectsCompiledClass(testClasses, tagClasspath, excluded)) return false;
        ctx.label("no tests to run");
        ctx.cached();
        return true;
    }

    /**
     * Whether the last green run under {@code stampKey} stands for this one: nothing changed and no
     * {@code rerun}. Each class it recorded is said replayed in build history.
     */
    private boolean replayedGreenRun(
            TaskContext ctx,
            boolean rerun,
            @Nullable String stampKey,
            JkBuild project,
            WorkerEnv testEnv,
            TestTier tier)
            throws IOException {
        if (rerun || stampKey == null || !TestLaunch.replayGreenRun(ctx, in, actionCache, stampKey, project, testEnv))
            return false;
        TestClassLedger.load(actionCache, stampKey, coordinate(project), tier.name())
                .replayCovered(ctx, cls -> true, suite -> true);
        return true;
    }

    /**
     * Keep each class's record of the run {@code reads} recorded, with its key in shadow, before the
     * environment report empties the lookups.
     */
    private static void recordClasses(
            TaskContext ctx,
            SuiteDiscovery.Run reads,
            TestClassLedger ledger,
            String moduleLabel,
            Path testClasses,
            List<Path> runtimeCp,
            TestStampInputs stamp,
            @Nullable String stampKey)
            throws IOException {
        reads.recordClasses(
                ledger,
                moduleLabel,
                testClasses,
                mainClasses(ctx),
                runtimeCp,
                ctx.get(JAVA_HOME).orElse(null),
                stamp.extras(),
                ctx.get(COMPILED_TESTS).map(CompiledTests::resourceDirs).orElse(List.of()),
                stampKey);
    }

    /** {@code group:name}, the label a module's suite reports under. */
    private static String coordinate(JkBuild project) {
        return project.project().group() + ":" + project.project().name();
    }

    /**
     * The run's progress listener: the step's own bridge, each class recorded in {@code ledger} as it
     * finishes and marked when it passed after a red under this stamp, and the runner's {@code
     * tag-excluded} warning kept when the run named classes.
     */
    private static TagExcludedCapture listener(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestClassLedger ledger,
            int testWorkers,
            String moduleLabel,
            TestFailureSource.Cache snippets,
            SuiteFilter suites,
            TestTier tier,
            AffectedTestRun.@Nullable Outcome affected,
            TestSelection effectiveSel) {
        return new TagExcludedCapture(
                ledger.recording(TestSupport.bridgeListener(
                        ctx, testWorkers, in.verbose(), moduleLabel, in.dir(), snippets, suites, tier, ledger::marked)),
                affected != null || !effectiveSel.classes().isEmpty());
    }

    /** Settle a {@code --class} selection that names no compiled class of this module: nothing ran. */
    private static void settleUnmatched(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestSelection sel,
            SuiteFilter suites,
            ActionCache actionCache,
            String testTaskId,
            boolean testSourcesExist,
            TestFailureSource.Cache snippets)
            throws Exception {
        TestSummary none = new TestSummary(0, 0, 0, 0, List.of());
        settle(
                ctx,
                in,
                sel,
                false,
                suites,
                null,
                none,
                actionCache,
                testTaskId,
                null,
                testSourcesExist,
                snippets,
                List.of());
    }

    /**
     * What the suite reads as it runs: discovery lists the test classes, and the classpath and
     * resources are read through. Taken before the stamp key, held against them after the run.
     */
    private static InputGuard suiteGuard(TaskContext ctx, Path testClasses, List<Path> testRtCp) throws IOException {
        List<Path> suiteInputs = new ArrayList<>(List.of(testClasses, mainClasses(ctx)));
        suiteInputs.addAll(
                ctx.get(COMPILED_TESTS).map(CompiledTests::resourceDirs).orElse(List.of()));
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
                ctx.get(COMPILED_TESTS).map(CompiledTests::sources).orElse(List.of()),
                runtimeCp,
                testStampWorkerJars(in.dir(), project));
        return SuiteDiscovery.start(
                ctx.require(MODULE).layout(),
                in.dir(),
                selection,
                keyed,
                project.build().testNetworkAllowed(),
                affected == null,
                project);
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
        // Not the lock: the stamp keys the jars the suite resolves, not the workspace's lock file, so a
        // suite that reads the file itself keys it as a discovered input.
        List<Path> roots = new ArrayList<>(List.of(ctx.require(PARSED_BUILD).testClassesDir(), mainClasses(ctx)));
        // The temp roots the suite was handed: what it makes there is its own.
        for (String name : List.of("TMPDIR", "TMP", "TEMP")) {
            String tmp = testEnv.extras().get(name);
            if (tmp != null && !tmp.isBlank()) roots.add(Path.of(tmp));
        }
        roots.addAll(ctx.get(COMPILED_TESTS).map(CompiledTests::resourceDirs).orElse(List.of()));
        roots.addAll(runtimeCp);
        roots.addAll(testSrcs);
        ctx.get(JAVA_HOME).ifPresent(roots::add);
        for (String jar : workerJars.values()) roots.add(Path.of(jar));
        roots.addAll(WorkerClosure.paths(PluginJar.TEST_RUNNER));
        return roots;
    }

    /**
     * The class ledger of a run that never returned a summary. A launcher that failed is red for a
     * reason no class owns, so no class stays settled under {@code stampKey}. A run cancelled part
     * way keeps the classes it got through when the suite's inputs held while it ran and its key,
     * taken again, is still {@code stampKey}; otherwise the classes that passed prove nothing a later
     * build under that key may skip. Safe on an interrupted thread.
     */
    static void storeCutShort(
            TestClassLedger ledger,
            boolean launcherFailed,
            ActionCache actionCache,
            InputGuard suiteGuard,
            SuiteDiscovery.Rekey rekey,
            String testTaskId,
            @Nullable String stampKey) {
        if (stampKey == null) return;
        if (launcherFailed) {
            TestClassLedger.clear(actionCache, testTaskId, stampKey);
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            if (suiteGuard.moved().isEmpty() && stampKey.equals(rekey.key()))
                ledger.store(actionCache, testTaskId, stampKey);
            else Log.debug("cut-short suite: an input moved while it ran; class ledger not stored");
        } catch (Exception e) {
            Log.debug("cut-short suite: class ledger not stored", e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
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
        ctx.warn(InputGuard.MOVED, InputGuard.describe(TaskNames.RUN_TESTS, moved));
        return null;
    }

    /**
     * Judge the {@code --class} selection, then record the suite's outcome. A selection that
     * matched no test raises {@link TestClassMatch#NO_TESTS_MATCHED} and fails the step without a
     * red test; a named class the tag filter dropped beside tests that ran does the same after
     * their summary is kept. {@code said} rides a green marker for its replay to say again.
     */
    private static void settle(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestSelection sel,
            boolean exactClassList,
            SuiteFilter suites,
            @Nullable String tagExcluded,
            TestSummary result,
            ActionCache actionCache,
            String testTaskId,
            @Nullable String stampKey,
            boolean testSourcesExist,
            TestFailureSource.Cache snippets,
            List<String> said)
            throws Exception {
        TestClassMatch.Verdict judged = judgeClassSelection(ctx, in, sel, exactClassList, suites, tagExcluded, result);
        if (judged == null) return;
        TestSummary ran = judged.result();
        if (ran != null) {
            TagExcludedSuite.note(ctx, sel, exactClassList, ran);
            ctx.put(TEST_RUN, TestRun.of(ran));
        }
        if (judged.noMatch() != null) {
            ctx.error(TestClassMatch.NO_TESTS_MATCHED, judged.noMatch());
            if (ran == null || ran.allPassed()) throw new IllegalArgumentException(judged.noMatch());
        }
        if (ran != null) {
            TestLaunch.recordOutcome(ctx, in, actionCache, testTaskId, stampKey, ran, testSourcesExist, snippets, said);
        }
    }

    /** The run-tests stamp's inputs as this run reads them: what the classpath step published, the worker jars awaited. */
    private static TestStampInputs stampInputs(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JkBuild projectUnderTest,
            @Nullable PluginDeclarations pluginDecls,
            Map<String, String> workerJars)
            throws IOException {
        return TestStampInputs.of(
                new TestStampInputs.Facts(
                        in.dir(),
                        projectUnderTest,
                        ctx.require(MODULE).layout(),
                        ctx.require(JAVA_HOME),
                        in.profileName(),
                        in.session().testSelection(),
                        pluginDecls,
                        workerJars,
                        ctx.require(CLASSPATHS).testRuntime(),
                        ctx.require(CLASSPATHS).providedRuntime(),
                        compileTestKeys(ctx)),
                ClasspathFingerprint.ON_DISK);
    }

    /**
     * The action key a green run of this suite is stored under: its sources, resources, runtime
     * classpath, worker jars, JVM arguments, selection and the test compiles' keys.
     */
    /**
     * {@link #testStampKey}, with the lines it hashed told to the action cache by name, so the
     * marker stored under the key records them and a later miss names the input that moved.
     */
    private static @Nullable String namedStampKey(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestStampInputs stamp,
            AffectedTestRun.@Nullable Outcome affected,
            List<Path> testSrcs)
            throws Exception {
        KeyAudit.Keyed keyed = KeyAudit.tapped(() -> testStampKey(ctx, in, stamp, affected, testSrcs));
        if (keyed == null) return null;
        ActionCache.derivedFrom(keyed.key(), keyed.tokens());
        return keyed.key();
    }

    private static @Nullable String testStampKey(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestStampInputs stamp,
            AffectedTestRun.@Nullable Outcome affected,
            List<Path> testSrcs)
            throws Exception {
        List<Path> testResDirs =
                ctx.get(COMPILED_TESTS).map(CompiledTests::resourceDirs).orElse(List.of());
        List<String> extras = new ArrayList<>(stamp.extras());
        if (affected != null && !affected.stampToken().isBlank()) {
            extras.add("affected:" + affected.stampToken());
        }
        // Unchanged inputs replay the green run; null (an unreadable input) is "not cached".
        String key = stamp.key(testSrcs, mainClasses(ctx), null, testResDirs, extras);
        TestLaunch.noteStampKey(ctx, in, key, testSrcs, testResDirs, stamp.runtimeClasspath(), extras);
        return key;
    }

    /**
     * Whether the guard's green run over the same tree ran this run's tests too ({@link
     * TestStampInputs#underGuard}), for a run that is neither forced nor affected-only: replayed with the counts of the tests this selection runs, out
     * of the classes the guard's run recorded.
     */
    private boolean replayedGuardRun(
            TaskContext ctx,
            boolean rerun,
            AffectedTestRun.@Nullable Outcome affected,
            TestStampInputs stamp,
            List<Path> testSrcs,
            JkBuild projectUnderTest,
            WorkerEnv testEnv,
            SuiteFilter suites)
            throws Exception {
        if (rerun || affected != null) return false;
        String guardKey = guardStampKey(ctx, in, stamp, testSrcs);
        if (guardKey == null) return false;
        TestSelection selection = stamp.selection();
        TestClassLedger guardLedger = TestClassLedger.load(
                actionCache,
                guardKey,
                coordinate(projectUnderTest),
                stamp.tier().name());
        Predicate<String> suiteRuns = suite -> selection.wantedSuites().contains(suite);
        if (!TestLaunch.replayGreenRun(
                ctx,
                in,
                actionCache,
                guardKey,
                projectUnderTest,
                testEnv,
                marker -> guardLedger.covered(suites.acceptor(), suiteRuns))) return false;
        guardLedger.replayCovered(ctx, suites.acceptor(), suiteRuns);
        return true;
    }

    /**
     * The key the guard's run ({@code jk test -g}) of this module stores its green marker under, when
     * that run covers this one ({@link TestStampInputs#underGuard}); null otherwise.
     */
    private static @Nullable String guardStampKey(
            TaskContext ctx, BuildPlanner.Inputs in, TestStampInputs stamp, List<Path> testSrcs) throws Exception {
        TestStampInputs guard = stamp.underGuard();
        return guard == null ? null : testStampKey(ctx, in, guard, null, testSrcs);
    }

    /**
     * The verdict a {@code --class} selection leaves ({@link TestClassMatch#judge}), or null when
     * this workspace module skips: then it leaves the runner's tag-excluded warning and the suite
     * hint, when there are, for the run-wide verdict.
     */
    private static TestClassMatch.@Nullable Verdict judgeClassSelection(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestSelection sel,
            boolean exactClassList,
            SuiteFilter suites,
            @Nullable String tagExcluded,
            TestSummary result)
            throws IOException {
        String suiteHint = exactClassList
                ? null
                : suites.classHint(
                        ctx.require(PARSED_BUILD).testClassesDir(), PlannerTest.tagClasspath(ctx), sel.classes());
        TestClassMatch.Verdict verdict = TestClassMatch.judge(
                sel, exactClassList, tagExcluded, suiteHint, in.projectModules().size() > 1, result);
        if (verdict.skipped()) {
            if (tagExcluded != null || suiteHint != null) {
                ctx.put(TEST_RUN, new TestRun(null, tagExcluded, suiteHint));
            }
            ctx.label(TestClassMatch.skipLabel(sel.classes(), suiteHint));
            ctx.cached();
            return null;
        }
        if (verdict.otherSuite() != null) {
            ctx.warn(
                    "class-in-other-suite",
                    "--class " + String.join(", ", sel.classes()) + ": " + verdict.otherSuite());
        }
        return verdict;
    }

    /** The affected-test selection when the session asked for one; null otherwise. */
    private static AffectedTestRun.@Nullable Outcome affectedRun(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            TestSelection effectiveSel,
            SuiteFilter suites,
            List<Path> tagClasspath)
            throws Exception {
        if (!in.session().affected()) return null;
        try {
            return AffectedTestRun.apply(ctx, in, effectiveSel, suites, tagClasspath);
        } catch (AffectedTestRun.RankingRefused e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * The test compiles' action keys compile-test published, one per language it compiled tests
     * in. Each is an input to the suite's outcome — the compiler options, plugins and target that
     * shaped the test classes — and nothing else in the stamp reads them.
     */
    private static TestStamp.CompileTestKeys compileTestKeys(TaskContext ctx) {
        return ctx.get(COMPILED_TESTS).map(CompiledTests::actionKeys).orElse(TestStamp.CompileTestKeys.NONE);
    }

    /**
     * The tests a dirty suite runs, as the forecast says them: {@code all} of the {@code estimated},
     * or, where the classes' cones replay some once the pending compiles are built ({@link
     * #replayedByCones}), the rest of them, which {@code s} then counts ({@link
     * ForecastScope#rerunTests}).
     */
    private static String coneTests(
            ForecastScope s, ForecastScope.Prepared prepared, boolean kspRuns, int estimated, String all)
            throws Exception {
        TestClassRecording.Replayed replayed = replayedByCones(s, prepared, kspRuns);
        if (replayed == null || replayed.classes() == 0) return all;
        s.rerunTests = (int) Math.max(0, estimated - replayed.tests());
        return "~" + ForecastSteps.count(s.rerunTests, "test") + " · " + replayed.classes()
                + (replayed.classes() == 1 ? " class" : " classes") + " replayed";
    }

    /**
     * Which of the suite's classes replay once the pending compiles are built, by their cones ({@link TestClassRecording#replayedAfter}), or null when the forecast counts them all:
     * a module not under {@code [test] cache = "cone"}, a test source or KSP round that moves, a
     * worker jar its stamp hashes rebuilding, or a dirty sibling whose edit the forecast cannot read.
     */
    private static TestClassRecording.@Nullable Replayed replayedByCones(
            ForecastScope s, ForecastScope.Prepared prepared, boolean kspRuns) throws Exception {
        if (s.testDirty || kspRuns) return null;
        if (TestStampInputs.testCache(s.project, s.dir) != TestCache.CONE) return null;
        if (TestStampInputs.rebuildingWorker(s.dir, s.project, s.orderDeps) != null) return null;
        var edits = s.pendingEdits();
        if (edits == null) return null;
        BuildLayout layout = prepared.parsed().layout();
        return TestClassRecording.replayedAfter(
                s.dir,
                layout,
                layout.testClassesDir(),
                layout.classesDir(),
                prepared.classpaths().testRuntime(),
                edits);
    }

    /**
     * run-tests' price: dirty when a compile it reads runs or a test-scope sibling is rebuilding,
     * else the green marker under the same stamp key the live step stores — the runtime classpath
     * as the run hashes it, a wiped tree read as the bytes the build restores.
     */
    private TaskForecast.@Nullable Task forecastRunTests(StepForecast.Scope scope) throws Exception {
        ForecastScope s = ForecastScope.of(scope);
        if (s.skipTests || !s.haveTests()) return null;
        // As the run decides it first: a module --exclusive left out runs no suite.
        if (!SuiteRunRules.selectedForTests(SessionContext.current().testSelection(), s.dir)) {
            return new TaskForecast.Task(
                    TaskNames.RUN_TESTS, TaskForecast.Status.CACHED, TaskNames.TESTS_NOT_SELECTED, null);
        }
        s.projectOwnOutputs();
        ForecastScope.Prepared prepared = s.prepared();
        boolean compact = prepared.parsed().compact();
        BuildLayout layout = prepared.parsed().layout();
        // The tests this run selects: an integration run is priced by its integration tests, not
        // every unit test beside them.
        int estimated = TestSupport.estimateSelectedSuiteTestCount(
                s.dir, compact, SessionContext.current().testSelection());
        s.testCount = estimated;
        String tests = estimated > 0 ? "~" + ForecastSteps.count(estimated, "test") : "tests";
        // The run's own rule (SuiteRunRules.forcedRunReason): a rebuild, debug or coverage run never
        // replays a green marker, so neither does its forecast.
        String forced = SuiteRunRules.forcedRunReason(SessionContext.current(), s.project);
        if (forced != null) {
            return new TaskForecast.Task(
                    TaskNames.RUN_TESTS, TaskForecast.Status.RUN, "run tests · " + forced + " · " + tests, null);
        }
        // A sibling on the test classpath is rebuilding: the suite re-runs even when the main
        // compile stays cached.
        // The runtime classpath is keyed on code where the compile is keyed on ABI: a sibling
        // rebuilt with its API unchanged leaves this module's compile cached and still reruns its
        // suite (compileDepDirty).
        boolean kspRuns = s.kspRuns();
        // A suite re-run for a sibling's jar bytes alone reads as that, not as this module's
        // change, so why-rebuilt can tell the two apart.
        boolean jarOnly = !s.testDirty && !s.compileDirty && (s.compileDepDirty || s.testDepDirty);
        // Siblings whose edit moved only comments: the stamp keys their classes without line tables,
        // which the classes on disk already show, so the marker under the stamp decides.
        List<String> kept = jarOnly && !kspRuns ? s.codeKeptSiblings() : null;
        String keptHint = kept == null ? "" : " · comment-only edit in " + String.join(", ", kept) + " (hint)";
        if (kept == null && (s.compileDirty || s.compileDepDirty || s.testDirty || s.testDepDirty || kspRuns)) {
            String jk = StampTokens.jkMoved(
                    s.dir,
                    SessionContext.current().testSelection(),
                    KeyContract.SUITE.keyVersion(),
                    OrchestratorCode.digest());
            if (jk == null) tests = coneTests(s, prepared, kspRuns, estimated, tests);
            String why = jk != null
                    ? "run tests · " + jk + " · " + tests
                    : jarOnly
                            ? "run tests · " + TaskForecast.DEPENDENCY_JAR_CHANGED + " · " + tests
                            : "run tests · " + tests;
            return new TaskForecast.Task(TaskNames.RUN_TESTS, TaskForecast.Status.RUN, why, null);
        }
        // A worker jar the stamp hashes (`test-plugin-jars`, the nested engine's plugins) whose
        // sibling is rebuilding: the run hashes the rebuilt jar, the jar on disk is the old one.
        String worker = TestStampInputs.rebuildingWorker(s.dir, s.project, s.orderDeps);
        if (worker != null) {
            return new TaskForecast.Task(
                    TaskNames.RUN_TESTS,
                    TaskForecast.Status.RUN,
                    "run tests · " + StampTokens.workerChanged(worker) + " · " + tests,
                    null);
        }
        TestStampInputs stamp = TestStampInputs.of(
                TestStampInputs.Facts.forecast(
                        s.dir,
                        s.project,
                        layout,
                        prepared.javaHome(),
                        s.knobs.profile(),
                        prepared.parsed().pkgDecls(),
                        prepared.classpaths(),
                        new TestStamp.CompileTestKeys(
                                s.compileTestKey, s.compileTestKotlinKey, s.compileTestGroovyKey)),
                s.restored.identity());
        long ts = Perf.start();
        String mainFp = null;
        if (!s.classesTreeWhole(layout)) {
            // An empty or incomplete tree is keyed as the compile outputs plus the resource roots
            // (the same merge as package after a clean), with no resource drift applied — the
            // walk's own projection when it made one, which also covers a mixed module's tree.
            mainFp = s.restored.projectedIdentity(layout.classesDir());
            if (mainFp == null)
                mainFp = PackagingKeys.classesTokenForPackage(
                        s.dir, compact, layout, s.project, s.actionCache, s.compileMainKey, null);
            if (mainFp != null && mainFp.startsWith("missing:")) mainFp = null;
        }
        String finalMainFp = mainFp;
        String stampKey = stamp.forecastKey(compact, layout.classesDir(), finalMainFp);
        Perf.end("  test-stamp-key", ts);
        // The forecast is no job, so no step stated what the marker's reader accepts: the stamp says.
        ToolchainPolicy.Expectation expected = stamp.expectation();
        var marker = stampKey == null
                ? Optional.<ActionCache.ActionRecord>empty()
                : s.actionCache.restorable(stampKey, expected);
        boolean hit = marker.isPresent() && TestStamp.green(marker.get());
        // The same key with a red record is the one shape a live run never skips: say so, or the
        // ETA reads "only the suite is dirty" as stamp drift.
        boolean red = marker.isPresent() && !hit;
        // As the run decides it (SuiteDiscovery.settle): a suite found to reach another host stores
        // no marker, so its miss is that and not a stamp that moved.
        boolean network = !hit
                && !red
                && !s.project.build().testNetworkAllowed()
                && SuiteReads.load(s.dir, SessionContext.current().testSelection())
                        .network();
        Perf.note("forecast-test-stamp " + s.dir, "key", stampKey, "hit", hit, "red", red);
        if (hit)
            return new TaskForecast.Task(
                    TaskNames.RUN_TESTS, TaskForecast.Status.CACHED, "· " + tests + keptHint, null);
        // As the run decides it: the guard's green run over the same tree ran these tests too.
        TestStampInputs guard = stamp.underGuard();
        String guardKey = guard == null ? null : guard.forecastKey(compact, layout.classesDir(), finalMainFp);
        if (guardKey != null
                && ForecastSteps.presentRecord(s.actionCache, guardKey)
                        .filter(TestStamp::green)
                        .isPresent()) {
            return new TaskForecast.Task(
                    TaskNames.RUN_TESTS, TaskForecast.Status.CACHED, "· " + tests + keptHint, null);
        }
        // A miss for none of the reasons above says which input of the last green run's key moved.
        // As the run decides it: a suite whose last recording could not be read stored no marker.
        String unread = red || network ? null : SuiteReads.unread(s.dir);
        // As the run decides it: records under this key made on a JDK this build's policy declines.
        String declined = stampKey == null || expected == null || red || network
                ? null
                : s.actionCache.declined(stampKey, expected);
        String moved = declined != null
                ? declined
                : red || network || unread != null
                        ? null
                        : StampTokens.reason(
                                s.dir,
                                SessionContext.current().testSelection(),
                                () -> stamp.forecastKey(compact, layout.classesDir(), finalMainFp));
        String priced = red || network || unread != null || stampKey == null
                ? tests
                : pricedByCone(s, stamp, layout, compact, stampKey, estimated, tests);
        return new TaskForecast.Task(
                TaskNames.RUN_TESTS,
                TaskForecast.Status.RUN,
                "run tests · " + (network ? TaskForecast.TESTS_USE_NETWORK + " · " : "")
                        + (unread != null ? SuiteReads.unreadMessage(unread) + " · " : "")
                        + (moved != null ? moved + " · " : "") + priced
                        + (red ? " · " + TaskForecast.LAST_RUN_FAILED : ""),
                null);
    }

    /**
     * The tests a missed suite runs, as the run decides it under {@code [test] cache = "cone"}: a
     * class whose key has not moved since it passed is replayed, so only the rest are priced, and
     * {@code s} says how many ({@link ForecastScope#rerunTests}). Every input is current where this
     * is asked, so the keys are the ones the run takes. {@code tests} when nothing is replayed.
     */
    private static String pricedByCone(
            ForecastScope s,
            TestStampInputs stamp,
            BuildLayout layout,
            boolean compact,
            String stampKey,
            int estimated,
            String tests)
            throws IOException {
        if (!stamp.cachesByCone()) return tests;
        List<Path> resources = stamp.forecastResources(compact);
        if (resources == null) return tests;
        Map<String, TestClassLedger.Entry> replayed = SuiteDiscovery.replayedByCone(
                s.dir,
                layout,
                SessionContext.current().testSelection(),
                stamp.runtimeClasspath(),
                stamp.extras(),
                resources,
                stampKey);
        if (replayed.isEmpty()) return tests;
        long replayedTests = 0;
        for (TestClassLedger.Entry e : replayed.values()) replayedTests += e.tests();
        s.rerunTests = (int) Math.max(0, estimated - replayedTests);
        return "~" + ForecastSteps.count(s.rerunTests, "test") + " · " + replayed.size()
                + (replayed.size() == 1 ? " class" : " classes") + " replayed";
    }
}
