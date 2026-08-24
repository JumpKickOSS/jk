// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.GroovyCompile;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.KotlinCompile;
import java.io.IOException;
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

    // ---- compile-main's one derivation, shared with the forecast -------------------------
    //
    // `jk explain` prices compile-main by recomputing ActionKey.forJavac over a CompileRequest it
    // builds itself. Every field of that request the build sets and the forecast does not is a key
    // the two sides can never agree on — a phantom rebuild reported forever, or (running the other
    // way) a stale artifact blessed. Six such drifts were live at once, and three of them were in
    // this one request: scalaVersion, compilerClasspath and the Groovy stubs --source-path
    // (JK-2479), joined by javaHome the moment forJavac started hashing it (JK-2460).
    //
    // A text guard can compare which FIELDS each side sets (checkForecastKeyParity arm B) but not
    // which VALUES it puts in them, so the field list is only half the problem. These four methods
    // are the other half: both sides call them, so the request is derived once and the question of
    // whether the copies agree stops existing.

    /**
     * The {@code [build] extra-src} overlay roots plus plugin-contributed source roots
     * ({@code [[contribute.source-roots]]} — grails-app/…). Variant overlays are folded in by
     * {@code VariantApply} before this runs.
     */
    public static List<Path> extraSourceDirs(JkBuild project, Path moduleDir) {
        List<Path> dirs = new ArrayList<>(CompileSupport.extraSrcDirs(project, moduleDir));
        for (var root : PluginContributions.sourceRoots(project, moduleDir)) {
            if (!root.resource()) dirs.add(moduleDir.resolve(root.dir()));
        }
        return dirs;
    }

    /**
     * What {@code BuildPlanner.JAVA_SOURCES} holds: the module's {@code .java} plus the extra-src
     * overlay, plus <em>every</em> {@code .scala} (a mixed Java+Scala module compiles through one
     * Zinc session, so the Scala sources are javac's inputs too — JK-2320). {@code javaSeed} is the
     * caller's already-walked {@code .java} list, so the common path does not walk twice.
     */
    public static List<Path> javaAndScalaSources(JkBuild project, Path moduleDir, boolean compact, List<Path> javaSeed)
            throws IOException {
        List<Path> extraSrcDirs = extraSourceDirs(project, moduleDir);
        List<Path> java = javaSeed;
        List<Path> scala = CompileSupport.collectScalaSources(moduleDir, compact);
        if (!extraSrcDirs.isEmpty()) {
            java = CompileSupport.withExtraSources(java, extraSrcDirs, ".java");
            scala = CompileSupport.withExtraSources(scala, extraSrcDirs, ".scala");
        }
        if (scala.isEmpty()) return java;
        List<Path> withScala = new ArrayList<>(java);
        withScala.addAll(scala);
        return withScala;
    }

    /**
     * {@link #javaAndScalaSources} plus the generated roots compile-main folds in at execute time:
     * plugin {@code contributesSources}, KSP output, and build-logic output. The build re-publishes
     * this union as {@code JAVA_SOURCES} so write-stamp records the set the compile checked.
     */
    public static List<Path> mainJavaSources(
            List<Path> javaAndScala, BuildLayout layout, PluginBuild.Declarations decls) throws IOException {
        List<Path> generated = pluginContributedSources(layout, decls, ".java");
        List<Path> kspGenerated = kspGeneratedSources(layout, ".java");
        List<Path> logicGenerated = BuildLogicSupport.generatedSources(layout, ".java");
        if (generated.isEmpty() && kspGenerated.isEmpty() && logicGenerated.isEmpty()) return javaAndScala;
        List<Path> all = new ArrayList<>(javaAndScala);
        all.addAll(generated);
        all.addAll(kspGenerated);
        all.addAll(logicGenerated);
        return all;
    }

    /**
     * Every fact compile-main's {@link CompileRequest} is derived from. {@code classpath} is the
     * <em>base</em> compile classpath (lock + workspace siblings); {@link #mainCompileRequest}
     * adds the sibling-language outputs, because which of those belong on it is part of the
     * derivation and not a caller's decision.
     */
    public record MainCompile(
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            BuildLayout layout,
            Path outputDir,
            int release,
            List<String> javacArgs,
            Path javaHome,
            boolean mixedKotlin,
            boolean mixedGroovy,
            Path groovyJar,
            ScalaCompile.Setup scala) {}

    /** compile-main's request — the build's javac invocation and the forecast's key, from one body. */
    public static CompileRequest mainCompileRequest(MainCompile in) {
        List<Path> classpath = new ArrayList<>(in.classpath());
        // See Kotlin's output so Java can reference Kotlin types.
        if (in.mixedKotlin()) classpath.add(in.layout().kotlinClassesDir());
        if (in.mixedGroovy()) {
            // Groovy's output, plus the version-matched groovy jar: every Groovy class implements
            // groovy.lang.GroovyObject, which javac must resolve.
            classpath.add(in.layout().groovyClassesDir());
            if (in.groovyJar() != null) classpath.add(in.groovyJar());
        }
        if (in.scala() != null) {
            for (Path lib : in.scala().libraryJars()) {
                if (!classpath.contains(lib)) classpath.add(lib);
            }
        }
        List<String> options = in.javacArgs();
        if (in.mixedGroovy()) {
            // The joint Groovy compile retained Java-visible stubs — put them on javac's
            // sourcepath so Java→Groovy references resolve even before the real Groovy classes are
            // visible; the assemble merge overwrites any stub-compiled .class with the real Groovy
            // output afterwards.
            Path stubs = in.layout().groovyStubsDir();
            if (Files.isDirectory(stubs)) {
                options = new ArrayList<>(options);
                options.add("--source-path");
                options.add(stubs.toAbsolutePath().toString());
            }
        }
        CompileRequest.CompileRequestBuilder req = CompileRequest.builder()
                .sources(in.sources())
                .classpath(classpath)
                .outputDir(in.outputDir())
                .release(in.release())
                .extraOptions(options)
                .javaHome(in.javaHome())
                .processorPath(in.processorPath());
        if (in.scala() != null) {
            req.scalaVersion(in.scala().version())
                    .compilerClasspath(in.scala().compilerClasspath())
                    .scalaLibraryJar(in.scala().libraryJar())
                    .scalaCompilerJar(in.scala().compilerJar())
                    .scalaBridgeJar(in.scala().bridgeJar());
        }
        return req.build();
    }

    /**
     * compile-main's freshness-stamp inputs. The stamp is the cheap gate in front of the action
     * key, so it has to move on the same facts: a scala-version bump must invalidate the stat-only
     * fast path (JK-2295), which it only does if the resolved stdlib jars are in here.
     */
    public static List<Path> mainStampInputs(
            List<Path> compileCp,
            List<Path> processorCp,
            boolean mixedKotlin,
            boolean mixedGroovy,
            BuildLayout layout,
            Path groovyJar,
            ScalaCompile.Setup scala) {
        List<Path> inputs = mainStampClasspath(compileCp, processorCp, mixedKotlin, mixedGroovy, layout, groovyJar);
        if (scala == null) return inputs;
        List<Path> withScala = new ArrayList<>(inputs);
        withScala.addAll(scala.libraryJars());
        return withScala;
    }

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
                    // JAVA_SOURCES already carries the java+scala union (incl. extra-src/plugin-root
                    // .scala) that PlannerSetup published — no need to re-walk the tree for .scala here
                    // (JK-2320). hasScala below reads it directly.
                    List<Path> declared = javaSources(ctx);
                    List<Path> sources = mainJavaSources(declared, ctx.require(LAYOUT), pluginDecls);
                    if (sources != declared) {
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
                    Path groovyJar = cx.mixedGroovy() ? groovyCompileJar(ctx, cas) : null;
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp =
                            (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
                    boolean rerun = in.session().config().rebuildOr(false);
                    // Resolve the Scala toolchain before the stamp check so the stdlib jars are part
                    // of the freshness inputs — a scala-version bump must invalidate the stat-only
                    // fast path (JK-2295). Cheap on a warm closure cache. Gate on the *merged* source
                    // set (which includes extra-src / plugin-root .scala published by PlannerSetup),
                    // not the narrow main-roots walk — otherwise a variant-overlay .scala reaches the
                    // Zinc worker with the Java-only dummy compiler and fails cryptically (JK-2302).
                    boolean hasScala =
                            sources.stream().anyMatch(p -> p.toString().endsWith(".scala"));
                    ScalaCompile.Setup scalaSetup =
                            hasScala ? ScalaCompile.prepare(ctx.require(PROJECT), ctx.require(LOCKFILE), cas) : null;
                    // The shared stamp recipe — the forecast and write-stamp use it too.
                    List<Path> stampInputs = mainStampInputs(
                            baseClasspath,
                            processorCp,
                            mixed,
                            cx.mixedGroovy(),
                            ctx.require(LAYOUT),
                            groovyJar,
                            scalaSetup);
                    if (!rerun
                            && FreshnessStamp.isFresh(
                                    javaOut, BuildStamps.JAVA, sources, stampInputs, ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(BUILD_OUTCOME, "up-to-date");
                        ctx.progress(sources.size());
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    CompileRequest request = mainCompileRequest(new MainCompile(
                            sources,
                            baseClasspath,
                            processorCp,
                            ctx.require(LAYOUT),
                            javaOut,
                            ctx.require(RELEASE),
                            javacArgs,
                            ctx.require(JAVA_HOME),
                            mixed,
                            cx.mixedGroovy(),
                            groovyJar,
                            scalaSetup));
                    String taskId = ActionKey.qualifiedTaskId("compile-main", javaOut);
                    Path javaStateDir = CacheTree.ACTIONS
                            .under(in.cache())
                            .resolve("incremental-java")
                            .resolve(taskId);
                    // Reweight the bar slice now that the real request is known: a CAS
                    // action-cache hit means a cheap hard-link restore (3), not a full
                    // javac (ceil(sources × 0.1)). Uses the exact key
                    // JavaCompile will look up, so the estimate matches what
                    // actually happens — no plan-start reconstruction divergence.
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
                    Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations");
                    Files.createDirectories(genDir);
                    Path workerJar = PluginJar.JAVA_COMPILER.locate(cas);
                    ctx.label("compiling " + sources.size() + " sources");
                    JavaCompile.Result r = JavaCompile.run(
                            taskId,
                            request,
                            BuildIdentity.cacheKeyVersion(),
                            !rerun,
                            !in.ephemeralActions(), // verify-scratch: no persistent residue
                            actionCache.cas(),
                            actionCache,
                            javaStateDir,
                            workerJar,
                            genDir);
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
        List<Dependency> procs = build.dependencies().byScope().get(Scope.PROCESSOR);
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
                    if (FreshnessStamp.hasRemovedSources(classes, BuildStamps.KOTLIN, freshInputs)) {
                        cc.jumpkick.host.PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun
                            && FreshnessStamp.isFresh(
                                    classes, BuildStamps.KOTLIN, freshInputs, classpath, ctx.require(RELEASE))) {
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
                    Path workingDir = CacheTree.ACTIONS
                            .under(in.cache())
                            .resolve("incremental-kotlin")
                            .resolve(taskId);
                    // Mixed module: Kotlin reads the Java declarations from source
                    // (analysis only — it emits no Java bytecode; javac does next).
                    KotlinCompile.Result kr = compileKotlinSources(
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
                        PlannerSupport.forwardWorkerDiagnostics(
                                ctx, "kotlinc", kr.diagnostics(), "kotlinc failed without diagnostics");
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
                    if (FreshnessStamp.hasRemovedSources(classes, BuildStamps.GROOVY, freshInputs)) {
                        cc.jumpkick.host.PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    if (!rerun
                            && FreshnessStamp.isFresh(
                                    classes, BuildStamps.GROOVY, freshInputs, classpath, ctx.require(RELEASE))) {
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
                    GroovyCompile.Result gr = compileGroovySources(
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
                        PlannerSupport.forwardWorkerDiagnostics(
                                ctx, "groovyc", gr.diagnostics(), "groovyc failed without diagnostics");
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
