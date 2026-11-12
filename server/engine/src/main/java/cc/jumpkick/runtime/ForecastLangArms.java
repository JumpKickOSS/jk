// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KotlinClasspathAbi;
import cc.jumpkick.task.LangCompile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The stamp-language arms of one module's forecast: compile-kotlin and compile-groovy priced the
 * way the build decides them — the freshness stamp first, then the action key of the request the
 * build itself constructs — with the module's dirtiness and its dependencies' hint carried over
 * from the module's {@link ForecastScope}.
 */
final class ForecastLangArms {
    private final ActionCache actionCache;
    private final boolean compileDepDirty;
    private final boolean force;
    private final TaskForecaster.DepHint depHint;

    /** The compile classpath as the build will read it — a wiped sibling tree by its restored token. */
    private final ActionKey.EntryToken classpathToken;

    /** The Kotlin reading of the same classpath: memo only, a wiped sibling under its restored identity. */
    private final KotlinClasspathAbi.Snapshotter snapshotter;

    ForecastLangArms(
            ActionCache actionCache,
            boolean compileDepDirty,
            boolean force,
            TaskForecaster.DepHint depHint,
            RestoredOutputs restored) {
        this.actionCache = actionCache;
        this.compileDepDirty = compileDepDirty;
        this.force = force;
        this.depHint = depHint;
        this.classpathToken = restored.abiToken();
        this.snapshotter = restored.kotlinSnapshotter();
    }

    /**
     * A stamp-language compile step with the action key it was priced by — the key of the record
     * a wiped output tree is projected from. Null when the step was answered without a key (a
     * fresh stamp, an unresolvable toolchain).
     */
    record LangStep(TaskForecast.Task task, @Nullable String key) {}

    /**
     * compile-kotlin priced by the {@code forKotlinc} key of the request {@code
     * PlannerLang.kotlinWorker} builds for the build — CACHED when that record is present, a full
     * compile otherwise. Read-only: a classpath token not yet memoized keys on content here and
     * misses, which is the pessimistic answer; a toolchain that cannot be resolved without a
     * fetch forecasts a full compile for the same reason.
     */
    LangStep kotlinStep(CompileInputs.Module module, CompileInputs.Kotlin inputs) {
        List<Path> ktSrc = inputs.sources();
        String taskId = CompileInputs.kotlinTaskId(module);
        String key;
        KotlincRequest request;
        try {
            request = CompileInputs.kotlinWorker(module, inputs).request();
            key = LangCompile.kotlinKey(taskId, request, BuildIdentity.cacheKeyVersion(), snapshotter);
        } catch (Exception e) {
            Log.debug("kotlinStep: the Kotlin toolchain could not be resolved read-only", e);
            return new LangStep(
                    new TaskForecast.Task(
                            TaskNames.COMPILE_KOTLIN,
                            TaskForecast.Status.FULL,
                            "full compile · " + ForecastSteps.count(ktSrc.size(), "source") + " · toolchain unresolved",
                            null),
                    null);
        }
        boolean hit = ForecastSteps.present(actionCache, key);
        String why = "";
        if (!hit) {
            try {
                why = ForecastSteps.langMissReason(
                        actionCache,
                        taskId,
                        CompileInputs.kotlinStateDir(module),
                        ActionKey.kotlincInputs(request, snapshotter));
            } catch (IOException e) {
                Log.debug("kotlinStep: no miss reason", e);
            }
        }
        return new LangStep(
                ForecastSteps.langCompileStep(
                        TaskNames.COMPILE_KOTLIN, hit, key, ktSrc.size(), compileDepDirty, force, why, depHint),
                key);
    }

    /**
     * The build's compile-kotlin stamp check, from the same derivation: the kotlinc config's digest,
     * the ABI token line of each compile-classpath entry, and the sources the compile stamps — the
     * Kotlin sources plus, in a mixed module, the Java sources kotlinc reads. Read-only — a token
     * not yet memoized keys on content here, which can only make the forecast say "not fresh"
     * where the build, after one snapshot, would say fresh.
     */
    boolean kotlinStampFresh(CompileInputs.Module module, CompileInputs.Kotlin inputs) throws Exception {
        return StepWarnings.kept(module.layout(), TaskNames.COMPILE_KOTLIN)
                && CompileInputs.kotlinStampFresh(
                        module, inputs, PlannerLang.kotlinStampTokens(inputs.classpath(), snapshotter))
                // As the build: a fresh stamp over a partly wiped tree is not up to date.
                && CompileInputs.outputsOnDisk(actionCache, module, BuildStamps.KOTLIN);
    }

    /**
     * compile-groovy priced the way the build decides it: the freshness stamp first — the
     * Groovy sources (plus the Java sources joint mode reads), the request's classpath token
     * lines and the toolchain digest, written where write-stamp-groovy writes them — then the
     * {@code forGroovyc} key of the very request {@code PlannerLang.groovyRequest} builds for the
     * build. A body-only edit upstream therefore forecasts CACHED once the sibling's ABI token is
     * known, as the build answers it. A toolchain that cannot be resolved read-only forecasts a
     * full compile: the pessimistic answer, never a false hit.
     */
    LangStep groovyStep(CompileInputs.Module module, CompileInputs.Groovy inputs) throws Exception {
        BuildLayout layout = module.layout();
        List<Path> gvSrc = inputs.sources();
        String full = "full compile · " + ForecastSteps.count(gvSrc.size(), "source");
        GroovycRequest req;
        try {
            req = CompileInputs.groovyRequest(module, inputs);
        } catch (Exception e) {
            Log.debug("groovyStep: the Groovy toolchain could not be resolved read-only", e);
            // Without the toolchain the request cannot be keyed: the stamp's presence and the
            // sources' mtimes are the evidence left, and a stamp that vouches for every source the
            // compile read is the same answer the build's own stamp check gives.
            boolean fresh = !compileDepDirty
                    && !force
                    && StepWarnings.kept(layout, TaskNames.COMPILE_GROOVY)
                    && FreshnessStamp.looksFresh(layout.compileStampDir(), BuildStamps.GROOVY, inputs.stampInputs());
            return new LangStep(
                    fresh
                            ? new TaskForecast.Task(TaskNames.COMPILE_GROOVY, TaskForecast.Status.CACHED, "", null)
                            : new TaskForecast.Task(
                                    TaskNames.COMPILE_GROOVY,
                                    TaskForecast.Status.FULL,
                                    full + " · toolchain unresolved",
                                    null),
                    null);
        }
        if (!compileDepDirty
                && !force
                && StepWarnings.kept(layout, TaskNames.COMPILE_GROOVY)
                && CompileInputs.groovyStampFresh(
                        module, inputs, ActionKey.groovycClasspathTokens(req, classpathToken))) {
            return new LangStep(
                    new TaskForecast.Task(TaskNames.COMPILE_GROOVY, TaskForecast.Status.CACHED, "", null), null);
        }
        String taskId = CompileInputs.groovyTaskId(module);
        String key = LangCompile.groovyKey(taskId, req, BuildIdentity.cacheKeyVersion(), classpathToken);
        boolean hit = ForecastSteps.present(actionCache, key);
        // groovyc keeps no incremental state, so the pointer is the only prior record.
        String why = hit ? "" : ForecastSteps.langMissReason(actionCache, taskId, null, ActionKey.snapshotInputs(req));
        return new LangStep(
                ForecastSteps.langCompileStep(
                        TaskNames.COMPILE_GROOVY, hit, key, gvSrc.size(), compileDepDirty, force, why, depHint),
                key);
    }

    /**
     * The key compile-test's kotlinc pass runs under: the request {@code TestCompileSteps}' Kotlin pass
     * builds, from the same facts. Null when the toolchain cannot be resolved read-only.
     */
    @Nullable
    String kotlinTestKey(
            CompileInputs.Module module,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            Path testClasses,
            @Nullable List<Path> javaRoots) {
        try {
            Path workingDir = ActionKey.stateDir(
                    ActionTree.INCREMENTAL_KOTLIN.under(CacheTree.ACTIONS.under(module.cache())),
                    TaskNames.COMPILE_TEST_KOTLIN,
                    testClasses);
            PlannerLang.KotlinWorker worker = PlannerLang.kotlinWorker(
                    module.project(),
                    module.dir(),
                    module.cache(),
                    module.cas(),
                    sources,
                    classpath,
                    outputDir,
                    workingDir,
                    PlannerLang.kotlinConfig(
                            module.project(),
                            module.lock(),
                            module.dir(),
                            module.release(),
                            module.javaHome(),
                            javaRoots));
            return LangCompile.kotlinKey(
                    ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_KOTLIN, testClasses),
                    worker.request(),
                    BuildIdentity.cacheKeyVersion(),
                    snapshotter);
        } catch (Exception e) {
            Log.debug("kotlinTestKey: the Kotlin toolchain could not be resolved read-only", e);
            return null;
        }
    }

    /**
     * The key compile-test's groovyc pass runs under: the request {@code TestCompileSteps}' Groovy pass
     * builds, from the same facts. Null when the toolchain cannot be resolved read-only.
     */
    @Nullable
    String groovyTestKey(
            CompileInputs.Module module,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            Path testClasses,
            @Nullable List<Path> javaRoots) {
        try {
            GroovycRequest req = PlannerLang.groovyRequest(
                    module.project(),
                    module.lock(),
                    module.dir(),
                    module.cas(),
                    sources,
                    classpath,
                    outputDir,
                    javaRoots,
                    null,
                    javaRoots == null ? List.of() : module.classpaths().processor(),
                    module.release(),
                    module.javaHome());
            return LangCompile.groovyKey(
                    ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_GROOVY, testClasses),
                    req,
                    BuildIdentity.cacheKeyVersion(),
                    classpathToken);
        } catch (Exception e) {
            Log.debug("groovyTestKey: the Groovy toolchain could not be resolved read-only", e);
            return null;
        }
    }
}
