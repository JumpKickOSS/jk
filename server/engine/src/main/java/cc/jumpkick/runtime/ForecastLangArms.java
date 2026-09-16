// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KotlinClasspathAbi;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The stamp-language arms of one module's forecast: compile-kotlin and compile-groovy priced the
 * way the build decides them — the freshness stamp first, then the action key of the request the
 * build itself constructs — with the module's dirtiness and its dependencies' hint carried over
 * from {@link ModuleForecast}, which owns the step list and the verdicts these arms feed.
 */
final class ForecastLangArms {
    private final JkBuild project;
    private final Path dir;
    private final Path cache;
    private final Cas cas;
    private final ClasspathResolver resolver;
    private final ActionCache actionCache;
    private final boolean compileDepDirty;
    private final boolean force;
    private final TaskForecaster.DepHint depHint;

    /** The compile classpath as the build will read it — a wiped sibling tree by its restored token. */
    private final ActionKey.EntryToken classpathToken;

    /** The Kotlin reading of the same classpath: memo only, a wiped sibling under its restored identity. */
    private final KotlinClasspathAbi.Snapshotter snapshotter;

    ForecastLangArms(
            JkBuild project,
            Path dir,
            Path cache,
            Cas cas,
            ClasspathResolver resolver,
            ActionCache actionCache,
            boolean compileDepDirty,
            boolean force,
            TaskForecaster.DepHint depHint,
            RestoredOutputs restored) {
        this.project = project;
        this.dir = dir;
        this.cache = cache;
        this.cas = cas;
        this.resolver = resolver;
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

    /** The kotlinc config and compile classpath the build derives for this module. */
    record KotlinArm(PlannerLang.KotlinConfig config, List<Path> classpath, boolean mixedWithJava) {}

    KotlinArm kotlinArm(ModuleForecast.Prepared prepared) throws Exception {
        var langs = CompileSupport.resolveLanguages(project.project(), dir);
        List<Path> javaRoots = PlannerKsp.kotlinJavaSourceRoots(
                langs.java(), prepared.compact(), dir, prepared.layout(), prepared.pkgDecls());
        PlannerLang.KotlinConfig config = PlannerLang.kotlinConfig(
                project, prepared.lock(), dir, prepared.release(), prepared.javaHome(), javaRoots);
        WorkspaceClasspath.Result sib = WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.COMPILE_SCOPES);
        List<Path> cp = PlannerSupport.mainCompileClasspath(project, prepared.lock(), resolver, sib, false);
        return new KotlinArm(config, cp, langs.java());
    }

    /**
     * compile-kotlin priced by the {@code forKotlinc} key of the request {@code
     * PlannerLang.kotlinWorker} builds for the build — CACHED when that record is present, a full
     * compile otherwise. Read-only: a classpath token not yet memoized keys on content here and
     * misses, which is the pessimistic answer; a toolchain that cannot be resolved without a
     * fetch forecasts a full compile for the same reason.
     */
    LangStep kotlinStep(ModuleForecast.Prepared prepared, KotlinArm arm) {
        BuildLayout layout = prepared.layout();
        List<Path> ktSrc = prepared.ktSrc();
        String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, layout.classesDir());
        String key;
        KotlincRequest request;
        try {
            Path workingDir = ActionTree.INCREMENTAL_KOTLIN
                    .under(CacheTree.ACTIONS.under(cache))
                    .resolve(taskId);
            PlannerLang.KotlinWorker worker = PlannerLang.kotlinWorker(
                    project,
                    dir,
                    cache,
                    cas,
                    ktSrc,
                    arm.classpath(),
                    layout.kotlinClassesDir(),
                    workingDir,
                    arm.config());
            request = worker.request();
            key = ActionKey.forKotlinc(taskId, request, BuildIdentity.cacheKeyVersion(), snapshotter);
        } catch (Exception e) {
            Log.debug("kotlinStep: the Kotlin toolchain could not be resolved read-only", e);
            return new LangStep(
                    new TaskForecast.Task(
                            TaskNames.COMPILE_KOTLIN,
                            TaskForecast.Status.FULL,
                            "full compile · " + TaskForecaster.count(ktSrc.size(), "source")
                                    + " · toolchain unresolved",
                            null),
                    null);
        }
        boolean hit = TaskForecaster.present(actionCache, key);
        String why = "";
        if (!hit) {
            try {
                why = TaskForecaster.langMissReason(actionCache, taskId, ActionKey.kotlincInputs(request, snapshotter));
            } catch (IOException e) {
                Log.debug("kotlinStep: no miss reason", e);
            }
        }
        return new LangStep(
                TaskForecaster.langCompileStep(
                        TaskNames.COMPILE_KOTLIN, hit, key, ktSrc.size(), compileDepDirty || force, why, depHint),
                key);
    }

    /**
     * The build's compile-kotlin stamp check, from the same derivation: the kotlinc config's digest,
     * the ABI token line of each compile-classpath entry, and the sources the compile stamps — the
     * Kotlin sources plus, in a mixed module, the Java sources kotlinc reads. Read-only — a token
     * not yet memoized keys on content here, which can only make the forecast say "not fresh"
     * where the build, after one snapshot, would say fresh.
     */
    boolean kotlinStampFresh(ModuleForecast.Prepared prepared, KotlinArm arm) throws Exception {
        List<Path> freshInputs = new ArrayList<>(prepared.ktSrc());
        if (arm.mixedWithJava()) freshInputs.addAll(prepared.mainSrc());
        return FreshnessStamp.isFresh(
                prepared.layout().compileStampDir(),
                BuildStamps.KOTLIN,
                freshInputs,
                FreshnessStamp.ClasspathTokens.of(PlannerLang.kotlinStampTokens(arm.classpath(), snapshotter)),
                prepared.release(),
                arm.config().digest());
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
    LangStep groovyStep(ModuleForecast.Prepared prepared) throws Exception {
        BuildLayout layout = prepared.layout();
        List<Path> gvSrc = prepared.gvSrc();
        boolean mixed = prepared.mixedGroovy();
        String full = "full compile · " + TaskForecaster.count(gvSrc.size(), "source");
        GroovycRequest req;
        try {
            WorkspaceClasspath.Result sib = WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.COMPILE_SCOPES);
            List<Path> cp = PlannerSupport.mainCompileClasspath(project, prepared.lock(), resolver, sib, false);
            List<Path> javaRoots = mixed
                    ? PlannerKsp.kotlinJavaSourceRoots(true, prepared.compact(), dir, layout, prepared.pkgDecls())
                    : null;
            req = PlannerLang.groovyRequest(
                    project,
                    prepared.lock(),
                    dir,
                    cas,
                    gvSrc,
                    cp,
                    layout.groovyClassesDir(),
                    javaRoots,
                    mixed ? layout.groovyStubsDir() : null,
                    prepared.processorCp(),
                    prepared.release(),
                    prepared.javaHome());
        } catch (Exception e) {
            Log.debug("groovyStep: the Groovy toolchain could not be resolved read-only", e);
            // Without the toolchain the request cannot be keyed: the stamp's presence and the
            // sources' mtimes are the evidence left, and a stamp that vouches for every source the
            // compile read is the same answer the build's own stamp check gives.
            List<Path> inputs = new ArrayList<>(gvSrc);
            if (mixed) inputs.addAll(prepared.mainSrc());
            boolean fresh = !compileDepDirty
                    && !force
                    && FreshnessStamp.looksFresh(layout.compileStampDir(), BuildStamps.GROOVY, inputs);
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
        List<Path> freshInputs = new ArrayList<>(gvSrc);
        if (mixed) freshInputs.addAll(prepared.mainSrc());
        if (!compileDepDirty
                && !force
                && FreshnessStamp.isFresh(
                        layout.compileStampDir(),
                        BuildStamps.GROOVY,
                        freshInputs,
                        FreshnessStamp.ClasspathTokens.of(ActionKey.groovycClasspathTokens(req, classpathToken)),
                        prepared.release(),
                        PlannerLang.groovyStampDigest(
                                project, prepared.lock(), dir, prepared.release(), prepared.javaHome()))) {
            return new LangStep(
                    new TaskForecast.Task(TaskNames.COMPILE_GROOVY, TaskForecast.Status.CACHED, "", null), null);
        }
        String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, layout.classesDir());
        String key = ActionKey.forGroovyc(taskId, req, BuildIdentity.cacheKeyVersion(), classpathToken);
        boolean hit = TaskForecaster.present(actionCache, key);
        String why = hit ? "" : TaskForecaster.langMissReason(actionCache, taskId, ActionKey.snapshotInputs(req));
        return new LangStep(
                TaskForecaster.langCompileStep(
                        TaskNames.COMPILE_GROOVY, hit, key, gvSrc.size(), compileDepDirty || force, why, depHint),
                key);
    }
}
