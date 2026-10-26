// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileToolchain;
import cc.jumpkick.runtime.base.GroovyPluginSetup;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.SourceApiIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The predictors of the main compile steps: compile-java, compile-kotlin and compile-groovy, beside {@link PlannerCompile}. */
final class PlannerCompileForecast {

    private PlannerCompileForecast() {}

    /**
     * compile-java's price: the freshness stamp the live compile checks first, else {@link
     * JavaCompile#predict} on the very request the build keys — the shared {@link
     * #mainCompileRequest} over the same classpath, processors and toolchain.
     */
    static TaskForecast.@Nullable Task java(ForecastScope s) throws Exception {
        ForecastScope.Prepared prepared = s.prepared();
        List<Path> mainSrc = prepared.mainSrc();
        if (mainSrc.isEmpty()) return null;
        var lock = prepared.lock();
        BuildLayout layout = prepared.layout();
        int release = prepared.release();
        WorkspaceClasspath.Result sib = WorkspaceClasspath.resolve(s.dir, s.project, WorkspaceClasspath.COMPILE_SCOPES);
        List<Path> cp = prepared.withProvided(
                PlannerSupport.mainCompileClasspath(s.dir, s.project, lock, s.resolver, sib, false));
        s.mainCp = cp;
        Path out = layout.classesDir();
        // Same stamp gate as the live compile: a post-rebuild tree with a fresh stamp is cached
        // even when action-cache keys were not rewritten (a --rebuild skips the store). The input
        // recipe is shared with the live check and write-stamp, so a mixed module hashes the same
        // inputs here and stamp-matches.
        Path groovyJar = null;
        boolean groovyJarUnavailable = false;
        if (prepared.mixedGroovy()) {
            try {
                String groovyVersion = CompileToolchain.groovyVersionFor(lock, s.project);
                var repos = RepoGroupBuilder.buildFor(s.project, null, s.cas);
                groovyJar =
                        GroovyPluginSetup.prepare(repos, s.cas, groovyVersion).groovyJar();
            } catch (Exception e) {
                // Cannot reproduce the live stamp inputs without the jar — fall through to the
                // action-cache prediction rather than guessing.
                groovyJarUnavailable = true;
            }
        }
        // The Scala toolchain is a compile-main input on both sides: its stdlib jars are
        // freshness-stamp inputs and its version and compiler closure are hashed by
        // ActionKey.forJavac.
        ScalaCompile.Setup scalaSetup =
                mainSrc.stream().anyMatch(pth -> pth.toString().endsWith(".scala"))
                        ? ScalaCompile.prepare(s.project, lock, s.cas)
                        : null;
        CompileRequest req = PlannerCompile.mainCompileRequest(new PlannerCompile.MainCompile(
                mainSrc,
                cp,
                prepared.processorCp(),
                layout,
                out,
                release,
                prepared.javacArgs(),
                s.project.javac(),
                prepared.javaHome(),
                prepared.mixedKotlin(),
                prepared.mixedGroovy(),
                groovyJar,
                scalaSetup));
        Optional<FreshnessStamp.Stamp> fresh = Optional.empty();
        if (!s.compileDepDirty && !s.force && !groovyJarUnavailable) {
            try {
                fresh = FreshnessStamp.freshStamp(
                        out,
                        BuildStamps.JAVA,
                        mainSrc,
                        FreshnessStamp.ClasspathTokens.of(ActionKey.javacClasspathTokens(req, s.restored.abiToken())),
                        release,
                        ActionKey.javacOptionsDigest(req));
            } catch (IOException ignored) {
                fresh = Optional.empty();
            }
        }
        boolean stampFresh = fresh.isPresent();
        ForecastSteps.noteCompileMain(out, req, mainSrc.size(), release, stampFresh, s.compileDepDirty);
        if (stampFresh) {
            // The stamp names the compile that produced this tree; that record is what the tree is
            // held against and what a wiped tree would be reconstructed from.
            s.compileMainKey = fresh.map(FreshnessStamp.Stamp::actionKey)
                    .filter(k -> !k.isBlank())
                    .orElse(null);
            s.ownHint = new SourceApiIndex.Hint(SourceApiIndex.Kind.BODY_ONLY, List.of());
            return new TaskForecast.Task(TaskNames.COMPILE_JAVA, TaskForecast.Status.CACHED, "", null);
        }
        String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, out);
        Path stateDir = ActionKey.stateDir(
                ActionTree.INCREMENTAL_JAVA.under(CacheTree.ACTIONS.under(s.cache)), TaskNames.COMPILE_MAIN, out);
        long tc = Perf.start();
        var pred = JavaCompile.predict(
                taskId,
                req,
                BuildIdentity.cacheKeyVersion(),
                s.actionCache,
                stateDir,
                s.workerJar,
                layout.generatedSourcesDir("annotations"),
                WorkerEnv.forModule(s.project.build().env(), layout.moduleRoot(), layout.moduleTargetDir()),
                s.restored.abiToken());
        Perf.end("  predict-compile-main", tc);
        s.compileMainKey = pred.actionKey();
        TaskForecast.Task step =
                ForecastSteps.compileStep(TaskNames.COMPILE_JAVA, pred, s.compileDepDirty || s.force, req, s.depHint);
        if (!step.cached()) s.compileDirty = true;
        // The key hit against the dependency's current output: nothing of this module's own moved,
        // and its package and tests are dirty only for the sibling's jar bytes.
        s.depOnlyDirty = s.compileDepDirty && pred.outcome() == JavaCompile.Outcome.CACHE_HIT && !s.force;
        s.ownHint = ForecastSteps.ownApiHint(s.force, s.compileDepDirty, s.depHint, s.dir, prepared, pred);
        return step;
    }

    /**
     * compile-kotlin's price: the stamp the live step writes to the merged classes, else the
     * action key of the build's own kotlinc request — never the tasks/ pointer, whose last record
     * may belong to another edit of the sources.
     */
    static TaskForecast.@Nullable Task kotlin(ForecastScope s) throws Exception {
        ForecastScope.Prepared prepared = s.prepared();
        if (prepared.ktSrc().isEmpty()) return null;
        BuildLayout layout = prepared.layout();
        ForecastLangArms arms = s.arms();
        ForecastLangArms.KotlinArm arm = arms.kotlinArm(prepared);
        if (!s.compileDepDirty && !s.force && arms.kotlinStampFresh(prepared, arm)) {
            s.compileKotlinKey = FreshnessStamp.stampedKey(layout.compileStampDir(), BuildStamps.KOTLIN)
                    .orElse(null);
            return new TaskForecast.Task(TaskNames.COMPILE_KOTLIN, TaskForecast.Status.CACHED, "", null);
        }
        ForecastLangArms.LangStep step = arms.kotlinStep(prepared, arm);
        if (step.task().cached()) {
            s.restored.projectFromRecord(layout.kotlinClassesDir(), s.compileKotlinKey = step.key());
        } else {
            s.compileDirty = true;
            s.ownHint = SourceApiIndex.Hint.UNKNOWN;
        }
        return step.task();
    }

    /** compile-groovy's price: the stamp, then the action key of the build's own groovyc request. */
    static TaskForecast.@Nullable Task groovy(ForecastScope s) throws Exception {
        ForecastScope.Prepared prepared = s.prepared();
        if (prepared.gvSrc().isEmpty()) return null;
        BuildLayout layout = prepared.layout();
        ForecastLangArms.LangStep step = s.arms().groovyStep(prepared);
        if (step.task().cached()) {
            s.restored.projectFromRecord(layout.groovyClassesDir(), s.compileGroovyKey = step.key());
            // A fresh stamp answers without a key; the stamp still names the compile's record.
            if (s.compileGroovyKey == null) {
                s.compileGroovyKey = FreshnessStamp.stampedKey(layout.compileStampDir(), BuildStamps.GROOVY)
                        .orElse(null);
            }
        } else {
            s.compileDirty = true;
            s.ownHint = SourceApiIndex.Hint.UNKNOWN;
        }
        return step.task();
    }
}
