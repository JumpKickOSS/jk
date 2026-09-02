// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

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
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.JavaCompile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code compile-test-fixtures}: a test-scoped source root compiled to a directory that is never an
 * artifact. Own tests and {@code fixtures = true} siblings consume that directory.
 */
public final class PlannerFixtures {

    private PlannerFixtures() {}

    public static boolean declared(JkBuild project) {
        return project != null && project.build().hasFixtures();
    }

    public static List<Path> sources(JkBuild project, Path moduleDir) throws IOException {
        String rel = project.build().fixtures();
        if (rel == null) return List.of();
        Path root = moduleDir.resolve(rel).normalize();
        if (!Files.isDirectory(root)) return List.of();
        return TestSuites.collectExt(root, ".java");
    }

    /** Forecast twin of {@link #sources}: empty on I/O rather than throwing. */
    public static List<Path> forecastSources(JkBuild project, Path moduleDir) {
        try {
            return sources(project, moduleDir);
        } catch (IOException degraded) {
            return List.of();
        }
    }

    /**
     * Append this module's fixtures output when declared. Own tests do not go through
     * {@code WorkspaceClasspath} (self is excluded).
     */
    public static List<Path> withOwnFixtures(JkBuild project, BuildLayout layout, List<Path> cp) {
        if (!declared(project)) return cp;
        Path dir = layout.testFixturesClassesDir();
        if (cp.contains(dir)) return cp;
        List<Path> out = new ArrayList<>(cp);
        out.add(dir);
        return out;
    }

    /**
     * compile-test-fixtures' request — the build's javac invocation and the forecast's key, from
     * one body.
     */
    public static CompileRequest fixturesCompileRequest(
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
                .processorPath(processorPath)
                .build();
    }

    /**
     * Forecast {@code compile-test-fixtures} into {@code steps}. Returns whether that step is
     * dirty, so {@code compile-test} / {@code run-tests} cascade.
     */
    static boolean addForecast(
            List<TaskForecast.Task> steps,
            boolean skipTests,
            boolean compileDirty,
            JkBuild project,
            Path dir,
            BuildLayout layout,
            List<Path> processorCp,
            int release,
            List<String> javacArgs,
            Path javaHome,
            Path cache,
            ActionCache actionCache,
            Path workerJar,
            List<Path> testCompileCp)
            throws IOException {
        if (!declared(project) || skipTests) return false;
        if (compileDirty) {
            steps.add(new TaskForecast.Task(
                    TaskNames.COMPILE_TEST_FIXTURES, TaskForecast.Status.RUN, "recompile · main changed", null));
            return true;
        }
        List<Path> fixtureSrc = forecastSources(project, dir);
        if (fixtureSrc.isEmpty()) {
            steps.add(new TaskForecast.Task(TaskNames.COMPILE_TEST_FIXTURES, TaskForecast.Status.CACHED, "", null));
            return false;
        }
        List<Path> fxCp = new ArrayList<>();
        fxCp.add(layout.classesDir());
        fxCp.addAll(testCompileCp);
        CompileRequest fxReq = fixturesCompileRequest(
                fixtureSrc, fxCp, processorCp, layout.testFixturesClassesDir(), release, javacArgs, javaHome);
        String fxTaskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_FIXTURES, layout.testFixturesClassesDir());
        Path fxState = ActionTree.INCREMENTAL_JAVA
                .under(CacheTree.ACTIONS.under(cache))
                .resolve(fxTaskId);
        var fxPred = JavaCompile.predict(
                fxTaskId,
                fxReq,
                BuildIdentity.cacheKeyVersion(),
                actionCache,
                fxState,
                workerJar,
                layout.generatedSourcesDir("annotations", "fixtures"));
        TaskForecast.Task fxStep = TaskForecaster.compileStep(TaskNames.COMPILE_TEST_FIXTURES, fxPred, false);
        steps.add(fxStep);
        return !fxStep.cached();
    }

    static Task compileTestFixturesStep(BuildPlanner.Ctx cx) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        return Task.builder(TaskNames.COMPILE_TEST_FIXTURES)
                .stage(BuildStage.TEST)
                .label("Test Fixtures")
                .kind(TaskKind.CPU)
                .requires(TaskNames.BUILD_LOGIC_AFTER_COMPILE, TaskNames.RESOLVE_DEPS, TaskNames.COPY_RESOURCES)
                .weight(() -> plan.get().compileTest())
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    List<Path> sources = sources(project, in.dir());
                    Path out = ctx.require(LAYOUT).testFixturesClassesDir();
                    if (sources.isEmpty()) {
                        Files.createDirectories(out);
                        ctx.label("no fixture sources");
                        ctx.cached();
                        ctx.progress(1);
                        return;
                    }
                    List<Path> compileCp = ctx.require(COMPILE_TEST_CP);
                    List<Path> classpath = new ArrayList<>();
                    classpath.add(ctx.require(MAIN_CLASSES));
                    classpath.addAll(compileCp);
                    List<Path> processorCp = ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                    List<String> javacArgs = ctx.require(JAVAC_ARGS);
                    CompileRequest request = fixturesCompileRequest(
                            sources,
                            classpath,
                            processorCp,
                            out,
                            ctx.require(RELEASE),
                            javacArgs,
                            ctx.require(JAVA_HOME));
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_FIXTURES, out);
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
                        } catch (Exception ignored) {
                            /* keep the up-front estimate */
                        }
                    }
                    Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations", "fixtures");
                    Files.createDirectories(genDir);
                    Path workerJar = PluginJar.JAVA_COMPILER.locate(cas);
                    ctx.label("compiling " + sources.size() + " fixture sources");
                    JavaCompile.Result r = JavaCompile.run(
                            taskId,
                            request,
                            BuildIdentity.cacheKeyVersion(),
                            !rerun,
                            actionCache.cas(),
                            actionCache,
                            stateDir,
                            workerJar,
                            genDir);
                    boolean errored = false;
                    for (CompileResult.Diagnostic d : r.diagnostics()) {
                        if (d.severity() == CompileResult.Severity.ERROR) {
                            ctx.error("javac", d.describe());
                            errored = true;
                        } else {
                            ctx.warn("javac", d.describe());
                        }
                    }
                    if (!r.success()) {
                        if (!errored) {
                            ctx.error(
                                    "javac",
                                    "fixture compile failed without compiler diagnostics (outcome: "
                                            + r.outcome()
                                            + ")");
                        }
                        throw new RuntimeException("fixture javac reported errors");
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
