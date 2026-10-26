// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.TestStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The predictors of compile-test and run-tests, beside {@link PlannerTest}. */
final class PlannerTestForecast {

    private PlannerTestForecast() {}

    /**
     * compile-test's price: dirty with the main compile, else {@link JavaCompile#predict} on the
     * shared {@link PlannerCompile#testCompileRequest}; a cached compile whose suites' resources
     * drifted from {@code target/test-classes} still runs, since this step copies them.
     */
    static TaskForecast.@Nullable Task compileTest(ForecastScope s) throws Exception {
        if (s.skipTests || !s.haveTests()) return null;
        s.projectOwnOutputs();
        ForecastScope.Prepared prepared = s.prepared();
        BuildLayout layout = prepared.layout();
        PlannerTest.TestSources testSources = s.testSources();
        TaskForecast.Task step;
        if (s.compileDirty) {
            // Dependency-only dirtiness: the tests recompile only if main really does.
            step = new TaskForecast.Task(
                    TaskNames.COMPILE_TEST,
                    TaskForecast.Status.RUN,
                    s.depOnlyDirty ? "recompile · " + TaskForecast.ONLY_IF_COMPILE_RUNS : "recompile · main changed",
                    null);
            s.testDirty = true;
        } else if (!testSources.javacSources().isEmpty()) {
            int release = prepared.release();
            List<Path> baseCp = new ArrayList<>();
            baseCp.add(layout.classesDir());
            baseCp.addAll(s.testCompileCp());
            baseCp = PlannerFixtures.withOwnFixtures(s.project, layout, baseCp);
            Path testOut = layout.testClassesDir();
            ScalaCompile.Setup testScala =
                    testSources.scTest().isEmpty() ? null : ScalaCompile.prepare(s.project, prepared.lock(), s.cas);
            List<Path> testSrc = testSources.javacSources();
            CompileRequest req = PlannerCompile.testCompileRequest(new PlannerCompile.TestCompile(
                    testSrc,
                    baseCp,
                    prepared.testProcessorCp(),
                    testOut,
                    s.project.javac().testRelease(release),
                    prepared.javacArgs(),
                    s.project.javac().forTests(),
                    prepared.javaHome(),
                    testScala,
                    layout.classesDir()));
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
                    baseCp.size(),
                    "src",
                    testSrc.size(),
                    "release",
                    release,
                    "out",
                    testOut);
            step = ForecastSteps.compileStep(TaskNames.COMPILE_TEST, pred, false, req);
            s.compileTestKey = pred.actionKey();
            if (!step.cached()) s.testDirty = true;
        } else {
            // Kotlin/Groovy-only tests: no content predictor — assume fresh when main is clean.
            step = new TaskForecast.Task(TaskNames.COMPILE_TEST, TaskForecast.Status.CACHED, "", null);
        }
        if (!s.compileDirty) {
            s.compileTestKotlinKey = testSources.ktTest().isEmpty()
                    ? null
                    : lastTestCompileKey(s.actionCache, TaskNames.COMPILE_TEST_KOTLIN, layout);
            s.compileTestGroovyKey = testSources.gvTest().isEmpty()
                    ? null
                    : lastTestCompileKey(s.actionCache, TaskNames.COMPILE_TEST_GROOVY, layout);
        }
        if (step.cached()
                && !s.testDirty
                && Files.isDirectory(layout.classesDir())
                && Files.isDirectory(layout.testClassesDir())
                && TaskForecaster.resourcesOutOfSync(
                        ModuleLayout.testResourcesDir(s.dir, prepared.compact()), layout.testClassesDir())) {
            return new TaskForecast.Task(
                    TaskNames.COMPILE_TEST, TaskForecast.Status.RUN, TaskForecast.TEST_RESOURCES_CHANGED, null);
        }
        return step;
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

    /**
     * run-tests' price: dirty when a compile it reads runs or a test-scope sibling is rebuilding,
     * else the green marker under the same stamp key the live step stores — the runtime classpath
     * as the run hashes it, a wiped tree read as the bytes the build restores.
     */
    static TaskForecast.@Nullable Task runTests(ForecastScope s) throws Exception {
        if (s.skipTests || !s.haveTests()) return null;
        s.projectOwnOutputs();
        ForecastScope.Prepared prepared = s.prepared();
        boolean compact = prepared.compact();
        BuildLayout layout = prepared.layout();
        int estimated = TestSupport.estimateAllSuiteTestCount(s.dir, compact);
        s.testCount = estimated;
        String tests = estimated > 0 ? "~" + ForecastSteps.count(estimated, "test") : "tests";
        // A sibling on the test classpath is rebuilding: the suite re-runs even when the main
        // compile stays cached.
        if (s.compileDirty || s.testDirty || s.testDepDirty) {
            // A suite re-run for a sibling's jar bytes alone reads as that, not as this module's
            // change: the runtime classpath is keyed on full bytes where the compile is keyed on
            // ABI, and why-rebuilt must be able to tell the two apart.
            boolean jarOnly = !s.testDirty && (s.depOnlyDirty || (!s.compileDirty && s.testDepDirty));
            String why = jarOnly ? "run tests · dependency jar changed · " + tests : "run tests · " + tests;
            return new TaskForecast.Task(TaskNames.RUN_TESTS, TaskForecast.Status.RUN, why, null);
        }
        List<Path> testRt =
                new ArrayList<>(PlannerSupport.testRuntimeClasspath(s.dir, s.project, prepared.lock(), s.resolver));
        for (Path p : prepared.provided().testRuntime()) {
            if (!testRt.contains(p)) testRt.add(p);
        }
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
        String stampKey = PlannerSupport.runTestsStampKey(
                s.dir,
                s.project,
                compact,
                layout.classesDir(),
                mainFp,
                s.lockFile,
                testRt,
                new TestStamp.CompileTestKeys(s.compileTestKey, s.compileTestKotlinKey, s.compileTestGroovyKey),
                s.restored.identity(),
                s.profileName,
                prepared.pkgDecls());
        Perf.end("  test-stamp-key", ts);
        var marker = stampKey == null
                ? Optional.<ActionCache.ActionRecord>empty()
                : ForecastSteps.presentRecord(s.actionCache, stampKey);
        boolean hit = marker.isPresent() && TestStamp.green(marker.get());
        // The same key with a red record is the one shape a live run never skips: say so, or the
        // ETA reads "only the suite is dirty" as stamp drift.
        boolean red = marker.isPresent() && !hit;
        Perf.note("forecast-test-stamp " + s.dir, "key", stampKey, "hit", hit, "red", red);
        return hit
                ? new TaskForecast.Task(TaskNames.RUN_TESTS, TaskForecast.Status.CACHED, "· " + tests, null)
                : new TaskForecast.Task(
                        TaskNames.RUN_TESTS,
                        TaskForecast.Status.RUN,
                        "run tests · " + tests + (red ? " · " + TaskForecast.LAST_RUN_FAILED : ""),
                        null);
    }
}
