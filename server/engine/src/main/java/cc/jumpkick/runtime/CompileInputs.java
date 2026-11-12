// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KeyOwner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What each main compile's request, freshness stamp and action key are derived from, one owner per
 * language over the module's {@link ModuleShape}, its {@link ModuleClasspaths} and its effective
 * manifest, and the freshness-stamp check each compile answers first. The build's compile steps
 * and their forecasts both call these, so neither side gathers a compiler's inputs or checks its
 * stamp in a body of its own.
 */
final class CompileInputs {

    private CompileInputs() {}

    /**
     * The module facts every main compile reads: the build's steps take them from the plan's state,
     * the forecast from its prepared module.
     *
     * @param ksp the module's main KSP round leaves generated Java where kotlinc and joint groovyc
     *     read it
     * @param pinnedJdk the module pins the JDK it compiles with ({@code CompilerJdk.pinned})
     */
    record Module(
            JkBuild project,
            Path dir,
            Path cache,
            Cas cas,
            Lockfile lock,
            BuildLayout layout,
            ModuleShape shape,
            boolean ksp,
            ModuleClasspaths classpaths,
            @Nullable PluginDeclarations decls,
            int release,
            Path javaHome,
            List<String> javacArgs,
            boolean pinnedJdk) {}

    /**
     * Every fact compile-main's request is derived from. {@code classpath} is the <em>base</em>
     * compile classpath (lock + workspace siblings); {@link PlannerCompile#mainCompileRequest} adds
     * the sibling-language outputs, because which of those belong on it is part of the derivation
     * and not a caller's decision. {@code processorPath} is the module's declared processor path,
     * {@code contributedProcessors} the plugins' ({@link PlannerCompile#effectiveProcessorPath}).
     * {@code resources} are the main resources copied in ahead of the compile; their digest joins
     * the request when processors may run, since a processor can read them back. Null for a compile
     * that has none to read.
     */
    public record MainCompile(
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            List<Path> contributedProcessors,
            BuildLayout layout,
            Path outputDir,
            int release,
            List<String> javacArgs,
            JavacConfig javac,
            Path javaHome,
            boolean mixedKotlin,
            boolean mixedGroovy,
            @Nullable Path groovyJar,
            ScalaCompile.@Nullable Setup scala,
            @Nullable ProcessorResources resources,
            boolean pinnedJdk) {}

    /**
     * compile-java's inputs over {@code sources}, the Java (and Scala) union the compile reads.
     * {@code groovyJar} is the version-matched Groovy jar a mixed Groovy module puts on javac's
     * classpath ({@link #groovyJar}). The Scala toolchain is resolved when a {@code .scala} source is
     * among them: its library jars, version and compiler closure are request inputs.
     */
    @KeyOwner(step = TaskNames.COMPILE_JAVA)
    static MainCompile java(Module m, List<Path> sources, @Nullable Path groovyJar) throws IOException {
        boolean hasScala = sources.stream().anyMatch(p -> p.toString().endsWith(".scala"));
        ScalaCompile.Setup scala = hasScala ? ScalaCompile.prepare(m.project(), m.lock(), m.cas()) : null;
        return new MainCompile(
                sources,
                m.classpaths().main(),
                m.classpaths().javacProcessor(),
                m.classpaths().contributedProcessor(),
                m.layout(),
                m.layout().classesDir(),
                m.release(),
                m.javacArgs(),
                m.project().javac(),
                m.javaHome(),
                m.shape().mixed(),
                m.shape().mixedGroovy(),
                groovyJar,
                scala,
                new ProcessorResources(m.dir(), m.project(), m.shape().compact()),
                m.pinnedJdk());
    }

    /**
     * compile-java's freshness stamp when it vouches for {@code request}: its sources, the classpath
     * token lines {@code tokens} (the build's ABI tokens, or the forecast's view of a sibling the
     * build restores first) and the request's option digest. Empty when the stamp is missing or
     * stale.
     */
    @KeyOwner(step = TaskNames.COMPILE_JAVA)
    static Optional<FreshnessStamp.Stamp> javaStamp(Module m, CompileRequest request, List<String> tokens)
            throws IOException {
        return FreshnessStamp.freshStamp(
                m.layout().classesDir(),
                BuildStamps.JAVA,
                request.sources(),
                FreshnessStamp.ClasspathTokens.of(tokens),
                m.release(),
                ActionKey.javacOptionsDigest(request));
    }

    /**
     * Whether the classes tree still holds every output a compile's stamped record owns: compile-java's
     * against every stamp in the tree (the other languages merge into it) when {@code stamp} is null,
     * another compile's against the record its own stamp names. A fresh stamp over a partly wiped tree
     * is not up to date: the action cache's hit restores the record's whole tree.
     */
    static boolean outputsOnDisk(ActionCache actionCache, Module m, @Nullable String stamp) throws IOException {
        Path classes = m.layout().classesDir();
        return stamp == null
                ? ModuleOutputs.compileOutputsOnDisk(actionCache, classes)
                : ModuleOutputs.compileOutputsOnDisk(actionCache, classes, stamp);
    }

    /** The Groovy jar compile-java reads in a mixed Groovy module ({@link ModuleClasspaths#groovyJar}); null in any other. */
    static @Nullable Path groovyJar(Module m) throws IOException {
        return ModuleClasspaths.groovyJar(
                m.project(), m.lock(), m.cas(), m.shape().mixedGroovy());
    }

    /**
     * compile-kotlin's inputs: its sources, what its freshness stamp covers (the Kotlin sources and,
     * when javac runs in the module, the Java sources kotlinc reads declarations from), its compile
     * classpath and the kotlinc config.
     */
    record Kotlin(List<Path> sources, List<Path> stampInputs, List<Path> classpath, PlannerLang.KotlinConfig config) {}

    /** compile-kotlin's inputs over the Kotlin and Java unions the module compiles. */
    @KeyOwner(step = TaskNames.COMPILE_KOTLIN)
    static Kotlin kotlin(Module m, List<Path> kotlinSources, List<Path> javaSources) {
        boolean withJava = m.shape().java();
        List<Path> stampInputs = new ArrayList<>(kotlinSources);
        if (withJava) stampInputs.addAll(javaSources);
        List<Path> javaRoots = PlannerKsp.kotlinJavaSourceRoots(
                withJava, m.shape().compact(), m.dir(), m.layout(), m.decls(), m.ksp());
        PlannerLang.KotlinConfig config =
                PlannerLang.kotlinConfig(m.project(), m.lock(), m.dir(), m.release(), m.javaHome(), javaRoots);
        return new Kotlin(kotlinSources, stampInputs, m.classpaths().main(), config);
    }

    /** compile-kotlin's task id: what its action key and its incremental state are named by. */
    static String kotlinTaskId(Module m) {
        return ActionKey.qualifiedTaskId(TaskNames.COMPILE_KOTLIN, m.layout().classesDir());
    }

    /** compile-kotlin's incremental state dir, before the kotlinc config names its own under it. */
    static Path kotlinStateDir(Module m) {
        return ActionKey.stateDir(
                ActionTree.INCREMENTAL_KOTLIN.under(CacheTree.ACTIONS.under(m.cache())),
                TaskNames.COMPILE_KOTLIN,
                m.layout().classesDir());
    }

    /**
     * Whether compile-kotlin's freshness stamp vouches for {@code k}: its stamp inputs, the classpath
     * token lines {@code tokens} ({@link PlannerLang#kotlinStampTokens}) and the kotlinc config's
     * digest.
     */
    @KeyOwner(step = TaskNames.COMPILE_KOTLIN)
    static boolean kotlinStampFresh(Module m, Kotlin k, List<String> tokens) throws IOException {
        return FreshnessStamp.isFresh(
                m.layout().classesDir(),
                BuildStamps.KOTLIN,
                k.stampInputs(),
                FreshnessStamp.ClasspathTokens.of(tokens),
                m.release(),
                k.config().digest());
    }

    /** The kotlinc worker of {@code k}, building into the Kotlin classes dir under its incremental state. */
    @KeyOwner(step = TaskNames.COMPILE_KOTLIN)
    static PlannerLang.KotlinWorker kotlinWorker(Module m, Kotlin k) {
        return PlannerLang.kotlinWorker(
                m.project(),
                m.dir(),
                m.cache(),
                m.cas(),
                k.sources(),
                k.classpath(),
                m.layout().kotlinClassesDir(),
                kotlinStateDir(m),
                k.config());
    }

    /**
     * compile-groovy's inputs: its sources, what its freshness stamp covers (the Groovy sources and,
     * in joint mode, the Java sources it resolves against), its classpath, and in joint mode the
     * Java roots it sweeps and the stubs dir javac reads.
     */
    record Groovy(
            List<Path> sources,
            List<Path> stampInputs,
            List<Path> classpath,
            @Nullable List<Path> javaRoots,
            @Nullable Path stubsOut) {}

    /**
     * compile-groovy's inputs over the Groovy, Java and Kotlin unions the module compiles: Kotlin
     * compiles first, so its output is on groovyc's classpath when it compiled anything.
     */
    @KeyOwner(step = TaskNames.COMPILE_GROOVY)
    static Groovy groovy(Module m, List<Path> groovySources, List<Path> javaSources, List<Path> kotlinSources) {
        boolean joint = m.shape().mixedGroovy();
        List<Path> stampInputs = new ArrayList<>(groovySources);
        if (joint) stampInputs.addAll(javaSources);
        List<Path> classpath =
                groovyClasspath(m.classpaths().main(), m.layout(), m.shape().kotlin() && !kotlinSources.isEmpty());
        List<Path> javaRoots = joint
                ? PlannerKsp.kotlinJavaSourceRoots(true, m.shape().compact(), m.dir(), m.layout(), m.decls(), m.ksp())
                : null;
        return new Groovy(
                groovySources,
                stampInputs,
                classpath,
                javaRoots,
                joint ? m.layout().groovyStubsDir() : null);
    }

    /**
     * compile-groovy's classpath: the module's, plus Kotlin's output when the module compiled
     * Kotlin, so a Groovy class can reference a Kotlin one (Kotlin cannot see Groovy: it compiles
     * first, as under Gradle).
     */
    static List<Path> groovyClasspath(List<Path> classpath, BuildLayout layout, boolean kotlinOutput) {
        if (!kotlinOutput) return classpath;
        List<Path> out = new ArrayList<>(classpath);
        out.add(layout.kotlinClassesDir());
        return out;
    }

    /** The groovyc request of {@code g}: resolves the Groovy toolchain, creates nothing on disk. */
    @KeyOwner(step = TaskNames.COMPILE_GROOVY)
    static GroovycRequest groovyRequest(Module m, Groovy g) throws IOException {
        return PlannerLang.groovyRequest(
                m.project(),
                m.lock(),
                m.dir(),
                m.cas(),
                g.sources(),
                g.classpath(),
                m.layout().groovyClassesDir(),
                g.javaRoots(),
                g.stubsOut(),
                m.classpaths().processor(),
                m.release(),
                m.javaHome());
    }

    /**
     * Whether compile-groovy's freshness stamp vouches for {@code g}: its stamp inputs, the request's
     * classpath token lines {@code tokens} and the Groovy option digest (the Groovy version, the JVM
     * target, the JDK and the args).
     */
    @KeyOwner(step = TaskNames.COMPILE_GROOVY)
    static boolean groovyStampFresh(Module m, Groovy g, List<String> tokens) throws IOException {
        return FreshnessStamp.isFresh(
                m.layout().classesDir(),
                BuildStamps.GROOVY,
                g.stampInputs(),
                FreshnessStamp.ClasspathTokens.of(tokens),
                m.release(),
                groovyDigest(m));
    }

    /** compile-groovy's option digest: the Groovy version, the JVM target, the JDK and the args. */
    @KeyOwner(step = TaskNames.COMPILE_GROOVY)
    static String groovyDigest(Module m) {
        return PlannerLang.groovyStampDigest(m.project(), m.lock(), m.dir(), m.release(), m.javaHome());
    }

    /** compile-groovy's task id. */
    static String groovyTaskId(Module m) {
        return ActionKey.qualifiedTaskId(TaskNames.COMPILE_GROOVY, m.layout().classesDir());
    }
}
