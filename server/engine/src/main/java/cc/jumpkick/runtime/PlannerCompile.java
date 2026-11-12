// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerKsp.kspGeneratedSources;
import static cc.jumpkick.runtime.PlannerKsp.pluginContributedSources;
import static cc.jumpkick.runtime.PlannerKsp.sourceGenStepSteps;
import static cc.jumpkick.runtime.PlannerLang.compileGroovySources;
import static cc.jumpkick.runtime.PlannerLang.compileKotlinSources;
import static cc.jumpkick.runtime.PlannerNative.groovySources;
import static cc.jumpkick.runtime.PlannerNative.javaSources;
import static cc.jumpkick.runtime.PlannerNative.kotlinSources;
import static cc.jumpkick.runtime.base.ResourceMirror.mergeLanguageOutput;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathProcessors;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.CompilerJdk;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
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
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.task.LangCompile;
import cc.jumpkick.task.MainAbi;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.test.AbiIndex;
import cc.jumpkick.test.AffectedChangedPublish;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
     * Zinc session, so the Scala sources are javac's inputs too) and the {@code .java} under
     * {@code src/main/scala}. {@code javaSeed} is the caller's already-walked {@code .java} list,
     * so the common path does not walk twice.
     */
    public static List<Path> javaAndScalaSources(
            JkBuild project, Path moduleDir, boolean compact, @Nullable List<Path> javaSeed) throws IOException {
        List<Path> extraSrcDirs = extraSourceDirs(project, moduleDir);
        List<Path> java = CompileSupport.concatDistinct(
                javaSeed == null ? List.of() : javaSeed, CompileSupport.collectJavaInScalaRoot(moduleDir, compact));
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
     * plugin {@code contributesSources}, KSP output when {@code ksp} (the module runs a main round),
     * and build-logic output. The build re-publishes this union as {@code JAVA_SOURCES}; a union
     * already taken comes back unchanged.
     */
    public static List<Path> mainJavaSources(
            List<Path> javaAndScala, BuildLayout layout, @Nullable PluginDeclarations decls, boolean ksp)
            throws IOException {
        return withGenerated(
                javaAndScala,
                pluginContributedSources(layout, decls, ".java"),
                ksp ? kspGeneratedSources(layout, ".java") : List.of(),
                BuildLogicSupport.generatedSources(layout, ".java"));
    }

    /**
     * {@code declared} then each generated source it does not hold yet, in order. A union already
     * taken comes back as the same list, so a step reading a set an earlier step re-published
     * derives the set that step compiled.
     */
    @SafeVarargs
    private static List<Path> withGenerated(List<Path> declared, List<Path>... generated) {
        Set<Path> seen = null;
        List<Path> all = null;
        for (List<Path> more : generated) {
            for (Path p : more) {
                if (seen == null) seen = new HashSet<>(declared);
                if (!seen.add(p)) continue;
                if (all == null) all = new ArrayList<>(declared);
                all.add(p);
            }
        }
        return all == null ? declared : all;
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
     * plugin {@code contributesSources}, KSP output when {@code ksp} and build-logic output — the
     * same union the Java side takes in {@link #mainJavaSources}. The build re-publishes it as {@code
     * KOTLIN_SOURCES}, and the forecast derives its stamp inputs from the same body.
     */
    public static List<Path> mainKotlinSourcesWithGenerated(
            List<Path> kotlin, BuildLayout layout, @Nullable PluginDeclarations decls, boolean kspRound)
            throws IOException {
        return withGenerated(
                kotlin,
                pluginContributedSources(layout, decls, ".kt"),
                kspRound ? kspGeneratedSources(layout, ".kt") : List.of(),
                BuildLogicSupport.generatedSources(layout, ".kt"));
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
     * {@code GROOVY_SOURCES}, and the forecast keys the same set.
     */
    public static List<Path> mainGroovySourcesWithGenerated(
            List<Path> groovy, BuildLayout layout, @Nullable PluginDeclarations decls) throws IOException {
        return withGenerated(
                groovy,
                pluginContributedSources(layout, decls, ".groovy"),
                BuildLogicSupport.generatedSources(layout, ".groovy"));
    }

    /**
     * The Groovy jar the test compilers read beside a module's classes, for the build and the
     * forecast alike: a module with Groovy main or test sources ({@code groovy}) needs it, every
     * Groovy class implementing {@code groovy.lang.GroovyObject}; null for one without.
     */
    static @Nullable Path testGroovyJar(JkBuild project, Lockfile lock, Cas cas, boolean groovy) throws IOException {
        return groovy ? CompileInputs.groovyCompileJar(project, lock, cas) : null;
    }

    /** compile-main's request — the build's javac invocation and the forecast's key, from one body. */
    @KeyOwner(step = TaskNames.COMPILE_JAVA)
    public static CompileRequest mainCompileRequest(CompileInputs.MainCompile in) {
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
            // output afterwards. Unconditional for a mixed module: javac ignores a source-path
            // entry that does not exist, and a key decided by what is on disk would differ between
            // a fresh checkout and a built one.
            options = new ArrayList<>(options);
            options.add("--source-path");
            options.add(in.layout().groovyStubsDir().toAbsolutePath().toString());
        }
        List<String> javacOpts = javacOptions(options, in.javac());
        List<Path> processorPath = effectiveProcessorPath(in.processorPath(), in.contributedProcessors(), classpath);
        CompileRequest.CompileRequestBuilder req = CompileRequest.builder()
                .sources(in.sources())
                .classpath(classpath)
                .outputDir(in.outputDir())
                .release(in.release())
                .extraOptions(javacOpts)
                .javaHome(in.javaHome())
                .pinnedJdk(in.pinnedJdk())
                .processorPath(processorPath);
        if (in.resources() != null && ActionKey.processorsMayRun(processorPath, javacOpts)) {
            req.resourcesDigest(resourcesDigest(in.resources(), in.layout()));
        }
        return withScala(req, in.scala()).build();
    }

    private static String resourcesDigest(ProcessorResources resources, BuildLayout layout) {
        try {
            return resources.digest(layout);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the main resources the processors see", e);
        }
    }

    /**
     * The processor path a compile step hands javac. A module that declares
     * {@code [processor-dependencies]} names its processors, and that path alone is searched: javac's
     * own rule for {@code -processorpath}, and what Maven does under {@code annotationProcessorPaths}.
     * A module that declares none compiles the way javac and Maven do by default: when any
     * compile-classpath entry registers a processor through its {@code META-INF/services} entry, the
     * whole compile classpath is the processor path, in classpath order. That is javac's own
     * behaviour without {@code -processorpath} — the processor loads its own dependencies from the
     * same classpath the module compiles against, so a processor whose helpers ride along as
     * ordinary dependencies (a Log4j plugin processor, Dagger's compiler) runs. A classpath that
     * registers no processor hands javac none, so the key hashes the classpath by ABI alone.
     *
     * <p>The processors the module's plugins contribute ({@code contributed}) are appended to
     * whichever of the two the module's own path is, each jar once. Which one it is depends on
     * {@code declared} alone: a contribution never turns discovery off, so a classpath processor
     * (Lombok as a plain dependency) runs beside the plugin's, and a declared path keeps every
     * processor it names.
     */
    public static List<Path> effectiveProcessorPath(List<Path> declared, List<Path> contributed, List<Path> classpath) {
        List<Path> own = !declared.isEmpty()
                ? declared
                : ClasspathProcessors.discover(classpath).isEmpty() ? List.of() : classpath;
        if (contributed.isEmpty()) return List.copyOf(own);
        List<Path> out = new ArrayList<>(own);
        for (Path jar : contributed) {
            if (!out.contains(jar)) out.add(jar);
        }
        return List.copyOf(out);
    }

    /**
     * Every fact compile-test's {@link CompileRequest} is derived from. {@code sources} is {@link
     * TestSources#javacSources}; {@code classpath} is the base test compile classpath,
     * to which {@link #testCompileRequest} adds the Scala toolchain's library jars; {@code
     * mainClasses} is the module's own main classes tree, whose module descriptor (when it has one)
     * the test sources patch. {@code processorPath} and {@code contributedProcessors} are as for
     * {@link CompileInputs.MainCompile}.
     */
    public record TestCompile(
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            List<Path> contributedProcessors,
            Path outputDir,
            int release,
            List<String> javacArgs,
            JavacConfig javac,
            Path javaHome,
            ScalaCompile.@Nullable Setup scala,
            @Nullable Path mainClasses,
            boolean discover,
            boolean pinnedJdk) {

        /** A compile-test that discovers classpath processors when it declares none. */
        public TestCompile(
                List<Path> sources,
                List<Path> classpath,
                List<Path> processorPath,
                List<Path> contributedProcessors,
                Path outputDir,
                int release,
                List<String> javacArgs,
                JavacConfig javac,
                Path javaHome,
                ScalaCompile.@Nullable Setup scala,
                @Nullable Path mainClasses) {
            this(
                    sources,
                    classpath,
                    processorPath,
                    contributedProcessors,
                    outputDir,
                    release,
                    javacArgs,
                    javac,
                    javaHome,
                    scala,
                    mainClasses,
                    true,
                    false);
        }
    }

    /**
     * compile-test's javac options. A test compile that may not discover processors and declares
     * none runs with {@code -proc:none}: Maven's testCompile without {@code annotationProcessorPaths}
     * on JDK 23 and later runs no processor at all, and javac on an older JDK, given no processor
     * path, would otherwise go looking on the test classpath.
     */
    private static List<String> testJavacOptions(TestCompile in) {
        List<String> base = javacOptions(in.javacArgs(), in.javac());
        if (in.discover()
                || !in.processorPath().isEmpty()
                || !in.contributedProcessors().isEmpty()) return base;
        List<String> out = new ArrayList<>(base);
        out.add("-proc:none");
        return List.copyOf(out);
    }

    /** compile-test's request — the build's javac invocation and the forecast's key, from one body. */
    @KeyOwner(step = TaskNames.COMPILE_TEST)
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
                .extraOptions(ModularCompile.testOptions(testJavacOptions(in), in.mainClasses(), in.sources()))
                .javaHome(in.javaHome())
                .pinnedJdk(in.pinnedJdk())
                .processorPath(
                        in.discover() || !in.processorPath().isEmpty()
                                ? effectiveProcessorPath(in.processorPath(), in.contributedProcessors(), classpath)
                                : List.copyOf(in.contributedProcessors()));
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

    static Task compileJavaStep(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        return Task.builder(TaskNames.COMPILE_JAVA)
                .forecast(StepForecast.priced(PlannerCompileForecast::java))
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
    private static void runCompileJava(TaskContext ctx, BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls)
            throws Exception {
        BuildPlanner.Inputs in = cx.in();
        Cas cas = cx.cas();
        Path classes = ctx.require(MAIN_CLASSES);
        // javac always writes to the canonical classes dir (java/main/).
        // The Kotlin incremental compiler gets its own dir (kotlin/main/)
        // so it cannot prune Java's output; the assembler merges both.
        Path javaOut = classes;
        // JAVA_SOURCES already carries the java+scala union (incl. extra-src/plugin-root .scala)
        // that PlannerSetup published: the Scala toolchain is decided from it.
        List<Path> declared = javaSources(ctx);
        List<Path> sources = mainJavaSources(declared, ctx.require(LAYOUT), pluginDecls, cx.ksp());
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
        CompileInputs.Module module = compileInputs(ctx, cx, pluginDecls);
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
                && CompileInputs.javaStamp(module, request, stampTokens).isPresent()
                && CompileInputs.outputsOnDisk(cx.actionCache(), module, null)) {
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
        if (!rerun) reweightForActionCache(ctx, cx.actionCache(), taskId, request, sources.size());
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
                cx.actionCache().cas(),
                cx.actionCache(),
                javaStateDir,
                workerJar,
                genDir,
                WorkerEnv.forModule(
                        ctx.require(PROJECT).build().env(),
                        in.dir(),
                        ctx.require(LAYOUT).moduleTargetDir()));
        HeapNotes.flush(ctx);
        ctx.waited(Duration.ofMillis(r.waitMillis()));
        reportJavacResult(ctx, request, r);
        afterJavac(ctx, cx, request, r);
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
    private static void afterJavac(TaskContext ctx, BuildPlanner.Ctx cx, CompileRequest request, JavaCompile.Result r)
            throws IOException {
        warnMovedSources(ctx, r.movedSources());
        ProcessedResources.afterCompile(
                Objects.requireNonNull(request.outputDir(), "compile-main writes a classes tree"),
                incremental(ctx),
                request.resourcesDigest() != null,
                cx.actionCache(),
                r.cacheHit() ? r.actionKey() : null);
    }

    /** What a compiler-worker failure names: the module's coordinates when the plan carries them, and the {@code step}. */
    static String compileLabel(TaskContext ctx, String step) {
        return ctx.get(PROJECT)
                        .map(build ->
                                build.project().group() + ":" + build.project().name() + " ")
                        .orElse("")
                + step;
    }

    /**
     * A source edited while its compiler ran is named in the report: the classes may come from
     * either version, so the compile is not recorded and the next build compiles the module again.
     */
    static void warnMovedSources(TaskContext ctx, List<Path> moved) {
        if (moved.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (Path p : moved) {
            if (names.length() > 0) names.append(", ");
            names.append(p.getFileName());
        }
        ctx.warn(
                "sources-moved",
                (moved.size() == 1 ? "a source" : moved.size() + " sources") + " changed while the compile ran: "
                        + names + "; the next build recompiles the module");
    }

    /**
     * The empty-source-set arm's cleanup: a stamp that recorded sources means {@code javaOut} still
     * holds their classes, and with nothing left to compile nothing would ever replace them — they
     * would be packaged as if current. The tree starts clean and the stamp goes with it, so a
     * source that reappears compiles instead of stamp-skipping. Sibling languages that merge into
     * this tree re-merge in assemble-classes, which runs after every compile.
     */
    static boolean dropOutputOfRemovedSources(Path javaOut) throws IOException {
        if (!FreshnessStamp.hasRemovedSources(javaOut, BuildStamps.JAVA, List.of())) return false;
        PathUtil.deleteRecursively(javaOut);
        Files.createDirectories(javaOut);
        return true;
    }

    /** {@code target/incremental}: the resource and compile ledgers beside the classes tree. */
    static Path incremental(TaskContext ctx) {
        return ctx.require(LAYOUT).buildDir().resolve("incremental");
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
                    .lookup(JavaCompile.key(taskId, request, BuildIdentity.cacheKeyVersion()))
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

    static String[] kotlinCompileRequires(@Nullable PluginDeclarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE,
                // The resources are in the class output before any compile reads it.
                TaskNames.COPY_RESOURCES));
        if (ksp) requires.add(TaskNames.KSP);
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    static String[] javaCompileRequires(
            boolean mixed, boolean mixedGroovy, @Nullable PluginDeclarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE,
                // The resources are in the class output before any compile reads it.
                TaskNames.COPY_RESOURCES));
        if (mixed) requires.add(TaskNames.COMPILE_KOTLIN);
        if (mixedGroovy) requires.add(TaskNames.COMPILE_GROOVY);
        if (ksp) requires.add(TaskNames.KSP);
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    static String[] groovyCompileRequires(@Nullable PluginDeclarations decls, boolean kotlinModule) {
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE,
                // The resources are in the class output before any compile reads it.
                TaskNames.COPY_RESOURCES));
        // Kotlin compiles first in a module that has both, and Groovy reads its output.
        if (kotlinModule) requires.add(TaskNames.COMPILE_KOTLIN);
        requires.addAll(sourceGenStepSteps(decls));
        return requires.toArray(new String[0]);
    }

    static Task compileKotlinStep(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        boolean compact = cx.compact();
        return Task.builder(TaskNames.COMPILE_KOTLIN)
                .forecast(StepForecast.priced(PlannerCompileForecast::kotlin))
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
                .execute(ctx -> runCompileKotlin(ctx, cx, pluginDecls))
                .build();
    }

    /** The compile-kotlin body: the source union, the freshness stamp, kotlinc in the worker. */
    private static void runCompileKotlin(TaskContext ctx, BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls)
            throws Exception {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Path classes = ctx.require(MAIN_CLASSES);
        Files.createDirectories(classes); // compile-java may be skipped
        List<Path> declaredKt = kotlinSources(ctx);
        // Plugin-contributed generated Kotlin (a KSP round, a codegen step) joins the source list
        // exactly like the Java side: the freshness stamp and the plugin see generated files as
        // ordinary sources.
        List<Path> ktSources = mainKotlinSourcesWithGenerated(declaredKt, ctx.require(LAYOUT), pluginDecls, cx.ksp());
        if (ktSources != declaredKt) ctx.put(KOTLIN_SOURCES, ktSources);
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
        CompileInputs.Module module = compileInputs(ctx, cx, pluginDecls);
        CompileInputs.Kotlin inputs = CompileInputs.kotlin(module, ktSources, mainJavaSources(ctx, cx, pluginDecls));
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
        if (ktKept.isPresent()
                && CompileInputs.kotlinStampFresh(module, inputs, stampTokens)
                && CompileInputs.outputsOnDisk(actionCache, module, BuildStamps.KOTLIN)) {
            ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
            JavacDiagnostics.reportWarnings(ctx, JavacDiagnostics.KOTLINC, List.of(), ktKept.get());
            ctx.label("up to date");
            ctx.cached();
            ctx.put(KOTLIN_COMPILE, CompileStamp.upToDate());
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
        if (!cx.mixedWithJava()) {
            mergeLanguageOutput(
                    module.layout().kotlinClassesDir(), classes, module.layout().buildDir(), "kotlin");
        }
        ctx.put(
                KOTLIN_COMPILE,
                new CompileStamp("compiled", freshInputs, optionsDigest, stampTokens, clock, kr.actionKey()));
        ctx.progress(ktSources.size());
    }

    static Task compileGroovyStep(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        Supplier<EffortWeights.Plan> plan = cx.plan();
        AtomicReference<@Nullable List<Path>> groovyMainSrcRef = cx.groovyMainSrcRef();
        boolean compact = cx.compact();
        return Task.builder(TaskNames.COMPILE_GROOVY)
                .forecast(StepForecast.priced(PlannerCompileForecast::groovy))
                .stage(BuildStage.COMPILE)
                .label("Groovy")
                .kind(TaskKind.CPU)
                // Groovy compiles first (joint mode reads Java *declarations* by sweeping the
                // .java roots; javac runs after it in a mixed module), so it only needs the
                // base steps plus any source-generating plugin steps.
                .requires(groovyCompileRequires(pluginDecls, cx.kotlinModule()))
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
                .execute(ctx -> runCompileGroovy(ctx, cx, pluginDecls))
                .build();
    }

    /** The compile-groovy body: the source union, the freshness stamp, groovyc in the worker. */
    private static void runCompileGroovy(TaskContext ctx, BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls)
            throws Exception {
        BuildPlanner.Inputs in = cx.in();
        ActionCache actionCache = cx.actionCache();
        Path classes = ctx.require(MAIN_CLASSES);
        Files.createDirectories(classes); // compile-java may be skipped
        List<Path> declaredGv = groovySources(ctx);
        // Plugin-contributed generated Groovy joins the source list exactly like the Kotlin side.
        List<Path> gvSources = mainGroovySourcesWithGenerated(declaredGv, ctx.require(LAYOUT), pluginDecls);
        if (gvSources != declaredGv) ctx.put(GROOVY_SOURCES, gvSources);
        if (gvSources.isEmpty()) {
            ctx.label("no Groovy sources");
            StepWarnings.clear(ctx, TaskNames.COMPILE_GROOVY);
            ctx.put(GROOVY_COMPILE, CompileStamp.noSources());
            return;
        }
        // The stamp covers the Groovy sources plus, in joint mode, the Java sources it resolves
        // against: the same conservative posture as compile-kotlin, the action cache behind it
        // decides precisely.
        CompileInputs.Module module = compileInputs(ctx, cx, pluginDecls);
        CompileInputs.Groovy inputs = CompileInputs.groovy(
                module,
                gvSources,
                mainJavaSources(ctx, cx, pluginDecls),
                mainKotlinSourcesWithGenerated(kotlinSources(ctx), module.layout(), pluginDecls, cx.ksp()));
        List<Path> freshInputs = inputs.stampInputs();
        // A shrunken Groovy source set must not leave dropped classes in the merged output (the
        // assemble merge into classes/ is additive).
        if (FreshnessStamp.hasRemovedSources(classes, BuildStamps.GROOVY, freshInputs)) {
            PathUtil.deleteRecursively(classes);
            Files.createDirectories(classes);
            ResourceMirror.relay(classes, incremental(ctx));
            // Kotlin published here earlier in this run; its own dir still holds it.
            if (cx.kotlinModule() && !cx.mixedWithJava()) {
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
        if (gvKept.isPresent()
                && CompileInputs.groovyStampFresh(module, inputs, stampTokens)
                && CompileInputs.outputsOnDisk(actionCache, module, BuildStamps.GROOVY)) {
            ctx.reweight(EffortWeights.TOKEN); // stamp skip — token tick
            JavacDiagnostics.reportWarnings(ctx, JavacDiagnostics.GROOVYC, List.of(), gvKept.get());
            ctx.label("up to date");
            ctx.cached();
            ctx.put(GROOVY_COMPILE, CompileStamp.upToDate());
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
        if (!cx.mixedGroovy())
            mergeLanguageOutput(gvOut, classes, module.layout().buildDir(), "groovy");
        ctx.put(
                GROOVY_COMPILE,
                new CompileStamp("compiled", freshInputs, optionsDigest, stampTokens, clock, gr.actionKey()));
        ctx.progress(gvSources.size());
    }

    /**
     * The facts every main compile of this module reads, from the plan's state: what {@link
     * CompileInputs} derives each compiler's inputs from, as the forecast derives them from its
     * prepared module.
     */
    static CompileInputs.Module compileInputs(
            TaskContext ctx, BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        BuildPlanner.Inputs in = cx.in();
        JkBuild project = ctx.require(PROJECT);
        return new CompileInputs.Module(
                project,
                in.dir(),
                in.cache(),
                cx.cas(),
                ctx.require(LOCKFILE),
                ctx.require(LAYOUT),
                cx.shape(),
                cx.ksp(),
                ctx.require(CLASSPATHS),
                pluginDecls,
                ctx.require(RELEASE),
                ctx.require(JAVA_HOME),
                ctx.require(JAVAC_ARGS),
                CompilerJdk.pinned(project, in.session()));
    }

    /** The Java union compile-java compiles, as a step that runs before it reads it. */
    private static List<Path> mainJavaSources(
            TaskContext ctx, BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) throws IOException {
        return mainJavaSources(javaSources(ctx), ctx.require(LAYOUT), pluginDecls, cx.ksp());
    }
}
