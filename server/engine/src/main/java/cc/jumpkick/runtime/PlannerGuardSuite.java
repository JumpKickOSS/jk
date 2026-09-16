// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.CLASSPATH;
import static cc.jumpkick.runtime.BuildPlanner.COMPILE_TEST_CP;
import static cc.jumpkick.runtime.BuildPlanner.JAVAC_ARGS;
import static cc.jumpkick.runtime.BuildPlanner.JAVAC_PROCESSOR_CP;
import static cc.jumpkick.runtime.BuildPlanner.JAVA_HOME;
import static cc.jumpkick.runtime.BuildPlanner.LAYOUT;
import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROCESSOR_CP;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.RELEASE;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code compile-guard}: the guard suite ({@code src/guard/java}) compiled to a directory that is
 * never an artifact, against main classes, the test compile classpath (so ArchUnit or Konsist come
 * from {@code [test-dependencies]}) and the provisioned {@code jk-guards-junit}. Not a test: it
 * compiles whether or not tests are skipped, because the guard lanes run it. No facts pass — guard
 * tests are not guarded.
 */
public final class PlannerGuardSuite {

    private PlannerGuardSuite() {}

    public static boolean declared(Path moduleDir, boolean compact) {
        return TestSuites.hasGuardSuite(moduleDir, compact);
    }

    public static List<Path> sources(Path moduleDir, boolean compact) throws IOException {
        return TestSuites.guardSources(moduleDir, compact);
    }

    /** Forecast twin of {@link #sources}: empty on I/O rather than throwing. */
    public static List<Path> forecastSources(Path moduleDir, boolean compact) {
        try {
            return sources(moduleDir, compact);
        } catch (IOException degraded) {
            return List.of();
        }
    }

    /** compile-guard's request — the build's javac invocation and the forecast's key, from one body. */
    public static CompileRequest guardCompileRequest(
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            Path outputDir,
            int release,
            List<String> javacArgs,
            Path javaHome) {
        return CompileRequest.builder()
                .sources(sources)
                .classpath(classpath)
                .outputDir(outputDir)
                .release(release)
                .extraOptions(javacArgs)
                .javaHome(javaHome)
                .processorPath(PlannerCompile.effectiveProcessorPath(processorPath, classpath))
                .build();
    }

    /** The suite's classpath: main classes, the test compile classpath, own fixtures, the library. */
    static List<Path> classpath(JkBuild project, BuildLayout layout, List<Path> testCompileCp, Path library) {
        List<Path> cp = new ArrayList<>();
        cp.add(layout.classesDir());
        for (Path p : testCompileCp) if (!cp.contains(p)) cp.add(p);
        cp = PlannerFixtures.withOwnFixtures(project, layout, cp);
        if (!cp.contains(library)) cp.add(library);
        return cp;
    }

    /**
     * Forecast the two non-suite source sets, fixtures then guard, in one call: returns whether the
     * fixtures step is dirty (so {@code compile-test} cascades); the guard step cascades to nothing.
     */
    static boolean addSuiteForecasts(
            List<TaskForecast.Task> steps,
            boolean skipTests,
            boolean compileDirty,
            JkBuild project,
            Path dir,
            boolean compact,
            BuildLayout layout,
            List<Path> processorCp,
            int release,
            List<String> javacArgs,
            Path javaHome,
            Path cache,
            ActionCache actionCache,
            @Nullable Path workerJar,
            List<Path> testCompileCp,
            Cas cas,
            RestoredOutputs restored)
            throws IOException {
        boolean fixturesDirty = PlannerFixtures.addForecast(
                steps,
                skipTests,
                compileDirty,
                project,
                dir,
                layout,
                processorCp,
                release,
                javacArgs,
                javaHome,
                cache,
                actionCache,
                workerJar,
                testCompileCp,
                restored);
        addForecast(
                steps,
                compileDirty,
                project,
                dir,
                compact,
                layout,
                processorCp,
                release,
                javacArgs,
                javaHome,
                cache,
                actionCache,
                workerJar,
                testCompileCp,
                cas,
                restored);
        return fixturesDirty;
    }

    /** Forecast {@code compile-guard} into {@code steps}; {@code false} when the module has no suite. */
    static boolean addForecast(
            List<TaskForecast.Task> steps,
            boolean compileDirty,
            JkBuild project,
            Path dir,
            boolean compact,
            BuildLayout layout,
            List<Path> processorCp,
            int release,
            List<String> javacArgs,
            Path javaHome,
            Path cache,
            ActionCache actionCache,
            @Nullable Path workerJar,
            List<Path> testCompileCp,
            Cas cas,
            RestoredOutputs restored)
            throws IOException {
        if (!declared(dir, compact)) return false;
        if (compileDirty) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_GUARD, TaskForecast.Status.RUN, "recompile · main changed", null));
            return true;
        }
        List<Path> src = forecastSources(dir, compact);
        Path library;
        try {
            // On jk's own tree the library is a sibling's classes tree; after jk clean the build
            // restores it before this compile and keys on it, so the forecast resolves it too.
            library = GuardSuiteLibrary.locate(WorkspaceScan.findRoot(dir).orElse(dir), cas, restored::willBePresent)
                    .path();
        } catch (IOException missing) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_GUARD, TaskForecast.Status.RUN, "jk-guards-junit not staged", null));
            return true;
        }
        CompileRequest req = guardCompileRequest(
                src,
                classpath(project, layout, testCompileCp, library),
                processorCp,
                layout.guardClassesDir(),
                release,
                javacArgs,
                javaHome);
        String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_GUARD, layout.guardClassesDir());
        Path state = ActionTree.INCREMENTAL_JAVA
                .under(CacheTree.ACTIONS.under(cache))
                .resolve(taskId);
        var pred = JavaCompile.predict(
                taskId,
                req,
                BuildIdentity.cacheKeyVersion(),
                actionCache,
                state,
                workerJar,
                layout.generatedSourcesDir("annotations", "guard"),
                WorkerEnv.forModule(project.build().env(), layout.moduleRoot(), layout.moduleTargetDir()),
                restored.abiToken());
        TaskForecast.Task step = TaskForecaster.compileStep(TaskNames.COMPILE_GUARD, pred, false, req);
        steps.add(step);
        return !step.cached();
    }

    static Task compileGuardStep(BuildPlanner.Ctx cx, boolean compact) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        return Task.builder(TaskNames.COMPILE_GUARD)
                // COMPILE, not TEST: the module lane (a compile-stage step) requires it.
                .stage(BuildStage.COMPILE)
                .label("Guard Suite")
                .kind(TaskKind.CPU)
                .requires(TaskNames.BUILD_LOGIC_AFTER_COMPILE, TaskNames.RESOLVE_DEPS, TaskNames.COPY_RESOURCES)
                .weight(() -> plan.get().compileTest())
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    List<Path> sources = sources(in.dir(), compact);
                    Path out = layout.guardClassesDir();
                    if (sources.isEmpty()) {
                        Files.createDirectories(out);
                        ctx.label("no guard sources");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    Path root = WorkspaceScan.findRoot(in.dir()).orElse(in.dir());
                    Path library = GuardSuiteLibrary.locate(root, cas).path();
                    List<Path> testCp = ctx.get(COMPILE_TEST_CP).orElseGet(() -> ctx.require(CLASSPATH));
                    List<Path> classpath = classpath(project, layout, testCp, library);
                    if (!classpath.contains(ctx.require(MAIN_CLASSES))) classpath.add(0, ctx.require(MAIN_CLASSES));
                    List<Path> processorCp = ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                    CompileRequest request = guardCompileRequest(
                            sources,
                            classpath,
                            processorCp,
                            out,
                            ctx.require(RELEASE),
                            ctx.require(JAVAC_ARGS),
                            ctx.require(JAVA_HOME));
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_GUARD, out);
                    Path stateDir = ActionTree.INCREMENTAL_JAVA
                            .under(CacheTree.ACTIONS.under(in.cache()))
                            .resolve(taskId);
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun) {
                        try {
                            boolean restores = actionCache
                                    .lookup(ActionKey.forJavac(taskId, request, BuildIdentity.cacheKeyVersion()))
                                    .isPresent();
                            ctx.reweight(
                                    restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
                        } catch (Exception e) {
                            /* keep the up-front estimate */
                            Log.debug("compileGuardStep: keep the up-front estimate", e);
                        }
                    }
                    Path genDir = layout.generatedSourcesDir("annotations", "guard");
                    Files.createDirectories(genDir);
                    Path workerJar = PluginJar.JAVA_COMPILER.locate(cas);
                    ctx.label("compiling " + sources.size() + " guard sources");
                    JavaCompile.Result r = JavaCompile.run(
                            taskId,
                            request,
                            BuildIdentity.cacheKeyVersion(),
                            !rerun,
                            actionCache.cas(),
                            actionCache,
                            stateDir,
                            workerJar,
                            genDir,
                            WorkerEnv.forModule(
                                    ctx.require(PROJECT).build().env(),
                                    in.dir(),
                                    ctx.require(LAYOUT).moduleTargetDir()));
                    ctx.waited(Duration.ofMillis(r.waitMillis()));
                    boolean errored = JavacDiagnostics.report(ctx, r.diagnostics());
                    if (!r.success()) {
                        if (!errored) {
                            ctx.error(
                                    "javac",
                                    "guard suite compile failed without compiler diagnostics (outcome: " + r.outcome()
                                            + ")");
                        }
                        throw new RuntimeException("guard suite javac reported errors");
                    }
                    if (r.cacheHit()) {
                        ctx.label("cache hit " + r.actionKey().substring(0, 8));
                        ctx.cached();
                    }
                    ctx.progress(1);
                })
                .build();
    }
}
