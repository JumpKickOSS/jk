// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerCompile.incremental;
import static cc.jumpkick.runtime.PlannerCompile.mainGroovySourcesWithGenerated;
import static cc.jumpkick.runtime.PlannerCompile.mainKotlinSourcesWithGenerated;
import static cc.jumpkick.runtime.PlannerCompile.warnMovedSources;
import static cc.jumpkick.runtime.PlannerLang.compileGroovySources;
import static cc.jumpkick.runtime.PlannerLang.compileKotlinSources;
import static cc.jumpkick.runtime.base.ResourceMirror.mergeLanguageOutput;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.ResourceMirror;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.CompileStamp;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.task.SourceApiIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One plan's compile-kotlin and compile-groovy: each step, its body and its predictor side by
 * side. Built once per plan from what its {@link BuildPlanner.Ctx} holds; they read the module's
 * compile facts as {@link CompileSteps} gathers them.
 */
final class LangCompileSteps {

    private final BuildPlanner.Inputs in;
    private final Cas cas;
    private final ActionCache actionCache;
    private final Supplier<EffortWeights.Plan> plan;
    private final AtomicReference<@Nullable List<Path>> kotlinMainSrcRef;
    private final AtomicReference<@Nullable List<Path>> groovyMainSrcRef;
    private final ModuleShape shape;
    private final boolean compact;
    private final boolean mixedGroovy;
    private final boolean ksp;
    private final boolean kotlinModule;
    private final boolean mixedWithJava;
    private final @Nullable PluginDeclarations pluginDecls;

    LangCompileSteps(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        this.in = cx.in();
        this.cas = cx.cas();
        this.actionCache = cx.actionCache();
        this.plan = cx.plan();
        this.kotlinMainSrcRef = cx.kotlinMainSrcRef();
        this.groovyMainSrcRef = cx.groovyMainSrcRef();
        this.shape = cx.shape();
        this.compact = cx.compact();
        this.mixedGroovy = cx.mixedGroovy();
        this.ksp = cx.ksp();
        this.kotlinModule = cx.kotlinModule();
        this.mixedWithJava = cx.mixedWithJava();
        this.pluginDecls = pluginDecls;
    }

    Task compileKotlin() {
        return Task.builder(TaskNames.COMPILE_KOTLIN)
                .forecast(StepForecast.priced(this::forecastKotlin))
                .stage(BuildStage.COMPILE)
                .label("Kotlin")
                .kind(TaskKind.CPU)
                // Kotlin compiles first (reads Java declarations from source), so it
                // only needs the base steps — javac runs after it in a mixed module
                // plus any source-generating plugin steps.
                .requires(PlannerCompile.kotlinCompileRequires(pluginDecls, ksp))
                // Ticks count Kotlin sources (granularity); weight is the bar share
                // (see compile-java). kotlinc is opaque too, so ease it over time.
                .weight(() -> plan.get().compileKotlin())
                .interpolated()
                .ticks(() -> {
                    List<Path> srcs = kotlinMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            // Tick-count pre-walk only; parse-build re-derives the real
                            // list via mainKotlinSources and overwrites the ref.
                            srcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        kotlinMainSrcRef.compareAndSet(null, srcs);
                        List<Path> published = kotlinMainSrcRef.get();
                        if (published != null) srcs = published;
                    }
                    return srcs.size();
                })
                .execute(this::runCompileKotlin)
                .build();
    }

    /** The compile-kotlin body: the source union, the freshness stamp, kotlinc in the worker. */
    private void runCompileKotlin(TaskContext ctx) throws Exception {
        Path classes = mainClasses(ctx);
        Files.createDirectories(classes); // compile-java may be skipped
        // Plugin-contributed generated Kotlin (a KSP round, a codegen step) joins the source list
        // exactly like the Java side: the freshness stamp and the plugin see generated files as
        // ordinary sources.
        List<Path> ktSources = mainKotlinSourcesWithGenerated(
                kotlinSources(ctx), ctx.require(MODULE).layout(), pluginDecls, ksp);
        if (ktSources.isEmpty()) {
            ctx.label("no Kotlin sources");
            StepWarnings.clear(ctx, TaskNames.COMPILE_KOTLIN);
            ctx.put(KOTLIN_COMPILE, CompileStamp.noSources());
            return;
        }
        // The stamp covers the Kotlin sources plus, in a module javac runs in, the Java sources
        // kotlinc reads declarations from: a Java edit can make our classes stale. It is the cheap
        // check in front of the content-hash action cache, so it errs conservative. The kotlinc
        // config is resolved ahead of it, without fetching: its digest is a stamp input, so a
        // kotlinc arg, a [[kotlin-plugins]] entry or a JDK switch is stale here with no source moved.
        CompileInputs.Module module = CompileSteps.compileInputs(ctx, in, cas, shape, pluginDecls);
        CompileInputs.Kotlin inputs =
                CompileInputs.kotlin(module, ktSources, CompileSteps.mainJavaSources(ctx, pluginDecls, ksp));
        List<Path> freshInputs = inputs.stampInputs();
        // A shrunken Kotlin source set (a variant switch dropping an extra-src root) must not leave
        // the dropped classes in the merged output: kotlinc's IC prunes its own dir, but the
        // assemble merge into classes/ is additive. Start the merged tree clean; both stamps die
        // with it, so javac re-runs too (rare: only on source removals).
        if (FreshnessStamp.hasRemovedSources(classes, BuildStamps.KOTLIN, freshInputs)) {
            PathUtil.deleteRecursively(classes);
            Files.createDirectories(classes);
            ResourceMirror.relay(classes, incremental(ctx));
        }
        boolean rerun = in.session().config().rebuildOr(false);
        // Kotlin compiles into its own dir and merges into the shared classes dir: the incremental
        // compiler owns its output dir and prunes files it did not produce.
        PlannerLang.KotlinWorker worker = CompileInputs.kotlinWorker(module, inputs);
        // The stamp spells the classpath by ABI token, like the action key: a sibling rewritten
        // with the same ABI is fresh here once its token is known, and one whose ABI moved is stale
        // before any source mtime is read. A token not yet memoized is what makes the worker
        // resolve (and fork) ahead of the compile.
        String optionsDigest = inputs.config().digest();
        List<String> stampTokens = PlannerLang.kotlinStampTokens(inputs.classpath(), worker.snapshotter());
        // A fresh stamp vouches for the inputs, not for the tree: the merged classes must still
        // hold every output the stamped compile's record owns.
        Optional<List<CompileResult.Diagnostic>> ktKept =
                StepWarnings.read(ctx, TaskNames.COMPILE_KOTLIN, ktSources, rerun);
        if (ktKept.isPresent() && CompileInputs.kotlinStampFresh(actionCache, module, inputs, stampTokens)) {
            ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
            JavacDiagnostics.reportWarnings(ctx, JavacDiagnostics.KOTLINC, List.of(), ktKept.get());
            ctx.label("up to date");
            ctx.cached();
            ctx.put(KOTLIN_COMPILE, CompileStamp.upToDate(ktSources));
            ctx.progress(ktSources.size());
            return;
        }
        ctx.label("compiling " + ktSources.size() + " Kotlin sources");
        long clock = FreshnessStamp.clockNow(classes);
        LangCompile.Result kr = compileKotlinSources(ctx, in, actionCache, CompileInputs.kotlinTaskId(module), worker);
        if (!kr.success()) {
            StepDiagnostics.forwardWorkerDiagnostics(
                    ctx,
                    "kotlinc",
                    PackageProviders.enrich(ctx, inputs.classpath(), ClasspathResolver.COMPILE_MAIN, kr.diagnostics()),
                    "kotlinc failed without diagnostics");
            throw new RuntimeException("kotlinc reported errors");
        }
        StepWarnings.sayAndKeep(ctx, TaskNames.COMPILE_KOTLIN, JavacDiagnostics.KOTLINC, kr);
        if (kr.cacheHit()) {
            ctx.label("cache hit " + kr.actionKey().substring(0, 8));
            ctx.cached();
        }
        warnMovedSources(ctx, kr.movedSources());
        // Kotlin-only: publish straight into the classes dir. Mixed: leave it in the Kotlin dir
        // for assemble-classes to merge after javac.
        if (!mixedWithJava) {
            mergeLanguageOutput(
                    module.layout().kotlinClassesDir(), classes, module.layout().buildDir(), "kotlin");
        }
        ctx.put(
                KOTLIN_COMPILE,
                new CompileStamp(
                        "compiled", freshInputs, optionsDigest, stampTokens, clock, kr.actionKey(), ktSources));
        ctx.progress(ktSources.size());
    }

    Task compileGroovy() {
        return Task.builder(TaskNames.COMPILE_GROOVY)
                .forecast(StepForecast.priced(this::forecastGroovy))
                .stage(BuildStage.COMPILE)
                .label("Groovy")
                .kind(TaskKind.CPU)
                // Groovy compiles first (joint mode reads Java *declarations* by sweeping the
                // .java roots; javac runs after it in a mixed module), so it only needs the
                // base steps plus any source-generating plugin steps.
                .requires(PlannerCompile.groovyCompileRequires(pluginDecls, kotlinModule))
                .weight(() -> plan.get().compileGroovy())
                .interpolated()
                .ticks(() -> {
                    List<Path> srcs = groovyMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            // Tick-count pre-walk only; parse-build re-derives the real
                            // list via mainGroovySources and overwrites the ref.
                            srcs = CompileSupport.collectGroovySources(in.dir(), compact);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        groovyMainSrcRef.compareAndSet(null, srcs);
                        List<Path> published = groovyMainSrcRef.get();
                        if (published != null) srcs = published;
                    }
                    return srcs.size();
                })
                .execute(this::runCompileGroovy)
                .build();
    }

    /** The compile-groovy body: the source union, the freshness stamp, groovyc in the worker. */
    private void runCompileGroovy(TaskContext ctx) throws Exception {
        Path classes = mainClasses(ctx);
        Files.createDirectories(classes); // compile-java may be skipped
        // Plugin-contributed generated Groovy joins the source list exactly like the Kotlin side.
        List<Path> gvSources = mainGroovySourcesWithGenerated(
                groovySources(ctx), ctx.require(MODULE).layout(), pluginDecls);
        if (gvSources.isEmpty()) {
            ctx.label("no Groovy sources");
            StepWarnings.clear(ctx, TaskNames.COMPILE_GROOVY);
            ctx.put(GROOVY_COMPILE, CompileStamp.noSources());
            return;
        }
        // The stamp covers the Groovy sources plus, in joint mode, the Java sources it resolves
        // against: the same conservative posture as compile-kotlin, the action cache behind it
        // decides precisely.
        CompileInputs.Module module = CompileSteps.compileInputs(ctx, in, cas, shape, pluginDecls);
        CompileInputs.Groovy inputs = CompileInputs.groovy(
                module,
                gvSources,
                CompileSteps.mainJavaSources(ctx, pluginDecls, ksp),
                mainKotlinSourcesWithGenerated(kotlinSources(ctx), module.layout(), pluginDecls, ksp));
        List<Path> freshInputs = inputs.stampInputs();
        // A shrunken Groovy source set must not leave dropped classes in the merged output (the
        // assemble merge into classes/ is additive).
        if (FreshnessStamp.hasRemovedSources(classes, BuildStamps.GROOVY, freshInputs)) {
            PathUtil.deleteRecursively(classes);
            Files.createDirectories(classes);
            ResourceMirror.relay(classes, incremental(ctx));
            // Kotlin published here earlier in this run; its own dir still holds it.
            if (kotlinModule && !mixedWithJava) {
                mergeLanguageOutput(
                        module.layout().kotlinClassesDir(),
                        classes,
                        module.layout().buildDir(),
                        "kotlin");
            }
        }
        boolean rerun = in.session().config().rebuildOr(false);
        // Groovy compiles into its own dir and merges into the shared classes dir: the worker's
        // action cache snapshots its whole output dir, so it never shares one with javac. In joint
        // mode it sweeps the Java roots for resolution only and retains stubs for javac.
        Path gvOut = module.layout().groovyClassesDir();
        Files.createDirectories(gvOut);
        if (inputs.stubsOut() != null) Files.createDirectories(inputs.stubsOut());
        GroovycRequest request = CompileInputs.groovyRequest(module, inputs);
        // The groovyc args and toolchain are stamp inputs too, and the classpath enters as the
        // token lines the action key hashes: a sibling's body-only rewrite reads fresh.
        String optionsDigest = CompileInputs.groovyDigest(module);
        List<String> stampTokens = ActionKey.groovycClasspathTokens(request);
        // As compile-kotlin: the stamp vouches for the inputs, the record for the tree.
        Optional<List<CompileResult.Diagnostic>> gvKept =
                StepWarnings.read(ctx, TaskNames.COMPILE_GROOVY, gvSources, rerun);
        if (gvKept.isPresent() && CompileInputs.groovyStampFresh(actionCache, module, inputs, stampTokens)) {
            ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
            JavacDiagnostics.reportWarnings(ctx, JavacDiagnostics.GROOVYC, List.of(), gvKept.get());
            ctx.label("up to date");
            ctx.cached();
            ctx.put(GROOVY_COMPILE, CompileStamp.upToDate(gvSources));
            ctx.progress(gvSources.size());
            return;
        }
        ctx.label("compiling " + gvSources.size() + " Groovy sources");
        long clock = FreshnessStamp.clockNow(classes);
        LangCompile.Result gr = compileGroovySources(ctx, in, actionCache, request, CompileInputs.groovyTaskId(module));
        if (!gr.success()) {
            StepDiagnostics.forwardWorkerDiagnostics(
                    ctx, "groovyc", gr.diagnostics(), "groovyc failed without diagnostics");
            throw new RuntimeException("groovyc reported errors");
        }
        StepWarnings.sayAndKeep(ctx, TaskNames.COMPILE_GROOVY, JavacDiagnostics.GROOVYC, gr);
        if (gr.cacheHit()) {
            ctx.label("cache hit " + gr.actionKey().substring(0, 8));
            ctx.cached();
        }
        warnMovedSources(ctx, gr.movedSources());
        // Groovy-only: publish straight into the classes dir. Mixed: leave it in the Groovy dir
        // for assemble-classes to merge after javac.
        if (!mixedGroovy) mergeLanguageOutput(gvOut, classes, module.layout().buildDir(), "groovy");
        ctx.put(
                GROOVY_COMPILE,
                new CompileStamp(
                        "compiled", freshInputs, optionsDigest, stampTokens, clock, gr.actionKey(), gvSources));
        ctx.progress(gvSources.size());
    }

    // ---- predictors -----------------------------------------------------------------------

    /**
     * compile-kotlin's price: the stamp the live step writes to the merged classes, else the
     * action key of the build's own kotlinc request — never the tasks/ pointer, whose last record
     * may belong to another edit of the sources.
     */
    private TaskForecast.@Nullable Task forecastKotlin(StepForecast.Scope scope) throws Exception {
        ForecastScope s = ForecastScope.of(scope);
        ForecastScope.Prepared prepared = s.prepared();
        if (prepared.parsed().ktSrc().isEmpty()) return null;
        BuildLayout layout = prepared.parsed().layout();
        KspForecast.Round round = prepared.mainRound();
        boolean rewritten = round.rewrites(prepared.parsed().ktSrc())
                || round.rewrites(prepared.parsed().mainSrc());
        if (round.runs() && rewritten) return CompileSteps.forecastKspRewrite(s, TaskNames.COMPILE_KOTLIN);
        ForecastLangArms arms = s.arms();
        CompileInputs.Module module = CompileSteps.forecastInputs(s, prepared);
        CompileInputs.Kotlin inputs = CompileInputs.kotlin(
                module, prepared.parsed().ktSrc(), prepared.parsed().mainSrc());
        if (!s.compileDepDirty && !s.force && !rewritten && arms.kotlinStampFresh(module, inputs)) {
            s.compileKotlinKey = FreshnessStamp.stampedKey(layout.compileStampDir(), BuildStamps.KOTLIN)
                    .orElse(null);
            return new TaskForecast.Task(TaskNames.COMPILE_KOTLIN, TaskForecast.Status.CACHED, "", null);
        }
        ForecastLangArms.LangStep step = arms.kotlinStep(module, inputs);
        if (step.task().cached()) {
            s.restored.projectFromRecord(layout.kotlinClassesDir(), s.compileKotlinKey = step.key());
        } else {
            s.compileDirty = true;
            s.ownHint = SourceApiIndex.Hint.UNKNOWN;
        }
        return step.task();
    }

    /** compile-groovy's price: the stamp, then the action key of the build's own groovyc request. */
    private TaskForecast.@Nullable Task forecastGroovy(StepForecast.Scope scope) throws Exception {
        ForecastScope s = ForecastScope.of(scope);
        ForecastScope.Prepared prepared = s.prepared();
        if (prepared.parsed().gvSrc().isEmpty()) return null;
        BuildLayout layout = prepared.parsed().layout();
        CompileInputs.Module module = CompileSteps.forecastInputs(s, prepared);
        ForecastLangArms.LangStep step = s.arms()
                .groovyStep(
                        module,
                        CompileInputs.groovy(
                                module,
                                prepared.parsed().gvSrc(),
                                prepared.parsed().mainSrc(),
                                prepared.parsed().ktSrc()));
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
