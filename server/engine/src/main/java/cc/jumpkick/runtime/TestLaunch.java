// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerKsp.contributedProvidedFor;
import static cc.jumpkick.runtime.PlannerKsp.pluginTestClasspath;
import static cc.jumpkick.runtime.PlannerSupport.groovyRuntime;
import static cc.jumpkick.runtime.PlannerSupport.kotlinStdlib;
import static cc.jumpkick.runtime.PlannerSupport.needsNestedEngineIsolation;
import static cc.jumpkick.runtime.PlannerSupport.nestedEngineTestEnv;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.guard.eval.OutputArtifacts;
import cc.jumpkick.host.Errors;
import cc.jumpkick.http.Http;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.base.LiveUnits;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.runtime.base.TestFailureSource;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.AffectedTestRun;
import cc.jumpkick.test.CoverageAgent;
import cc.jumpkick.test.CoverageResults;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.TestLauncherFailure;
import cc.jumpkick.test.TestProgressListener;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How run-tests launches a module's suite: the JVM args and environment the fork gets, its runtime
 * classpath and worker count, the launch itself and its coverage report, and the markers a run
 * reads before it forks and leaves behind it. {@link PlannerTest} owns the step and the gate.
 */
final class TestLaunch {

    private TestLaunch() {}

    /**
     * The plugin steps' contributed arguments, then {@code [test] jvm-args}, system-properties and
     * the active profile's jvm-args — what rides the fork and its stamp alike. The module's own
     * flags come last, so they win over a framework plugin's.
     */
    static List<String> testJvmArgs(
            TaskContext ctx, BuildPlanner.Inputs in, JkBuild project, PluginBuild.@Nullable Declarations pluginDecls)
            throws IOException {
        List<String> args = new ArrayList<>(PlannerKsp.pluginTestJvmArgs(ctx.require(LAYOUT), pluginDecls));
        args.addAll(PlannerSupport.testJvmArgs(project, in.profileName()));
        return args;
    }

    /** The step's own line naming the flags the fork gets beyond jk's tuning; silent when there are none. */
    static void noteJvmArgs(TaskContext ctx, List<String> testJvmArgs) {
        if (!testJvmArgs.isEmpty()) ctx.output("test jvm-args: " + String.join(" ", testJvmArgs));
    }

    /**
     * The launcher never ran a test: a failed step with the fork's output, not a red test — and a
     * red marker under the run's stamp, so the next build runs the suite again.
     */
    static void reportLauncherFailure(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JkBuild project,
            ActionCache actionCache,
            String testTaskId,
            @Nullable String stampKey,
            TestLauncherFailure e)
            throws IOException {
        TestLauncherReport.report(ctx, in.lockFile(), project, e);
        if (stampKey != null) {
            actionCache.storeWithOutputs(testTaskId, stampKey, Map.of(), TestStamp.outcome(0, 0, 0, 1));
        }
    }

    /** A fresh {@code target/reports/jacoco.exec}: every suite JVM of this module appends to it. */
    static Path coverageExecFile(TaskContext ctx) throws IOException {
        Path exec = ctx.require(LAYOUT).reportsDir().resolve("jacoco.exec");
        Files.deleteIfExists(exec);
        Files.createDirectories(exec.getParent());
        return exec;
    }

    /** {@code target/reports/coverage/} — the module's JaCoCo HTML report, {@code index.html} first. */
    static final String COVERAGE_HTML_DIR = "coverage";

    /**
     * The module's JaCoCo XML, where the {@code coverage.*} guard measures look for it, and the HTML
     * report beside it: both over every class directory the build produced, from the execution
     * data every suite JVM of this module appended to. The whole-report counters are published for
     * the run's record and {@code jk-results.md}.
     */
    static void writeCoverageReport(
            TaskContext ctx, BuildPlanner.Inputs in, CoverageTools.Jacoco jacoco, Path exec, String moduleLabel)
            throws Exception {
        BuildLayout layout = ctx.require(LAYOUT);
        Path xml = layout.reportsDir().resolve(OutputArtifacts.DEFAULT_COVERAGE);
        Path html = layout.reportsDir().resolve(COVERAGE_HTML_DIR);
        List<Path> classDirs = List.of(ctx.require(MAIN_CLASSES), layout.kotlinClassesDir(), layout.groovyClassesDir());
        List<Path> sourceDirs = new ArrayList<>();
        for (ModuleLayout.Root root : ModuleLayout.roots(in.dir())) {
            if (root.kind() == ModuleLayout.Kind.SOURCE) sourceDirs.add(in.dir().resolve(root.relative()));
        }
        CoverageTools.Counters counters;
        try {
            CoverageTools.writeReport(
                    ctx.require(JAVA_HOME), jacoco, exec, classDirs, sourceDirs, xml, html, moduleLabel);
            counters = CoverageTools.counters(xml);
        } catch (IOException e) {
            ctx.error("coverage", Errors.text(e));
            throw e;
        }
        Path index = html.resolve("index.html");
        CoverageResults.publish(
                in.dir(),
                new CoverageResults.Module(
                        in.dir().toAbsolutePath().normalize().toString(),
                        moduleLabel,
                        counters.linesCovered(),
                        counters.linesMissed(),
                        counters.branchesCovered(),
                        counters.branchesMissed(),
                        index.toAbsolutePath().normalize().toString()));
        ctx.output("coverage: jacoco " + jacoco.version() + " → " + in.dir().relativize(xml) + " · lines "
                + CoverageResults.pct(counters.linesCovered(), counters.linesMissed()) + " · branches "
                + CoverageResults.pct(counters.branchesCovered(), counters.branchesMissed()) + " · html "
                + in.dir().relativize(index));
    }

    /**
     * The resolved test runtime classpath plus own fixtures, plugin test-classpath contributions
     * (contributesTestClasspath — e.g. the android plugin's Robolectric test_config dir), and the
     * provided platform (android.jar) LAST: unit tests calling framework stubs get the platform's
     * throw-on-call contract (AGP's default posture), and anything real on the classpath shadows it.
     */
    static List<Path> testRuntimeClasspath(TaskContext ctx, PluginBuild.@Nullable Declarations pluginDecls)
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
    static WorkerEnv testEnvironment(TaskContext ctx, BuildPlanner.Inputs in, JkBuild projectUnderTest)
            throws Exception {
        WorkerEnv testEnv = TestEnv.forModule(projectUnderTest, in.dir(), ctx.require(LAYOUT));
        if (needsNestedEngineIsolation(projectUnderTest)) {
            testEnv = testEnv.with(nestedEngineTestEnv(in.dir()));
        }
        PlannerSupport.stageSiblingRulePacks(in.dir(), projectUnderTest, testEnv.extras());
        return testEnv;
    }

    /**
     * The default tier's wall against Maven Central. A launch that includes no tag and excludes at
     * least one is the module's plain {@code jk test} tier, the one {@code jk build} runs, and its
     * JVMs — and any engine they nest — find {@code repo.maven.apache.org} and its failover mirror
     * refused by {@link Http} with the host named. A profile that includes a tag names a tier that may
     * fetch, and a module that sets {@link Http#DENY_HOSTS_ENV} in {@code [test] env} keeps its value.
     */
    static WorkerEnv withHostWall(WorkerEnv testEnv, TestSelection sel) {
        if (!sel.includeTags().isEmpty() || sel.excludeTags().isEmpty()) return testEnv;
        if (testEnv.extras().containsKey(Http.DENY_HOSTS_ENV)) return testEnv;
        return testEnv.with(Map.of(Http.DENY_HOSTS_ENV, Http.centralHosts()));
    }

    /**
     * Incremental test skip: a content key over every input that affects the outcome — own main
     * output, test sources, the *content* of the runtime classpath (sibling modules included), the
     * lock, and the toolchain/runner/plugin identity. Unchanged → skip the runner. Null only when
     * computeKey failed open (unreadable input) — the caller treats that as "not cached".
     */
    static @Nullable String stampKey(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            List<Path> testSrcs,
            List<Path> testResDirs,
            List<Path> testRtCp,
            List<String> extras)
            throws Exception {
        String stampKey =
                TestStamp.computeKey(testSrcs, ctx.require(MAIN_CLASSES), testResDirs, in.lockFile(), testRtCp, extras);
        if (Perf.enabled()) {
            Perf.note(
                    "live-test-stamp " + in.dir(),
                    "key",
                    stampKey,
                    "src",
                    testSrcs.size(),
                    "res",
                    testResDirs.size(),
                    "rt",
                    testRtCp.size(),
                    "extras",
                    extras.size(),
                    "X",
                    extras,
                    "cpFp",
                    ClasspathFingerprint.of(testRtCp),
                    "mainFp",
                    ClasspathFingerprint.entry(ctx.require(MAIN_CLASSES)));
        }
        return stampKey;
    }

    /**
     * The "tests passed for this input" marker lives in the CAS (keyed by the content key), NOT in
     * target/ — so it survives {@code jk clean}: a later build that restores byte-identical classes
     * recomputes the same key and skips the runner, mirroring how the compile cache survives clean.
     * True when a green marker was found and the step marked itself skipped.
     */
    static boolean replayGreenRun(TaskContext ctx, ActionCache actionCache, String stampKey) throws IOException {
        var marker = actionCache.lookup(stampKey);
        if (marker.isEmpty() || !TestStamp.green(marker.get())) return false;
        ctx.reweight(EffortWeights.TOKEN); // cache/stamp skip — token tick
        // A run that found no test is not up-to-date tests; it is a module with none to run.
        ctx.label(TestStamp.noTests(marker.get()) ? "no tests" : "tests up-to-date");
        ctx.cached();
        // Replay the green run's counts (stored on the marker) so the summary
        // line reads "Passed N tests", not "No tests" — without this a
        // legitimate skip was indistinguishable from a module with no test
        // sources. Markers written before counts were stored replay nothing;
        // the next real run upgrades them.
        TestSummary previous = PlannerTest.stampedSummary(marker.get());
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
    static void reweightForRealRun(TaskContext ctx, BuildPlanner.Inputs in) {
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
    static List<Path> testRuntimeCpWithLanguageRuntimes(
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
    static int dispatchWorkers(BuildPlanner.Inputs in, JkBuild.Build module) {
        int planned = module.effectiveTestWorkers(in.workerCount());
        boolean pinned = module.effectiveTestWorkers(0) > 0 || in.session().requestedTestWorkers() > 0;
        if (pinned) return planned;
        return TestWorkers.liveShare(planned, TestWorkers.effectiveJobs(), LiveUnits.running());
    }

    /**
     * The launcher for this module's suite: its label, the selection's tag and class filters, the
     * module's {@code [test] serial-tags} (those classes run on one trailing worker while the rest
     * shard) and assertions, the fork's JVM args, the debug port, the coverage agent when the run
     * measures coverage, and the affected class names when the session ranked them.
     */
    static JUnitLauncher launcher(
            BuildPlanner.Inputs in,
            JkBuild projectUnderTest,
            TestSelection effectiveSel,
            List<String> testJvmArgs,
            AffectedTestRun.@Nullable Outcome affected,
            CoverageTools.@Nullable Jacoco jacoco,
            @Nullable Path coverageExec) {
        String moduleLabel = projectUnderTest.project().group() + ":"
                + projectUnderTest.project().name();
        JUnitLauncher launcher = new JUnitLauncher()
                .withModuleLabel(moduleLabel)
                .withTagFilters(effectiveSel.includeTags(), effectiveSel.excludeTags())
                .withSerialTags(projectUnderTest.build().testSerialTags())
                .withAssertions(projectUnderTest.build().testAssertions())
                .withJvmArgs(testJvmArgs)
                .withClassPatterns(effectiveSel.classes())
                .withDebug(in.session().debugJvm());
        if (jacoco != null && coverageExec != null) {
            launcher.withCoverage(new CoverageAgent(jacoco.agentJar(), coverageExec));
        }
        if (affected != null) launcher.withClassNames(affected.classNames());
        return launcher;
    }

    /**
     * Runs the suite; the caller holds the serial-test gate when the session asks for one. The
     * module's sandbox slots are held for the run so no reaper takes the jars the fork reads.
     */
    static TestSummary launch(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JUnitLauncher launcher,
            List<Path> runtimeCp,
            int testWorkers,
            Map<String, String> workerJars,
            WorkerEnv testEnv,
            TestProgressListener listener)
            throws Exception {
        try (TestHomes.Hold held = TestEnv.holdSandboxes(in.dir())) {
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
     * Skipped only when the key failed open, or when the run is no evidence — see
     * {@link #stampFor}.
     */
    static void recordOutcome(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            ActionCache actionCache,
            String testTaskId,
            @Nullable String stampKey,
            TestSummary result,
            boolean testSourcesExist,
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
        if (stampKey == null) return;
        switch (PlannerTest.stampFor(result, testSourcesExist)) {
            case GREEN ->
                actionCache.storeWithOutputs(
                        testTaskId,
                        stampKey,
                        Map.of(),
                        TestStamp.outcome(result.total(), result.succeeded(), result.skipped(), 0));
            case NO_TESTS -> actionCache.storeWithOutputs(testTaskId, stampKey, Map.of(), TestStamp.noTestsOutcome());
            case NONE -> {}
        }
    }
}
