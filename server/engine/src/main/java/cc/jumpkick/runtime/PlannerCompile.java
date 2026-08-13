// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Main-language compile steps (Java / Kotlin / Groovy) and their require edges.
 */
public final class PlannerCompile {

    private PlannerCompile() {}

    static Task compileJavaStep(BuildPlanner.Ctx cx, PluginBuild.Declarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COMPILE_JAVA)
                .stage(BuildStage.COMPILE)
                .label("Compiling")
                .kind(TaskKind.CPU)
                .requires(javaCompileRequires(mixed, cx.mixedGroovy(), pluginDecls, cx.ksp()))
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
                        srcs = javaMainSrcRef.get();
                    }
                    return srcs.size();
                })
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    // javac always writes to the canonical classes dir (java/main/).
                    // The Kotlin incremental compiler gets its own dir (kotlin/main/)
                    // so it cannot prune Java's output; the assembler merges both.
                    Path javaOut = classes;
                    List<Path> sources = javaSources(ctx);
                    List<Path> generated = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".java");
                    List<Path> kspGenerated = kspGeneratedSources(ctx.require(LAYOUT), ".java");
                    List<Path> logicGenerated = BuildLogicSupport.generatedSources(ctx.require(LAYOUT), ".java");
                    if (!generated.isEmpty() || !kspGenerated.isEmpty() || !logicGenerated.isEmpty()) {
                        sources = new ArrayList<>(sources);
                        sources.addAll(generated);
                        sources.addAll(kspGenerated);
                        sources.addAll(logicGenerated);
                        // Re-publish the union so write-stamp records the same input set
                        // this compile checked (else the fast freshness path never holds).
                        ctx.put(JAVA_SOURCES, sources);
                    }
                    if (sources.isEmpty()) {
                        ctx.label("no Java sources");
                        Files.createDirectories(javaOut);
                        ctx.put(BUILD_OUTCOME, "no-sources");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> baseClasspath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> classpath = baseClasspath;
                    Path groovyJar = null;
                    if (mixed) {
                        // See Kotlin's output so Java can reference Kotlin types.
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).kotlinClassesDir());
                    }
                    if (cx.mixedGroovy()) {
                        // See Groovy's output so Java can reference Groovy types — plus the
                        // version-matched groovy jar: every Groovy class implements
                        // groovy.lang.GroovyObject, which javac must resolve.
                        groovyJar = groovyCompileJar(ctx, cas);
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).groovyClassesDir());
                        classpath.add(groovyJar);
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp =
                            (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                    boolean rerun = in.session().config().rebuildOr(false);
                    // The shared stamp recipeforecast and write-stamp use it too.
                    List<Path> stampInputs = mainStampClasspath(
                            baseClasspath, processorCp, mixed, cx.mixedGroovy(), ctx.require(LAYOUT), groovyJar);
                    if (!rerun
                            && cc.jumpkick.task.FreshnessStamp.isFresh(
                                    javaOut,
                                    cc.jumpkick.task.FreshnessStamp.JAVA_STAMP,
                                    sources,
                                    stampInputs,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(BUILD_OUTCOME, "up-to-date");
                        ctx.progress(sources.size());
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    if (cx.mixedGroovy()) {
                        // The joint Groovy compile retained Java-visible stubs — put them on
                        // javac's sourcepath so Java→Groovy references resolve even before the
                        // real Groovy classes are visible; the assemble merge overwrites any
                        // stub-compiled.class with the real Groovy output afterwards.
                        Path stubs = ctx.require(LAYOUT).groovyStubsDir();
                        if (Files.isDirectory(stubs)) {
                            javacArgs = new ArrayList<>(javacArgs);
                            javacArgs.add("--source-path");
                            javacArgs.add(stubs.toAbsolutePath().toString());
                        }
                    }
                    CompileRequest request = CompileRequest.builder()
                            .sources(sources)
                            .classpath(classpath)
                            .outputDir(javaOut)
                            .release(ctx.require(RELEASE))
                            .extraOptions(javacArgs)
                            .javaHome(ctx.require(JAVA_HOME))
                            .processorPath(processorCp)
                            .build();
                    String taskId = ActionKey.qualifiedTaskId("compile-main", javaOut);
                    Path javaStateDir = in.cache()
                            .resolve("actions")
                            .resolve("incremental-java")
                            .resolve(taskId);
                    // Reweight the bar slice now that the real request is known: a CAS
                    // action-cache hit means a cheap hard-link restore (3), not a full
                    // javac (ceil(sources × 0.1)). Uses the exact key
                    // JavaIncrementalCompile will look up, so the estimate matches what
                    // actually happens — no plan-start reconstruction divergence.
                    if (!rerun) {
                        try {
                            boolean restores = actionCache
                                    .lookup(ActionKey.forJavac(
                                            taskId, request, cc.jumpkick.model.BuildIdentity.cacheKeyVersion()))
                                    .isPresent();
                            ctx.reweight(
                                    restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
                        } catch (Exception ignored) {
                            /* keep the up-front estimate */
                        }
                    }
                    // With processors declared, hand the incremental compiler an AP setup:
                    // a *lazy* plugin-jar resolver + a stable generated-sources dir. The
                    // engine routes through the plugin only once it has detected
                    // source-generating processors, so bytecode-only processors (e.g.
                    // Lombok) and first builds never resolve it — which matters because
                    // a jk build that didn't bundle the plugin (or its sha resource)
                    // would otherwise fail here even though the plugin isn't needed.
                    // When it *is* needed but unavailable, warn once and fall back to
                    // plain javac (correct, just without incremental AP provenance).
                    cc.jumpkick.task.JavaIncrementalCompile.ApSetup ap = null;
                    if (!processorCp.isEmpty()) {
                        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations");
                        Files.createDirectories(genDir);
                        ap = new cc.jumpkick.task.JavaIncrementalCompile.ApSetup(
                                () -> {
                                    try {
                                        return PluginJar.JAVA_COMPILER.locate(cas);
                                    } catch (RuntimeException e) {
                                        ctx.warn(
                                                "javac",
                                                "java-compiler worker unavailable ("
                                                        + e.getMessage()
                                                        + "); compiling with plain javac"
                                                        + " (no incremental annotation-processing provenance)");
                                        return null;
                                    }
                                },
                                genDir);
                    }
                    ctx.label("compiling " + sources.size() + " sources");
                    cc.jumpkick.task.JavaIncrementalCompile.Result r = cc.jumpkick.task.JavaIncrementalCompile.run(
                            taskId,
                            request,
                            cc.jumpkick.model.BuildIdentity.cacheKeyVersion(),
                            !rerun,
                            !in.ephemeralActions(), // verify-scratch: no persistent residue
                            actionCache.cas(),
                            actionCache,
                            javaStateDir,
                            ap);
                    ctx.put(ACTION_KEY, r.actionKey());
                    // Forward every javac diagnostic to the terminal, by severity:
                    // errors fail the build, warnings/notes (e.g. deprecation) are
                    // surfaced but don't. Strip the leading severity word — the
                    // console renderer adds its own ✗/⚠ marker.
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
                        // Never fail silently: if no ERROR diagnostic surfaced (crash,
                        // swallowed output), say so explicitly.
                        if (!errored) {
                            ctx.error(
                                    "javac",
                                    "compile failed without compiler diagnostics (outcome: " + r.outcome() + ")");
                        }
                        throw new RuntimeException("javac reported errors");
                    }
                    if (r.cacheHit()) {
                        ctx.label("cache hit " + r.actionKey().substring(0, 8));
                        ctx.cached();
                    }
                    ctx.put(BUILD_OUTCOME, r.outcome());
                    ctx.progress(sources.size());
                })
                .build();
    }

    static String[] kotlinCompileRequires(PluginBuild.Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        if (ksp) requires.add("ksp");
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    static String[] javaCompileRequires(
            boolean mixed, boolean mixedGroovy, PluginBuild.Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        if (mixed) requires.add(TaskNames.COMPILE_KOTLIN);
        if (mixedGroovy) requires.add(TaskNames.COMPILE_GROOVY);
        if (ksp) requires.add("ksp");
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    static String[] groovyCompileRequires(PluginBuild.Declarations decls) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    /** True when the module declares {@code [processor-dependencies]} entries. */
    static boolean hasProcessorDeps(JkBuild build) {
        List<cc.jumpkick.model.Dependency> procs =
                build.dependencies().byScope().get(Scope.PROCESSOR);
        return procs != null && !procs.isEmpty();
    }

    static Task compileKotlinStep(BuildPlanner.Ctx cx, PluginBuild.Declarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Task.builder(TaskNames.COMPILE_KOTLIN)
                .stage(BuildStage.COMPILE)
                .label("Kotlin")
                .kind(TaskKind.CPU)
                // Kotlin compiles first (reads Java declarations from source), so it
                // only needs the base steps — javac runs after it in a mixed module
                // plus any source-generating plugin steps.
                .requires(kotlinCompileRequires(pluginDecls, cx.ksp()))
                // Ticks count Kotlin sources (granularity); weight is the bar share
                // (see compile-java). kotlinc is opaque too, so ease it over time.
                .weight(() -> plan.get().compileKotlin())
                .interpolated()
                .ticks(() -> {
                    List<Path> srcs = kotlinMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        kotlinMainSrcRef.compareAndSet(null, srcs);
                        srcs = kotlinMainSrcRef.get();
                    }
                    return srcs.size();
                })
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes); // compile-java may be skipped
                    List<Path> ktSources = kotlinSources(ctx);
                    // Plugin-contributed generated Kotlin (a KSP round, a codegen step) joins the
                    // source list exactly like the Java side — the freshness stamp and the plugin
                    // see generated files as ordinary sources.
                    List<Path> generatedKt = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".kt");
                    List<Path> kspKt = kspGeneratedSources(ctx.require(LAYOUT), ".kt");
                    List<Path> logicKt = BuildLogicSupport.generatedSources(ctx.require(LAYOUT), ".kt");
                    if (!generatedKt.isEmpty() || !kspKt.isEmpty() || !logicKt.isEmpty()) {
                        ktSources = new ArrayList<>(ktSources);
                        ktSources.addAll(generatedKt);
                        ktSources.addAll(kspKt);
                        ktSources.addAll(logicKt);
                        // Re-publish so write-stamp-kotlin records what this compile checked.
                        ctx.put(KOTLIN_SOURCES, ktSources);
                    }
                    if (ktSources.isEmpty()) {
                        ctx.label("no Kotlin sources");
                        ctx.put(KOTLIN_OUTCOME, "no-sources");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    // Freshness inputs: Kotlin sources plus — in a mixed module
                    // the Java sources, since kotlinc compiles against the Java
                    // output (kotlincCp includes `classes`) and a Java edit can
                    // make our.class files stale. Unlike compile-java there is
                    // no content-hash action cache behind this stamp, so the
                    // check must err conservative: any Java change forces a
                    // Kotlin recompile. The output dir itself is deliberately not
                    // an input (directory mtimes don't track in-place.class
                    // rewrites, and copy-resources churns it).
                    List<Path> freshInputs = new ArrayList<>(ktSources);
                    if (mixedWithJava) freshInputs.addAll(javaSources(ctx));
                    // A shrunken Kotlin source set (a variant switch dropping an extra-src root)
                    // must not leave the dropped classes in the merged output: kotlinc's IC
                    // prunes its own dir, but the assemble merge into classes/ is additive.
                    // Start the merged tree clean — both stamps die with it, so javac re-runs
                    // too (rare: only on source removals).
                    if (cc.jumpkick.task.FreshnessStamp.hasRemovedSources(
                            classes, cc.jumpkick.task.FreshnessStamp.KOTLIN_STAMP, freshInputs)) {
                        cc.jumpkick.util.PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun
                            && cc.jumpkick.task.FreshnessStamp.isFresh(
                                    classes,
                                    cc.jumpkick.task.FreshnessStamp.KOTLIN_STAMP,
                                    freshInputs,
                                    classpath,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(KOTLIN_OUTCOME, "up-to-date");
                        ctx.progress(ktSources.size());
                        return;
                    }
                    ctx.label("compiling " + ktSources.size() + " Kotlin sources");
                    // Kotlin compiles into its own dir, then we merge into the
                    // shared classes dir. The incremental compiler owns its output
                    // dir and prunes files it didn't produce — so it can't share a
                    // dir with javac's output (it would delete the.class files).
                    Path ktOut = ctx.require(LAYOUT).kotlinClassesDir();
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, classes);
                    Path workingDir = in.cache()
                            .resolve("actions")
                            .resolve("incremental-kotlin")
                            .resolve(taskId);
                    // Mixed module: Kotlin reads the Java declarations from source
                    // (analysis only — it emits no Java bytecode; javac does next).
                    cc.jumpkick.task.KotlinCompile.Result kr = compileKotlinSources(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            ktSources,
                            classpath,
                            ktOut,
                            taskId,
                            workingDir,
                            kotlinJavaSourceRoots(mixedWithJava, compact, in.dir(), ctx.require(LAYOUT), pluginDecls));
                    if (!kr.success()) {
                        ctx.error("kotlinc", kr.output());
                        throw new RuntimeException("kotlinc reported errors");
                    }
                    if (kr.cacheHit()) {
                        ctx.label("cache hit " + kr.actionKey().substring(0, 8));
                        ctx.cached();
                    }
                    // Kotlin-only: publish straight into the classes dir. Mixed:
                    // leave it in ktOut for `assemble-classes` to merge after javac.
                    if (!mixedWithJava) copyResources(ktOut, classes);
                    ctx.put(KOTLIN_OUTCOME, "compiled");
                    ctx.progress(ktSources.size());
                })
                .build();
    }

    static Task compileGroovyStep(BuildPlanner.Ctx cx, PluginBuild.Declarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<List<Path>> groovyMainSrcRef = cx.groovyMainSrcRef();
        boolean compact = cx.compact();
        boolean mixedGroovy = cx.mixedGroovy();
        return Task.builder(TaskNames.COMPILE_GROOVY)
                .stage(BuildStage.COMPILE)
                .label("Groovy")
                .kind(TaskKind.CPU)
                // Groovy compiles first (joint mode reads Java *declarations* by sweeping the
                // .java roots; javac runs after it in a mixed module), so it only needs the
                // base steps plus any source-generating plugin steps.
                .requires(groovyCompileRequires(pluginDecls))
                .weight(() -> plan.get().compileGroovy())
                .interpolated()
                .ticks(() -> {
                    List<Path> srcs = groovyMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectGroovySources(in.dir(), compact);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        groovyMainSrcRef.compareAndSet(null, srcs);
                        srcs = groovyMainSrcRef.get();
                    }
                    return srcs.size();
                })
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes); // compile-java may be skipped
                    List<Path> gvSources = groovySources(ctx);
                    // Plugin-contributed generated Groovy joins the source list exactly like the
                    // Kotlin side — the freshness stamp and the worker see generated files as
                    // ordinary sources.
                    List<Path> generatedGv = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".groovy");
                    List<Path> logicGv = BuildLogicSupport.generatedSources(ctx.require(LAYOUT), ".groovy");
                    if (!generatedGv.isEmpty() || !logicGv.isEmpty()) {
                        gvSources = new ArrayList<>(gvSources);
                        gvSources.addAll(generatedGv);
                        gvSources.addAll(logicGv);
                        // Re-publish so write-stamp-groovy records what this compile checked.
                        ctx.put(GROOVY_SOURCES, gvSources);
                    }
                    if (gvSources.isEmpty()) {
                        ctx.label("no Groovy sources");
                        ctx.put(GROOVY_OUTCOME, "no-sources");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    // Freshness inputs: Groovy sources plus — in a mixed module — the Java
                    // sources, since joint mode resolves against them (any Java edit can make
                    // our.class files or retained stubs stale). Same conservative posture as
                    // compile-kotlin; the action cache behind decides precisely.
                    List<Path> freshInputs = new ArrayList<>(gvSources);
                    if (mixedGroovy) freshInputs.addAll(javaSources(ctx));
                    // A shrunken Groovy source set must not leave dropped classes in the merged
                    // output (the assemble merge into classes/ is additive).
                    if (cc.jumpkick.task.FreshnessStamp.hasRemovedSources(
                            classes, cc.jumpkick.task.FreshnessStamp.GROOVY_STAMP, freshInputs)) {
                        cc.jumpkick.util.PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun
                            && cc.jumpkick.task.FreshnessStamp.isFresh(
                                    classes,
                                    cc.jumpkick.task.FreshnessStamp.GROOVY_STAMP,
                                    freshInputs,
                                    classpath,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(GROOVY_OUTCOME, "up-to-date");
                        ctx.progress(gvSources.size());
                        return;
                    }
                    ctx.label("compiling " + gvSources.size() + " Groovy sources");
                    // Groovy compiles into its own dir, then we merge into the shared classes
                    // dir (the worker's action cache snapshots its whole output dir — it must
                    // never share one with javac).
                    Path gvOut = ctx.require(LAYOUT).groovyClassesDir();
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, classes);
                    // Mixed module: joint mode sweeps the Java roots for resolution only
                    // stubs are retained for javac's sourcepath; jk's javac worker stays
                    // authoritative for the real Java outputs.
                    cc.jumpkick.task.GroovyCompile.Result gr = compileGroovySources(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            gvSources,
                            classpath,
                            gvOut,
                            taskId,
                            mixedGroovy
                                    ? kotlinJavaSourceRoots(true, compact, in.dir(), ctx.require(LAYOUT), pluginDecls)
                                    : null,
                            mixedGroovy ? ctx.require(LAYOUT).groovyStubsDir() : null);
                    if (!gr.success()) {
                        ctx.error("groovyc", gr.output());
                        throw new RuntimeException("groovyc reported errors");
                    }
                    if (gr.cacheHit()) {
                        ctx.label("cache hit " + gr.actionKey().substring(0, 8));
                        ctx.cached();
                    }
                    // Groovy-only: publish straight into the classes dir. Mixed:
                    // leave it in gvOut for `assemble-classes` to merge after javac.
                    if (!mixedGroovy) copyResources(gvOut, classes);
                    ctx.put(GROOVY_OUTCOME, "compiled");
                    ctx.progress(gvSources.size());
                })
                .build();
    }
}
