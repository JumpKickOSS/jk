// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerKsp.kspGeneratedSources;
import static cc.jumpkick.runtime.PlannerKsp.pluginContributedSources;
import static cc.jumpkick.runtime.PlannerKsp.sourceGenStepSteps;

import cc.jumpkick.compile.ClasspathProcessors;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KeyOwner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What the main compiles read and send, derived once for the build's compile steps ({@link
 * CompileSteps}, {@link LangCompileSteps}) and their forecasts: the source unions and the javac
 * requests.
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

    /** The steps each main compile waits on: setup, the resource copy, KSP and the source generators. */
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
}
