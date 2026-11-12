// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerCompile.compileLabel;
import static cc.jumpkick.runtime.PlannerCompile.dropOutputOfRemovedSources;
import static cc.jumpkick.runtime.PlannerCompile.incremental;
import static cc.jumpkick.runtime.PlannerCompile.mainCompileRequest;
import static cc.jumpkick.runtime.PlannerCompile.warnMovedSources;
import static cc.jumpkick.runtime.PlannerNative.javaSources;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.CompilerJdk;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.ProcessedResources;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClassAbi;
import cc.jumpkick.task.CompileStamp;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.MainAbi;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.test.AbiIndex;
import cc.jumpkick.test.AffectedChangedPublish;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One plan's compile-java: the step, its body over the module's Java and Scala sources, and its
 * predictor beside it. Built once per plan from what its {@link BuildPlanner.Ctx} holds; the
 * Kotlin and Groovy compiles are {@link LangCompileSteps}, and the requests both sides derive are
 * {@link PlannerCompile}'s.
 */
final class CompileSteps {

    private final BuildPlanner.Inputs in;
    private final Cas cas;
    private final ActionCache actionCache;
    private final Supplier<EffortWeights.Plan> plan;
    private final AtomicReference<@Nullable List<Path>> javaMainSrcRef;
    private final Path javaMainSrcDir;
    private final ModuleShape shape;
    private final boolean mixed;
    private final boolean mixedGroovy;
    private final boolean ksp;
    private final @Nullable PluginDeclarations pluginDecls;

    CompileSteps(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        this.in = cx.in();
        this.cas = cx.cas();
        this.actionCache = cx.actionCache();
        this.plan = cx.plan();
        this.javaMainSrcRef = cx.javaMainSrcRef();
        this.javaMainSrcDir = cx.javaMainSrcDir();
        this.shape = cx.shape();
        this.mixed = cx.mixed();
        this.mixedGroovy = cx.mixedGroovy();
        this.ksp = cx.ksp();
        this.pluginDecls = pluginDecls;
    }

    Task compileJava() {
        return Task.builder(TaskNames.COMPILE_JAVA)
                .forecast(StepForecast.priced(this::forecastJava))
                .stage(BuildStage.COMPILE)
                .label("Compiling")
                .kind(TaskKind.CPU)
                .requires(PlannerCompile.javaCompileRequires(mixed, mixedGroovy, pluginDecls, ksp))
                // Ticks count sources (granularity); weight is the bar share. javac
                // is opaque — one progress(sources.size) on completion — so ease the
                // slice forward over time while it runs instead of sitting flat.
                .weight(() -> plan.get().compileJava())
                .interpolated()
                .ticks(() -> {
                    // Populate the cache if not yet done (may have been filled by
                    // parse-build execute or by EffortWeights.predict via collectJavaSources).
                    List<Path> srcs = javaMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectJavaSources(javaMainSrcDir);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        javaMainSrcRef.compareAndSet(null, srcs);
                        List<Path> published = javaMainSrcRef.get();
                        if (published != null) srcs = published;
                    }
                    return srcs.size();
                })
                .execute(this::runCompileJava)
                .build();
    }

    /** The compile-java body: the source union, the freshness stamp, javac in the worker, the ABI index. */
    private void runCompileJava(TaskContext ctx) throws Exception {
        Path classes = ctx.require(MAIN_CLASSES);
        // javac always writes to the canonical classes dir (java/main/).
        // The Kotlin incremental compiler gets its own dir (kotlin/main/)
        // so it cannot prune Java's output; the assembler merges both.
        Path javaOut = classes;
        // JAVA_SOURCES already carries the java+scala union (incl. extra-src/plugin-root .scala)
        // that parse-build published: the Scala toolchain is decided from it.
        List<Path> declared = javaSources(ctx);
        List<Path> sources = PlannerCompile.mainJavaSources(declared, ctx.require(LAYOUT), pluginDecls, ksp);
        if (sources != declared) {
            // Re-publish the union: the steps after the compile read the set javac compiled.
            ctx.put(JAVA_SOURCES, sources);
        }
        if (sources.isEmpty()) {
            ctx.label("no Java sources");
            StepWarnings.clear(ctx, TaskNames.COMPILE_JAVA);
            if (dropOutputOfRemovedSources(javaOut)) ResourceMirror.relay(javaOut, incremental(ctx));
            Files.createDirectories(javaOut);
            ctx.put(JAVA_COMPILE, CompileStamp.noSources());
            return;
        }
        boolean rerun = in.session().config().rebuildOr(false);
        // Resolved before the stamp check, the Scala toolchain over the merged source set
        // included: a scala-version bump or a variant-overlay .scala must move the request the
        // stamp is derived from.
        CompileInputs.Module module = compileInputs(ctx, in, cas, shape, pluginDecls);
        CompileRequest request =
                mainCompileRequest(CompileInputs.java(module, sources, CompileInputs.groovyJar(module)));
        // The stamp gates on the option-bearing inputs too — [build] lint/debug, [javac] args and
        // plugins, --profile args, the JDK — through the digest of the very request the action
        // key hashes, and on the classpath through the token lines that key hashes (the ABI of
        // each compile-classpath entry, the content of each processor). write-stamp records the
        // same digest and lines, so an option edit with untouched sources reads stale here instead
        // of packaging the old classes, and a sibling's body-only rewrite reads fresh.
        String optionsDigest = ActionKey.javacOptionsDigest(request);
        List<String> stampTokens = ActionKey.javacClasspathTokens(request);
        if (Perf.enabled()) {
            Perf.note(
                    "live-compile-main " + javaOut,
                    "optionsDigest",
                    optionsDigest,
                    "cp",
                    request.classpath().size(),
                    "pp",
                    request.processorPath().size(),
                    "src",
                    sources.size(),
                    "release",
                    ctx.require(RELEASE),
                    "javaHome",
                    request.javaHome(),
                    "pp-list",
                    request.processorPath());
        }
        // A fresh stamp vouches for the inputs, not for the tree: one that lost an output its
        // compile record owns falls through to the action cache, whose hit restores the record's
        // whole tree, instead of packaging the subset it has; nor for warnings without their file.
        Optional<List<CompileResult.Diagnostic>> kept = StepWarnings.read(ctx, TaskNames.COMPILE_JAVA, sources, rerun);
        if (kept.isPresent()
                && CompileInputs.javaStamp(actionCache, module, request, stampTokens)
                        .isPresent()) {
            ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
            JavacDiagnostics.report(ctx, request.classpath(), ClasspathResolver.COMPILE_MAIN, List.of(), kept.get());
            ctx.label("up to date");
            ctx.cached();
            ctx.put(JAVA_COMPILE, CompileStamp.upToDate());
            ctx.progress(sources.size());
            return;
        }
        String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, javaOut);
        Path javaStateDir = ActionKey.stateDir(
                ActionTree.INCREMENTAL_JAVA.under(CacheTree.ACTIONS.under(in.cache())),
                TaskNames.COMPILE_MAIN,
                javaOut);
        // The stamp's instant: before the first source is hashed, so an edit landing while the
        // compile runs is stale on the next build instead of vouched for by a later stamp.
        long clock = FreshnessStamp.clockNow(javaOut);
        if (!rerun) reweightForActionCache(ctx, actionCache, taskId, request, stampTokens, sources.size());
        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations");
        Files.createDirectories(genDir);
        Path workerJar = PluginJar.JAVA_COMPILER.locate(cas);
        ctx.label("compiling " + sources.size() + " sources");
        Path abiFile = AbiIndex.path(ctx.require(LAYOUT).buildDir());
        Map<String, ClassAbi.Fingerprint> preAbi = AbiIndex.load(abiFile);
        JavaCompile.Result r = JavaCompile.run(
                taskId,
                compileLabel(ctx, TaskNames.COMPILE_MAIN),
                request,
                BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                actionCache.cas(),
                actionCache,
                javaStateDir,
                workerJar,
                genDir,
                WorkerEnv.forModule(
                        ctx.require(PROJECT).build().env(),
                        in.dir(),
                        ctx.require(LAYOUT).moduleTargetDir()),
                stampTokens);
        HeapNotes.flush(ctx);
        ctx.waited(Duration.ofMillis(r.waitMillis()));
        reportJavacResult(ctx, request, r);
        afterJavac(ctx, request, r);
        ctx.put(JAVA_COMPILE, new CompileStamp(r.outcome(), sources, optionsDigest, stampTokens, clock, r.actionKey()));
        ctx.put(MAIN_ABI, new MainAbi(preAbi, r.compiledSources()));
        advanceAbiIndex(ctx, in, r, abiFile, preAbi);
        advanceSourceApiIndex(in.dir(), ctx.require(LAYOUT).buildDir(), r, sources);
        ctx.progress(sources.size());
    }

    /**
     * After a compile-main that succeeded: the sources edited while it ran, and which resource
     * copies its processors rewrote — the resource copy leaves those next time.
     */
    private void afterJavac(TaskContext ctx, CompileRequest request, JavaCompile.Result r) throws IOException {
        warnMovedSources(ctx, r.movedSources());
        ProcessedResources.afterCompile(
                Objects.requireNonNull(request.outputDir(), "compile-main writes a classes tree"),
                incremental(ctx),
                request.resourcesDigest() != null,
                actionCache,
                r.cacheHit() ? r.actionKey() : null);
    }

    /**
     * Reweight the bar slice now that the real request is known: a CAS action-cache hit means a
     * cheap hard-link restore (3), not a full javac (ceil(sources × 0.1)). Uses the exact key
     * JavaCompile will look up, so the estimate matches what actually happens — no plan-start
     * reconstruction divergence.
     */
    private static void reweightForActionCache(
            TaskContext ctx,
            ActionCache actionCache,
            String taskId,
            CompileRequest request,
            List<String> classpathTokens,
            int sources) {
        try {
            boolean restores = actionCache
                    .lookup(JavaCompile.key(taskId, request, BuildIdentity.cacheKeyVersion(), classpathTokens))
                    .isPresent();
            ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources));
        } catch (Exception e) {
            /* keep the up-front estimate */
            Log.debug("reweightForActionCache: keep the up-front estimate", e);
        }
    }

    /**
     * Forward every javac diagnostic to the terminal, by severity: errors fail the build,
     * warnings/notes (e.g. deprecation) are surfaced but don't. Strip the leading severity word —
     * the console renderer adds its own ✗/⚠ marker.
     */
    private static void reportJavacResult(TaskContext ctx, CompileRequest request, JavaCompile.Result r) {
        boolean errored = JavacDiagnostics.report(
                ctx, request.classpath(), ClasspathResolver.COMPILE_MAIN, r.diagnostics(), r.replayed());
        if (!r.success()) {
            // Never fail silently: if no ERROR diagnostic surfaced (crash,
            // swallowed output), say so explicitly.
            if (!errored) {
                ctx.error("javac", "compile failed without compiler diagnostics (outcome: " + r.outcome() + ")");
            }
            throw new RuntimeException("javac reported errors");
        }
        StepWarnings.keep(ctx, TaskNames.COMPILE_JAVA, JavacDiagnostics.TOOL, r.warnings());
        if (r.cacheHit()) {
            ctx.label("cache hit " + r.actionKey().substring(0, 8));
            ctx.cached();
        } else {
            // What the compile did, as its record's detail: the ETA prices an incremental compile
            // from incremental history, not from an average that full compiles dominate.
            ctx.label(TaskNames.compiledLabel(
                    r.compiledSources().size(), request.sources().size()));
        }
    }

    /**
     * The abi idx advances by exactly what this compile did: a cache hit or no-op leaves it alone
     * (the restored classes were indexed when first compiled), an incremental compile re-hashes
     * only its compiled sources' classes, and only a missing/empty idx pays the full tree scan.
     * Then the cross-module --affected publish: this module's changed types while the pre-compile
     * baseline is still in memory; dependents rank against them.
     */
    private static void advanceAbiIndex(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            JavaCompile.Result r,
            Path abiFile,
            Map<String, ClassAbi.Fingerprint> preAbi)
            throws Exception {
        Path mainClasses = ctx.require(MAIN_CLASSES);
        Map<String, ClassAbi.Fingerprint> currentAbi;
        if (!preAbi.isEmpty() && r.compiledSources().isEmpty()) {
            currentAbi = preAbi;
        } else if (!preAbi.isEmpty()) {
            currentAbi = AbiIndex.updated(preAbi, in.dir(), r.compiledSources(), mainClasses);
            AbiIndex.write(abiFile, currentAbi);
        } else {
            currentAbi = AbiIndex.scanClasses(mainClasses);
            if (!currentAbi.isEmpty()) AbiIndex.write(abiFile, currentAbi);
        }
        AffectedChangedPublish.publish(in.session(), in.dir(), preAbi, currentAbi);
    }

    /**
     * The declaration baseline {@code jk explain} hints from ({@link SourceApiIndex}): advanced by
     * what this compile did, like the ABI index, and never allowed to fail a build — a missing
     * baseline only costs the hint.
     */
    private static void advanceSourceApiIndex(Path moduleDir, Path buildDir, JavaCompile.Result r, List<Path> sources) {
        try {
            Path file = SourceApiIndex.path(buildDir);
            Map<String, SourceApiIndex.Row> pre = SourceApiIndex.load(file);
            Map<String, SourceApiIndex.Row> cur = SourceApiIndex.updated(pre, moduleDir, r.compiledSources(), sources);
            if (!cur.equals(pre)) SourceApiIndex.write(file, cur);
        } catch (IOException | RuntimeException e) {
            Log.debug("advanceSourceApiIndex: the hint baseline was not advanced", e);
        }
    }

    /**
     * The facts every main compile of this module reads, from the plan's state: what {@link
     * CompileInputs} derives each compiler's inputs from, as the forecast derives them from its
     * prepared module.
     */
    static CompileInputs.Module compileInputs(
            TaskContext ctx,
            BuildPlanner.Inputs in,
            Cas cas,
            ModuleShape shape,
            @Nullable PluginDeclarations pluginDecls) {
        JkBuild project = ctx.require(PROJECT);
        return new CompileInputs.Module(
                project,
                in.dir(),
                in.cache(),
                cas,
                ctx.require(LOCKFILE),
                ctx.require(LAYOUT),
                shape,
                shape.ksp(),
                ctx.require(CLASSPATHS),
                pluginDecls,
                ctx.require(RELEASE),
                ctx.require(JAVA_HOME),
                ctx.require(JAVAC_ARGS),
                CompilerJdk.pinned(project, in.session()));
    }

    /** The Java union compile-java compiles, as a step that runs before it reads it. */
    static List<Path> mainJavaSources(TaskContext ctx, @Nullable PluginDeclarations pluginDecls, boolean ksp)
            throws IOException {
        return PlannerCompile.mainJavaSources(javaSources(ctx), ctx.require(LAYOUT), pluginDecls, ksp);
    }

    // ---- predictor ------------------------------------------------------------------------

    /**
     * The facts every main compile of the module reads, from its prepared module: what {@link
     * CompileInputs} derives each compiler's inputs from, as the build's steps derive them from the
     * plan's state.
     */
    static CompileInputs.Module forecastInputs(ForecastScope s, ForecastScope.Prepared prepared) {
        ForecastScope.Parsed parsed = prepared.parsed();
        return new CompileInputs.Module(
                s.project,
                s.dir,
                s.cache,
                s.cas,
                parsed.lock(),
                parsed.layout(),
                parsed.shape(),
                prepared.ksp(),
                prepared.classpaths(),
                parsed.pkgDecls(),
                parsed.release(),
                prepared.javaHome(),
                parsed.javacArgs(),
                CompilerJdk.pinned(s.project, SessionContext.current()));
    }

    /**
     * compile-java's price: the freshness stamp the live compile checks first, else {@link
     * JavaCompile#predict} on the very request the build keys, from {@link CompileInputs#java}.
     */
    private TaskForecast.@Nullable Task forecastJava(StepForecast.Scope scope) throws Exception {
        ForecastScope s = ForecastScope.of(scope);
        ForecastScope.Prepared prepared = s.prepared();
        List<Path> mainSrc = prepared.parsed().mainSrc();
        if (mainSrc.isEmpty()) return null;
        KspForecast.Round round = prepared.mainRound();
        if (round.runs() && round.rewrites(mainSrc)) return forecastKspRewrite(s, TaskNames.COMPILE_JAVA);
        BuildLayout layout = prepared.parsed().layout();
        int release = prepared.parsed().release();
        Path out = layout.classesDir();
        // Same stamp gate as the live compile: a post-rebuild tree with a fresh stamp is cached
        // even when action-cache keys were not rewritten (a --rebuild skips the store).
        CompileInputs.Module module = forecastInputs(s, prepared);
        Path groovyJar = null;
        boolean groovyJarUnavailable = false;
        try {
            groovyJar = CompileInputs.groovyJar(module);
        } catch (Exception e) {
            // Cannot reproduce the live stamp inputs without the jar: fall through to the
            // action-cache prediction rather than guessing.
            groovyJarUnavailable = true;
        }
        CompileRequest req = PlannerCompile.mainCompileRequest(CompileInputs.java(module, mainSrc, groovyJar));
        Optional<FreshnessStamp.Stamp> fresh = Optional.empty();
        // A stamp over generated sources the KSP round is about to restore answers for the old ones.
        // Read for a dependency-dirty module too: it is the very test the build applies once the
        // dependency is rebuilt, and unlike the action key it does not move with jk's own version.
        if (!s.force && !groovyJarUnavailable && !round.rewrites(mainSrc)) {
            try {
                fresh = CompileInputs.javaStamp(
                        s.actionCache, module, req, ActionKey.javacClasspathTokens(req, s.restored.abiToken()));
            } catch (IOException ignored) {
                fresh = Optional.empty();
            }
        }
        // The build's own condition: the stamp vouches for the inputs and the tree still holds every
        // output the stamped records own (CompileInputs.javaStamp); a partly wiped tree, or one whose
        // record is gone, falls through to the action cache, priced below by whether a record restores.
        boolean stampFresh =
                fresh.isPresent() && !s.compileDepDirty && StepWarnings.kept(layout, TaskNames.COMPILE_JAVA);
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
        JavaCompile.Prediction pred = JavaCompile.predict(
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
        // Nothing of this module's own moved, its dependencies' API reads as the stamp recorded and the
        // tree the stamp names is on disk: the build will find it fresh once they are rebuilt, as a
        // key hit says. A key that missed here missed on what the stamp leaves out — jk's own
        // version and the compiler worker's bytes, which every upgrade moves — so after an upgrade
        // every dependent of an edit forecast a recompile it would not run.
        boolean stampHolds = s.compileDepDirty && fresh.isPresent() && pred.outcome() != JavaCompile.Outcome.CACHE_HIT;
        s.compileStampHeld = stampHolds;
        if (stampHolds) {
            pred = new JavaCompile.Prediction(
                    JavaCompile.Outcome.CACHE_HIT, pred.actionKey(), mainSrc.size(), "", List.of());
        }
        // The record the tree is held against: the stamp's when it holds (none when it names
        // none), never the key that just missed.
        s.compileMainKey = stampHolds
                ? fresh.map(FreshnessStamp.Stamp::actionKey)
                        .filter(k -> !k.isBlank())
                        .orElse(null)
                : pred.actionKey();
        TaskForecast.Task step =
                ForecastSteps.compileStep(TaskNames.COMPILE_JAVA, pred, s.compileDepDirty, s.force, req, s.depHint);
        if (!step.cached()) s.compileDirty = true;
        // The key hit against the dependency's current output: nothing of this module's own moved,
        // and its package and tests are dirty only for the sibling's jar bytes.
        s.depOnlyDirty = s.compileDepDirty && pred.outcome() == JavaCompile.Outcome.CACHE_HIT && !s.force;
        s.ownHint = ForecastSteps.ownApiHint(s.force, s.compileDepDirty, s.depHint, s.dir, prepared, pred);
        return step;
    }

    /**
     * A compile over sources a running KSP round rewrites: what the round writes is unknown until
     * it runs, so the compile is forecast to run, and everything keyed on its output with it.
     */
    static TaskForecast.Task forecastKspRewrite(ForecastScope s, String step) {
        s.compileDirty = true;
        s.ownHint = SourceApiIndex.Hint.UNKNOWN;
        return new TaskForecast.Task(step, TaskForecast.Status.RUN, "recompile · KSP round runs", null);
    }
}
