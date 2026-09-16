// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerKsp.kotlinJavaSourceRoots;
import static cc.jumpkick.runtime.PlannerKsp.kspGeneratedSources;
import static cc.jumpkick.runtime.PlannerKsp.pluginContributedSources;
import static cc.jumpkick.runtime.PlannerKsp.sourceGenStepSteps;
import static cc.jumpkick.runtime.PlannerLang.compileGroovySources;
import static cc.jumpkick.runtime.PlannerLang.compileKotlinSources;
import static cc.jumpkick.runtime.PlannerNative.groovySources;
import static cc.jumpkick.runtime.PlannerNative.javaSources;
import static cc.jumpkick.runtime.PlannerNative.kotlinSources;
import static cc.jumpkick.runtime.PlannerSupport.groovyCompileJar;
import static cc.jumpkick.runtime.PlannerSupport.mergeLanguageOutput;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathProcessors;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClassAbi;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.JavaCompile;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.test.AbiIndex;
import cc.jumpkick.test.AffectedChangedPublish;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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
    // this one request: scalaVersion, compilerClasspath and the Groovy stubs --source-path,
    // joined by javaHome the moment forJavac started hashing it.
    //
    // A text guard can compare which FIELDS each side sets but not which VALUES it puts in them,
    // so the field list is only half the problem. These methods are the other half: both sides
    // call them, so the request is derived once and the question of whether the copies agree
    // stops existing.

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
     * Zinc session, so the Scala sources are javac's inputs too). {@code javaSeed} is the
     * caller's already-walked {@code .java} list, so the common path does not walk twice.
     */
    public static List<Path> javaAndScalaSources(
            JkBuild project, Path moduleDir, boolean compact, @Nullable List<Path> javaSeed) throws IOException {
        List<Path> extraSrcDirs = extraSourceDirs(project, moduleDir);
        List<Path> java = javaSeed;
        List<Path> scala = CompileSupport.collectScalaSources(moduleDir, compact);
        if (!extraSrcDirs.isEmpty()) {
            java = CompileSupport.withExtraSources(java, extraSrcDirs, ".java");
            scala = CompileSupport.withExtraSources(scala, extraSrcDirs, ".scala");
        }
        List<Path> javaSources = java == null ? List.of() : java;
        if (scala.isEmpty()) return javaSources;
        List<Path> withScala = new ArrayList<>(javaSources);
        withScala.addAll(scala);
        return withScala;
    }

    /**
     * {@link #javaAndScalaSources} plus the generated roots compile-main folds in at execute time:
     * plugin {@code contributesSources}, KSP output, and build-logic output. The build re-publishes
     * this union as {@code JAVA_SOURCES} so write-stamp records the set the compile checked.
     */
    public static List<Path> mainJavaSources(
            List<Path> javaAndScala, BuildLayout layout, PluginBuild.@Nullable Declarations decls) throws IOException {
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
     * What {@code BuildPlanner.KOTLIN_SOURCES} holds: the module's {@code .kt} walk plus the
     * {@code [build] extra-src} overlay and plugin-contributed source roots. One derivation for
     * the build, the forecast and the pricing walks — a bare {@code src/main} walk sees no
     * sources at all for a module whose Kotlin lives only under contributed roots.
     */
    public static List<Path> mainKotlinSources(JkBuild project, Path moduleDir, boolean compact) throws IOException {
        return mainKotlinSources(project, moduleDir, CompileSupport.collectKotlinSources(moduleDir, compact));
    }

    /** As above with the module walk already done ({@code kotlinSeed}), so the common path does not walk twice. */
    public static List<Path> mainKotlinSources(JkBuild project, Path moduleDir, List<Path> kotlinSeed)
            throws IOException {
        return CompileSupport.withExtraSources(kotlinSeed, extraSourceDirs(project, moduleDir), ".kt");
    }

    /**
     * {@link #mainKotlinSources} plus the generated Kotlin compile-kotlin folds in at execute time:
     * plugin {@code contributesSources}, KSP output and build-logic output — the same union the
     * Java side takes in {@link #mainJavaSources}. The build re-publishes it as {@code
     * KOTLIN_SOURCES} so write-stamp-kotlin records the set the compile checked, and the forecast
     * derives its stamp inputs from the same body.
     */
    public static List<Path> mainKotlinSourcesWithGenerated(
            List<Path> kotlin, BuildLayout layout, PluginBuild.@Nullable Declarations decls) throws IOException {
        List<Path> generated = pluginContributedSources(layout, decls, ".kt");
        List<Path> ksp = kspGeneratedSources(layout, ".kt");
        List<Path> logic = BuildLogicSupport.generatedSources(layout, ".kt");
        if (generated.isEmpty() && ksp.isEmpty() && logic.isEmpty()) return kotlin;
        List<Path> all = new ArrayList<>(kotlin);
        all.addAll(generated);
        all.addAll(ksp);
        all.addAll(logic);
        return all;
    }

    /** {@link #mainKotlinSources(JkBuild, Path, boolean)}, for {@code GROOVY_SOURCES}. */
    public static List<Path> mainGroovySources(JkBuild project, Path moduleDir, boolean compact) throws IOException {
        return mainGroovySources(project, moduleDir, CompileSupport.collectGroovySources(moduleDir, compact));
    }

    /** As above with the module walk already done ({@code groovySeed}), so the common path does not walk twice. */
    public static List<Path> mainGroovySources(JkBuild project, Path moduleDir, List<Path> groovySeed)
            throws IOException {
        return CompileSupport.withExtraSources(groovySeed, extraSourceDirs(project, moduleDir), ".groovy");
    }

    /**
     * {@link #mainGroovySources} plus the generated Groovy compile-groovy folds in at execute
     * time: plugin {@code contributesSources} and build-logic output. The build re-publishes it as
     * {@code GROOVY_SOURCES} so write-stamp-groovy records the set the compile checked, and the
     * forecast keys the same set.
     */
    public static List<Path> mainGroovySourcesWithGenerated(
            List<Path> groovy, BuildLayout layout, PluginBuild.@Nullable Declarations decls) throws IOException {
        List<Path> generated = pluginContributedSources(layout, decls, ".groovy");
        List<Path> logic = BuildLogicSupport.generatedSources(layout, ".groovy");
        if (generated.isEmpty() && logic.isEmpty()) return groovy;
        List<Path> all = new ArrayList<>(groovy);
        all.addAll(generated);
        all.addAll(logic);
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
            JavacConfig javac,
            Path javaHome,
            boolean mixedKotlin,
            boolean mixedGroovy,
            @Nullable Path groovyJar,
            ScalaCompile.@Nullable Setup scala) {}

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
                .extraOptions(javacOptions(options, in.javac()))
                .javaHome(in.javaHome())
                .processorPath(effectiveProcessorPath(in.processorPath(), classpath));
        return withScala(req, in.scala()).build();
    }

    /**
     * The processor path a compile step hands javac. A module that declares
     * {@code [processor-dependencies]} names its processors, and that path alone is searched: javac's
     * own rule for {@code -processorpath}, and what Maven does under {@code annotationProcessorPaths}.
     * A module that declares none compiles the way javac and Maven do by default: the processors
     * registered on its compile classpath (Lombok or MapStruct declared as a plain or provided
     * dependency) run, found through their {@code META-INF/services} entry. The found entries ARE the
     * request's processor path, so the worker loads them, records what they generate and the key
     * hashes their full content — a discovered processor and a declared one are one lane downstream.
     */
    public static List<Path> effectiveProcessorPath(List<Path> declared, List<Path> classpath) {
        return declared.isEmpty() ? ClasspathProcessors.discover(classpath) : declared;
    }

    /**
     * Every fact compile-test's {@link CompileRequest} is derived from. {@code sources} is {@link
     * PlannerTest.TestSources#javacSources}; {@code classpath} is the base test compile classpath,
     * to which {@link #testCompileRequest} adds the Scala toolchain's library jars.
     */
    public record TestCompile(
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            Path outputDir,
            int release,
            List<String> javacArgs,
            JavacConfig javac,
            Path javaHome,
            ScalaCompile.@Nullable Setup scala) {}

    /** compile-test's request — the build's javac invocation and the forecast's key, from one body. */
    public static CompileRequest testCompileRequest(TestCompile in) {
        List<Path> classpath = in.classpath();
        if (in.scala() != null) {
            classpath = new ArrayList<>(classpath);
            for (Path lib : in.scala().libraryJars()) {
                if (!classpath.contains(lib)) classpath.add(lib);
            }
        }
        CompileRequest.CompileRequestBuilder req = CompileRequest.builder()
                .sources(in.sources())
                .classpath(classpath)
                .outputDir(in.outputDir())
                .release(in.release())
                .extraOptions(javacOptions(in.javacArgs(), in.javac()))
                .javaHome(in.javaHome())
                .processorPath(effectiveProcessorPath(in.processorPath(), classpath));
        return withScala(req, in.scala()).build();
    }

    /** javac's plugin syntax is one argument: the name and its options, space-separated. */
    static final String PLUGIN_FLAG = "-Xplugin:";

    /**
     * The javac argv both compile steps hand the worker: {@code base} (lint, contributed and
     * profile args), then one {@link #PLUGIN_FLAG} element per {@code [javac] plugins} entry, then
     * {@code [javac] args} verbatim. One body, so the build's key and the forecast's agree.
     */
    static List<String> javacOptions(List<String> base, JavacConfig javac) {
        if (javac.isEmpty()) return base;
        List<String> out = new ArrayList<>(base);
        javac.plugins().forEach((name, options) -> {
            StringBuilder arg = new StringBuilder(PLUGIN_FLAG).append(name);
            for (String option : options) arg.append(' ').append(option);
            out.add(arg.toString());
        });
        out.addAll(javac.args());
        return List.copyOf(out);
    }

    /** The plugin names a request invokes, in argv order; empty when it invokes none. */
    public static List<String> pluginNames(CompileRequest request) {
        List<String> names = new ArrayList<>();
        for (String option : request.extraOptions()) {
            if (!option.startsWith(PLUGIN_FLAG)) continue;
            String rest = option.substring(PLUGIN_FLAG.length());
            int space = rest.indexOf(' ');
            names.add(space < 0 ? rest : rest.substring(0, space));
        }
        return names;
    }

    private static CompileRequest.CompileRequestBuilder withScala(
            CompileRequest.CompileRequestBuilder req, ScalaCompile.@Nullable Setup scala) {
        if (scala != null) {
            req.scalaVersion(scala.version())
                    .compilerClasspath(scala.compilerClasspath())
                    .scalaLibraryJar(scala.libraryJar())
                    .scalaCompilerJar(scala.compilerJar())
                    .scalaBridgeJar(scala.bridgeJar());
        }
        return req;
    }

    static Task compileJavaStep(BuildPlanner.Ctx cx, PluginBuild.@Nullable Declarations pluginDecls) {
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        return Task.builder(TaskNames.COMPILE_JAVA)
                .stage(BuildStage.COMPILE)
                .label("Compiling")
                .kind(TaskKind.CPU)
                .requires(javaCompileRequires(cx.mixed(), cx.mixedGroovy(), pluginDecls, cx.ksp()))
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
                .execute(ctx -> runCompileJava(ctx, cx, pluginDecls))
                .build();
    }

    /** The compile-java body: the source union, the freshness stamp, javac in the worker, the ABI index. */
    private static void runCompileJava(
            TaskContext ctx, BuildPlanner.Ctx cx, PluginBuild.@Nullable Declarations pluginDecls) throws Exception {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        Path classes = ctx.require(MAIN_CLASSES);
        // javac always writes to the canonical classes dir (java/main/).
        // The Kotlin incremental compiler gets its own dir (kotlin/main/)
        // so it cannot prune Java's output; the assembler merges both.
        Path javaOut = classes;
        // JAVA_SOURCES already carries the java+scala union (incl. extra-src/plugin-root
        // .scala) that PlannerSetup published — no need to re-walk the tree for .scala here
        // . hasScala below reads it directly.
        List<Path> declared = javaSources(ctx);
        List<Path> sources = mainJavaSources(declared, ctx.require(LAYOUT), pluginDecls);
        if (sources != declared) {
            // Re-publish the union so write-stamp records the same input set
            // this compile checked (else the fast freshness path never holds).
            ctx.put(JAVA_SOURCES, sources);
        }
        if (sources.isEmpty()) {
            ctx.label("no Java sources");
            dropOutputOfRemovedSources(javaOut);
            Files.createDirectories(javaOut);
            ctx.put(BUILD_OUTCOME, "no-sources");
            return;
        }
        List<Path> baseClasspath = ctx.require(CLASSPATH);
        Path groovyJar = cx.mixedGroovy() ? groovyCompileJar(ctx, cas) : null;
        List<Path> processorCp = ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
        boolean rerun = in.session().config().rebuildOr(false);
        // Resolve the Scala toolchain before the stamp check so the stdlib jars are part
        // of the request the stamp is derived from — a scala-version bump must invalidate
        // the fast path. Cheap on a warm closure cache. Gate on the *merged* source
        // set (which includes extra-src / plugin-root .scala published by PlannerSetup),
        // not the narrow main-roots walk — otherwise a variant-overlay .scala reaches the
        // Zinc worker with the Java-only dummy compiler and fails cryptically.
        boolean hasScala = sources.stream().anyMatch(p -> p.toString().endsWith(".scala"));
        ScalaCompile.Setup scalaSetup =
                hasScala ? ScalaCompile.prepare(ctx.require(PROJECT), ctx.require(LOCKFILE), cas) : null;
        List<String> javacArgs = ctx.require(JAVAC_ARGS);
        CompileRequest request = mainCompileRequest(new MainCompile(
                sources,
                baseClasspath,
                processorCp,
                ctx.require(LAYOUT),
                javaOut,
                ctx.require(RELEASE),
                javacArgs,
                ctx.require(PROJECT).build().javac(),
                ctx.require(JAVA_HOME),
                cx.mixed(),
                cx.mixedGroovy(),
                groovyJar,
                scalaSetup));
        // The stamp gates on the option-bearing inputs too — [build] lint/debug, [javac] args and
        // plugins, --profile args, the JDK — through the digest of the very request the action
        // key hashes, and on the classpath through the token lines that key hashes (the ABI of
        // each compile-classpath entry, the content of each processor). write-stamp records the
        // same digest and lines, so an option edit with untouched sources reads stale here instead
        // of packaging the old classes, and a sibling's body-only rewrite reads fresh.
        String optionsDigest = ActionKey.javacOptionsDigest(request);
        ctx.put(JAVA_STAMP_DIGEST, optionsDigest);
        List<String> stampTokens = ActionKey.javacClasspathTokens(request);
        ctx.put(JAVA_STAMP_TOKENS, stampTokens);
        // A fresh stamp vouches for the inputs, not for the tree: one that lost an output its
        // compile record owns falls through to the action cache, whose hit restores the record's
        // whole tree, instead of packaging the subset it has.
        if (!rerun
                && FreshnessStamp.isFresh(
                        javaOut,
                        BuildStamps.JAVA,
                        sources,
                        FreshnessStamp.ClasspathTokens.of(stampTokens),
                        ctx.require(RELEASE),
                        optionsDigest)
                && ModuleOutputs.compileOutputsOnDisk(cx.actionCache(), javaOut)) {
            ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
            ctx.label("up to date");
            ctx.cached();
            ctx.put(BUILD_OUTCOME, "up-to-date");
            ctx.progress(sources.size());
            return;
        }
        String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, javaOut);
        Path javaStateDir = ActionTree.INCREMENTAL_JAVA
                .under(CacheTree.ACTIONS.under(in.cache()))
                .resolve(taskId);
        if (!rerun) reweightForActionCache(ctx, cx.actionCache(), taskId, request, sources.size());
        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations");
        Files.createDirectories(genDir);
        Path workerJar = PluginJar.JAVA_COMPILER.locate(cas);
        ctx.label("compiling " + sources.size() + " sources");
        Path abiFile = AbiIndex.path(ctx.require(LAYOUT).buildDir());
        Map<String, ClassAbi.Fingerprint> preAbi = AbiIndex.load(abiFile);
        ctx.put(PRE_COMPILE_ABI, preAbi);
        JavaCompile.Result r = JavaCompile.run(
                taskId,
                request,
                BuildIdentity.cacheKeyVersion(),
                !rerun,
                !in.ephemeralActions(), // verify-scratch: no persistent residue
                cx.actionCache().cas(),
                cx.actionCache(),
                javaStateDir,
                workerJar,
                genDir,
                WorkerEnv.forModule(
                        ctx.require(PROJECT).build().env(),
                        in.dir(),
                        ctx.require(LAYOUT).moduleTargetDir()));
        ctx.put(ACTION_KEY, r.actionKey());
        ctx.waited(Duration.ofMillis(r.waitMillis()));
        reportJavacResult(ctx, request, r);
        ctx.put(BUILD_OUTCOME, r.outcome());
        ctx.put(COMPILED_MAIN_SOURCES, r.compiledSources());
        advanceAbiIndex(ctx, in, r, abiFile, preAbi);
        advanceSourceApiIndex(in.dir(), ctx.require(LAYOUT).buildDir(), r, sources);
        ctx.progress(sources.size());
    }

    /**
     * The empty-source-set arm's cleanup: a stamp that recorded sources means {@code javaOut} still
     * holds their classes, and with nothing left to compile nothing would ever replace them — they
     * would be packaged as if current. The tree starts clean and the stamp goes with it, so a
     * source that reappears compiles instead of stamp-skipping. Sibling languages that merge into
     * this tree re-merge in assemble-classes, which runs after every compile.
     */
    static void dropOutputOfRemovedSources(Path javaOut) throws IOException {
        if (!FreshnessStamp.hasRemovedSources(javaOut, BuildStamps.JAVA, List.of())) return;
        PathUtil.deleteRecursively(javaOut);
        Files.createDirectories(javaOut);
    }

    /**
     * Reweight the bar slice now that the real request is known: a CAS action-cache hit means a
     * cheap hard-link restore (3), not a full javac (ceil(sources × 0.1)). Uses the exact key
     * JavaCompile will look up, so the estimate matches what actually happens — no plan-start
     * reconstruction divergence.
     */
    private static void reweightForActionCache(
            TaskContext ctx, ActionCache actionCache, String taskId, CompileRequest request, int sources) {
        try {
            boolean restores = actionCache
                    .lookup(ActionKey.forJavac(taskId, request, BuildIdentity.cacheKeyVersion()))
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
        boolean errored = JavacDiagnostics.report(ctx, request.classpath(), r.diagnostics());
        if (!r.success()) {
            // Never fail silently: if no ERROR diagnostic surfaced (crash,
            // swallowed output), say so explicitly.
            if (!errored) {
                ctx.error("javac", "compile failed without compiler diagnostics (outcome: " + r.outcome() + ")");
            }
            throw new RuntimeException("javac reported errors");
        }
        if (r.cacheHit()) {
            ctx.label("cache hit " + r.actionKey().substring(0, 8));
            ctx.cached();
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

    static String[] kotlinCompileRequires(PluginBuild.@Nullable Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        if (ksp) requires.add(TaskNames.KSP);
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    static String[] javaCompileRequires(
            boolean mixed, boolean mixedGroovy, PluginBuild.@Nullable Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        if (mixed) requires.add(TaskNames.COMPILE_KOTLIN);
        if (mixedGroovy) requires.add(TaskNames.COMPILE_GROOVY);
        if (ksp) requires.add(TaskNames.KSP);
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    static String[] groovyCompileRequires(PluginBuild.@Nullable Declarations decls) {
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

    static Task compileKotlinStep(BuildPlanner.Ctx cx, PluginBuild.@Nullable Declarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
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
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes); // compile-java may be skipped
                    List<Path> declaredKt = kotlinSources(ctx);
                    // Plugin-contributed generated Kotlin (a KSP round, a codegen step) joins the
                    // source list exactly like the Java side — the freshness stamp and the plugin
                    // see generated files as ordinary sources.
                    List<Path> ktSources = mainKotlinSourcesWithGenerated(declaredKt, ctx.require(LAYOUT), pluginDecls);
                    if (ktSources != declaredKt) {
                        // Re-publish so write-stamp-kotlin records what this compile checked.
                        ctx.put(KOTLIN_SOURCES, ktSources);
                    }
                    if (ktSources.isEmpty()) {
                        ctx.label("no Kotlin sources");
                        ctx.put(KOTLIN_OUTCOME, "no-sources");
                        return;
                    }
                    List<Path> classpath = ctx.require(CLASSPATH);
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
                        PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    // Mixed module: Kotlin reads the Java declarations from source
                    // (analysis only — it emits no Java bytecode; javac does next).
                    List<Path> javaRoots =
                            kotlinJavaSourceRoots(mixedWithJava, compact, in.dir(), ctx.require(LAYOUT), pluginDecls);
                    // The kotlinc config is resolved ahead of the stamp check, without fetching:
                    // its digest is a stamp input, so a kotlinc arg, a [[kotlin-plugins]] entry or
                    // a JDK switch is stale here even though no source moved.
                    PlannerLang.KotlinConfig config = PlannerLang.kotlinConfig(ctx, in.dir(), javaRoots);
                    // Kotlin compiles into its own dir, then we merge into the
                    // shared classes dir. The incremental compiler owns its output
                    // dir and prunes files it didn't produce — so it can't share a
                    // dir with javac's output (it would delete the.class files).
                    Path ktOut = ctx.require(LAYOUT).kotlinClassesDir();
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, classes);
                    Path workingDir = ActionTree.INCREMENTAL_KOTLIN
                            .under(CacheTree.ACTIONS.under(in.cache()))
                            .resolve(taskId);
                    PlannerLang.KotlinWorker worker =
                            PlannerLang.kotlinWorker(ctx, in, cas, ktSources, classpath, ktOut, workingDir, config);
                    // The stamp spells the classpath by ABI token, like the action key: a sibling
                    // rewritten with the same ABI is fresh here once its token is known — no jar
                    // mtime or content identity is consulted — and one whose ABI moved is stale
                    // before any source mtime is read. A token not yet memoized is what makes the
                    // worker resolve (and fork) ahead of the compile.
                    String optionsDigest = config.digest();
                    ctx.put(KOTLIN_STAMP_DIGEST, optionsDigest);
                    List<String> stampTokens = PlannerLang.kotlinStampTokens(classpath, worker.snapshotter());
                    ctx.put(KOTLIN_STAMP_TOKENS, stampTokens);
                    if (!rerun
                            && FreshnessStamp.isFresh(
                                    classes,
                                    BuildStamps.KOTLIN,
                                    freshInputs,
                                    FreshnessStamp.ClasspathTokens.of(stampTokens),
                                    ctx.require(RELEASE),
                                    optionsDigest)) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(KOTLIN_OUTCOME, "up-to-date");
                        ctx.progress(ktSources.size());
                        return;
                    }
                    ctx.label("compiling " + ktSources.size() + " Kotlin sources");
                    LangCompile.Result kr = compileKotlinSources(ctx, in, actionCache, taskId, worker);
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
                    if (!mixedWithJava) {
                        mergeLanguageOutput(ktOut, classes, ctx.require(LAYOUT).buildDir(), "kotlin");
                    }
                    ctx.put(KOTLIN_OUTCOME, "compiled");
                    ctx.progress(ktSources.size());
                })
                .build();
    }

    static Task compileGroovyStep(BuildPlanner.Ctx cx, PluginBuild.@Nullable Declarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> groovyMainSrcRef = cx.groovyMainSrcRef();
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
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes); // compile-java may be skipped
                    List<Path> declaredGv = groovySources(ctx);
                    // Plugin-contributed generated Groovy joins the source list exactly like the
                    // Kotlin side — the freshness stamp and the worker see generated files as
                    // ordinary sources.
                    List<Path> gvSources = mainGroovySourcesWithGenerated(declaredGv, ctx.require(LAYOUT), pluginDecls);
                    if (gvSources != declaredGv) {
                        // Re-publish so write-stamp-groovy records what this compile checked.
                        ctx.put(GROOVY_SOURCES, gvSources);
                    }
                    if (gvSources.isEmpty()) {
                        ctx.label("no Groovy sources");
                        ctx.put(GROOVY_OUTCOME, "no-sources");
                        return;
                    }
                    List<Path> classpath = ctx.require(CLASSPATH);
                    // Freshness inputs: Groovy sources plus — in a mixed module — the Java
                    // sources, since joint mode resolves against them (any Java edit can make
                    // our.class files or retained stubs stale). Same conservative posture as
                    // compile-kotlin; the action cache behind decides precisely.
                    List<Path> freshInputs = new ArrayList<>(gvSources);
                    if (mixedGroovy) freshInputs.addAll(javaSources(ctx));
                    // A shrunken Groovy source set must not leave dropped classes in the merged
                    // output (the assemble merge into classes/ is additive).
                    if (FreshnessStamp.hasRemovedSources(classes, BuildStamps.GROOVY, freshInputs)) {
                        PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
                    boolean rerun = in.session().config().rebuildOr(false);
                    // Groovy compiles into its own dir, then we merge into the shared classes
                    // dir (the worker's action cache snapshots its whole output dir — it must
                    // never share one with javac).
                    Path gvOut = ctx.require(LAYOUT).groovyClassesDir();
                    // Mixed module: joint mode sweeps the Java roots for resolution only
                    // stubs are retained for javac's sourcepath; jk's javac worker stays
                    // authoritative for the real Java outputs.
                    GroovycRequest request = PlannerLang.groovyRequest(
                            ctx,
                            in,
                            cas,
                            gvSources,
                            classpath,
                            gvOut,
                            mixedGroovy
                                    ? kotlinJavaSourceRoots(true, compact, in.dir(), ctx.require(LAYOUT), pluginDecls)
                                    : null,
                            mixedGroovy ? ctx.require(LAYOUT).groovyStubsDir() : null);
                    // The groovyc args and toolchain are stamp inputs too (see compile-kotlin), and
                    // the classpath enters as the token lines the action key hashes: the ABI of
                    // each compile-classpath entry, the content of the worker closure and of each
                    // processor. A sibling's body-only rewrite reads fresh; a full groovyc is the
                    // only alternative, so this is the one skip Groovy gets.
                    String optionsDigest = PlannerLang.groovyStampDigest(ctx, in.dir());
                    ctx.put(GROOVY_STAMP_DIGEST, optionsDigest);
                    List<String> stampTokens = ActionKey.groovycClasspathTokens(request);
                    ctx.put(GROOVY_STAMP_TOKENS, stampTokens);
                    if (!rerun
                            && FreshnessStamp.isFresh(
                                    classes,
                                    BuildStamps.GROOVY,
                                    freshInputs,
                                    FreshnessStamp.ClasspathTokens.of(stampTokens),
                                    ctx.require(RELEASE),
                                    optionsDigest)) {
                        ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
                        ctx.label("up to date");
                        ctx.cached();
                        ctx.put(GROOVY_OUTCOME, "up-to-date");
                        ctx.progress(gvSources.size());
                        return;
                    }
                    ctx.label("compiling " + gvSources.size() + " Groovy sources");
                    String taskId = ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, classes);
                    LangCompile.Result gr = compileGroovySources(ctx, in, actionCache, request, taskId);
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
                    if (!mixedGroovy) {
                        mergeLanguageOutput(gvOut, classes, ctx.require(LAYOUT).buildDir(), "groovy");
                    }
                    ctx.put(GROOVY_OUTCOME, "compiled");
                    ctx.progress(gvSources.size());
                })
                .build();
    }
}
