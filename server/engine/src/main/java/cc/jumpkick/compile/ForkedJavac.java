// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.JdkCompilerAccess;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Drives the {@code jk-java-compiler} plugin: the request a compile hands the worker, the spec file
 * it is written as, and the worker JVM's command line. The compile itself runs on the resident
 * worker of its pool ({@link JavaCompilerHost}), many modules at once on threads of one JVM.
 *
 * <p>Launched as {@code java -cp <workerJar+POM>} {@code PluginMain --pull}, answering on its
 * command channel; the AOT trainer runs it as {@code @<spec>}, answering {@value #PREFIX} JSONL on
 * stdout.
 */
public final class ForkedJavac {

    static final String PREFIX = "##JKJC:";

    private ForkedJavac() {}

    /**
     * @param generated generated source file → the input source file(s) it originated from
     * @param compiledSources sources Zinc (or javac) actually compiled this invocation
     * @param waitMillis time the request sat in the shared worker's queue before it was dispatched
     */
    public record Result(
            boolean success,
            List<CompileResult.Diagnostic> diagnostics,
            Map<Path, Set<Path>> generated,
            List<Path> compiledSources,
            long waitMillis) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
            compiledSources = compiledSources == null ? List.of() : List.copyOf(compiledSources);
            generated = generated == null ? Map.of() : Map.copyOf(generated); // copy like the other two
        }

        public Result(boolean success, List<CompileResult.Diagnostic> diagnostics, Map<Path, Set<Path>> generated) {
            this(success, diagnostics, generated, List.of(), 0L);
        }
    }

    /** One source Zinc would compile, with the analysis reason. */
    public record Invalidation(Path source, String why) {}

    /** Read-only Zinc invalidation forecast ({@code PLAN}). */
    public record Plan(boolean full, String reason, List<Invalidation> invalidations) {
        public Plan {
            reason = reason == null ? "" : reason;
            invalidations = invalidations == null ? List.of() : List.copyOf(invalidations);
        }

        public List<Path> sources() {
            return invalidations.stream().map(Invalidation::source).toList();
        }
    }

    /**
     * The JDK the compiler worker runs on: the request's {@link Request#javaHome()}, which the
     * engine set to the compiler's JDK ({@link CompileRequest#compilerHome()}, the {@link
     * CompilerJdk} rule), else jk's own runtime.
     */
    static Path workerJavaHome(Request req) {
        Path home = req.javaHome();
        return home != null ? home : JavaHomes.runningJavaHome();
    }

    public record Request(
            @Nullable Path javaHome,
            Path workerJar,
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            Path classOutput,
            Path sourceOutput,
            int release,
            List<String> extraArgs,
            @Nullable Path workdir,
            @Nullable String scalaVersion,
            List<Path> compilerClasspath,
            @Nullable Path scalaLibraryJar,
            @Nullable Path scalaCompilerJar,
            @Nullable Path scalaBridgeJar,
            /** What the worker JVM starts with; the pool keys lanes on it. */
            WorkerEnv env,
            /**
             * Classpath entries other jk compiles produced, each with its producer's Zinc analysis
             * file — a hint for Zinc's per-entry lookup, never part of the compile's key.
             */
            Map<Path, Path> classpathAnalyses,
            /** What a failure names: the module and step this compile is for; empty when unknown. */
            String label) {

        public Request {
            classpathAnalyses = classpathAnalyses == null ? Map.of() : Map.copyOf(classpathAnalyses);
            label = label == null ? "" : label;
        }

        /**
         * javac's launcher spelling of a flag for the JVM running the compiler rather than for
         * javac: {@code -J<flag>}. javac itself rejects the argument as an invalid flag, so the
         * engine splits it the way the launcher does.
         */
        public static final String JVM_FLAG_PREFIX = JdkCompilerAccess.LAUNCHER_PREFIX;

        /** The flags the worker JVM starts with: every {@code -J} argument, prefix stripped, in order. */
        public List<String> jvmArgs() {
            List<String> out = new ArrayList<>();
            for (String arg : extraArgs) {
                if (arg.startsWith(JVM_FLAG_PREFIX) && arg.length() > JVM_FLAG_PREFIX.length()) {
                    out.add(arg.substring(JVM_FLAG_PREFIX.length()));
                }
            }
            return out;
        }

        /** What javac itself receives: {@link #extraArgs} without the {@code -J} arguments. */
        public List<String> javacArgs() {
            List<String> out = new ArrayList<>();
            for (String arg : extraArgs) {
                if (!(arg.startsWith(JVM_FLAG_PREFIX) && arg.length() > JVM_FLAG_PREFIX.length())) out.add(arg);
            }
            return out;
        }

        /** The same request for a worker started under {@code env}. */
        public Request withEnv(WorkerEnv env) {
            return new Request(
                    javaHome,
                    workerJar,
                    sources,
                    classpath,
                    processorPath,
                    classOutput,
                    sourceOutput,
                    release,
                    extraArgs,
                    workdir,
                    scalaVersion,
                    compilerClasspath,
                    scalaLibraryJar,
                    scalaCompilerJar,
                    scalaBridgeJar,
                    env,
                    classpathAnalyses,
                    label);
        }

        /** The same request handing the worker {@code classpathAnalyses}. */
        public Request withClasspathAnalyses(Map<Path, Path> classpathAnalyses) {
            return new Request(
                    javaHome,
                    workerJar,
                    sources,
                    classpath,
                    processorPath,
                    classOutput,
                    sourceOutput,
                    release,
                    extraArgs,
                    workdir,
                    scalaVersion,
                    compilerClasspath,
                    scalaLibraryJar,
                    scalaCompilerJar,
                    scalaBridgeJar,
                    env,
                    classpathAnalyses,
                    label);
        }

        /** The same request, its failures naming {@code label}. */
        public Request withLabel(String label) {
            return new Request(
                    javaHome,
                    workerJar,
                    sources,
                    classpath,
                    processorPath,
                    classOutput,
                    sourceOutput,
                    release,
                    extraArgs,
                    workdir,
                    scalaVersion,
                    compilerClasspath,
                    scalaLibraryJar,
                    scalaCompilerJar,
                    scalaBridgeJar,
                    env,
                    classpathAnalyses,
                    label);
        }

        public Request(
                @Nullable Path javaHome,
                Path workerJar,
                List<Path> sources,
                List<Path> classpath,
                List<Path> processorPath,
                Path classOutput,
                Path sourceOutput,
                int release,
                List<String> extraArgs,
                @Nullable Path workdir,
                @Nullable String scalaVersion,
                List<Path> compilerClasspath,
                @Nullable Path scalaLibraryJar,
                @Nullable Path scalaCompilerJar,
                @Nullable Path scalaBridgeJar,
                WorkerEnv env) {
            this(
                    javaHome,
                    workerJar,
                    sources,
                    classpath,
                    processorPath,
                    classOutput,
                    sourceOutput,
                    release,
                    extraArgs,
                    workdir,
                    scalaVersion,
                    compilerClasspath,
                    scalaLibraryJar,
                    scalaCompilerJar,
                    scalaBridgeJar,
                    env,
                    Map.of(),
                    "");
        }

        public Request(
                @Nullable Path javaHome,
                Path workerJar,
                List<Path> sources,
                List<Path> classpath,
                List<Path> processorPath,
                Path classOutput,
                Path sourceOutput,
                int release,
                List<String> extraArgs,
                @Nullable Path workdir) {
            this(
                    javaHome,
                    workerJar,
                    sources,
                    classpath,
                    processorPath,
                    classOutput,
                    sourceOutput,
                    release,
                    extraArgs,
                    workdir,
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    WorkerEnv.strict());
        }

        public Request(
                @Nullable Path javaHome,
                Path workerJar,
                List<Path> sources,
                List<Path> classpath,
                List<Path> processorPath,
                Path classOutput,
                Path sourceOutput,
                int release,
                List<String> extraArgs) {
            this(
                    javaHome,
                    workerJar,
                    sources,
                    classpath,
                    processorPath,
                    classOutput,
                    sourceOutput,
                    release,
                    extraArgs,
                    null);
        }
    }

    public static Result compile(Request request) {
        return JavaCompilerHost.compile(request);
    }

    public static Plan plan(Request request) {
        return JavaCompilerHost.plan(request);
    }

    /** As {@link #plan} on a warm worker already running; null when the plan would start one. */
    public static @Nullable Plan planIfWarm(Request request) {
        return JavaCompilerHost.planIfWarm(request);
    }

    /** Renders {@code req} and seals the network policy — its forks bypass {@code PluginLaunch}. */
    public static Path writeSpec(Request req) throws IOException {
        Classpaths.requireArchivesOnDisk(req.classpath(), "the javac classpath");
        Map<String, Path> layout = new LinkedHashMap<>();
        layout.put("classesDir", req.classOutput());
        if (req.sourceOutput() != null) layout.put("sourceOutput", req.sourceOutput());
        if (req.workdir() != null) layout.put("workdir", req.workdir());
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-java-compiler")
                .configInt("release", req.release())
                .layout(layout);
        // A diagnostic the shell that ran jk asked for reaches the worker through the spec: the
        // worker's own environment is the engine's, which that shell never sees.
        String phasesLog = BuildEnv.ambient().apply(PluginProtocol.COMPILE_PHASES_ENV);
        if (phasesLog != null && !phasesLog.isBlank()) {
            sw.configString(PluginProtocol.CONFIG_PHASES_LOG, phasesLog.trim());
        }
        if (req.scalaVersion() != null && !req.scalaVersion().isBlank()) {
            sw.configString("scalaVersion", req.scalaVersion());
        }
        for (Path s : req.sources()) sw.source(s);
        for (Path c : req.classpath()) sw.cp(c, PluginProtocol.ROLE_COMPILE);
        for (Path p : req.processorPath()) sw.cp(p, PluginProtocol.ROLE_PROCESSOR);
        if (req.compilerClasspath() != null) {
            for (Path p : req.compilerClasspath()) sw.cp(p, PluginProtocol.ROLE_COMPILER);
        }
        if (req.scalaLibraryJar() != null) sw.extra("scala-library", req.scalaLibraryJar());
        if (req.scalaCompilerJar() != null) sw.extra("scala-compiler", req.scalaCompilerJar());
        if (req.scalaBridgeJar() != null) sw.extra("scala-bridge", req.scalaBridgeJar());
        for (Map.Entry<Path, Path> e : req.classpathAnalyses().entrySet()) sw.cpAnalysis(e.getKey(), e.getValue());
        for (String a : req.javacArgs()) sw.arg(a);
        Path spec = Files.createTempFile("jk-javac-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        return spec;
    }

    /**
     * The module's {@code -J} flags jk does not already pass the worker — the ones that make its
     * JVM differ from every other module's, and so the ones that key its AOT cache and start the
     * trainer that records it.
     */
    static List<String> novelJvmArgs(Request req) {
        List<String> own = new ArrayList<>();
        List<String> batch = JvmOptions.batchFlags(1);
        for (String flag : req.jvmArgs()) {
            if (JdkCompilerAccess.JVM_FLAGS.contains(flag) || batch.contains(flag) || own.contains(flag)) continue;
            own.add(flag);
        }
        return own;
    }

    /**
     * glibc keeps what javac's native allocations freed; trimmed every minute the idle javac worker
     * is ~10 MiB smaller, with no measurable cost to a compile.
     */
    static final String TRIM_NATIVE_HEAP = "-XX:TrimNativeHeapInterval=60000";

    /** The trainer's workload, in the startup cache's key: {@link JavacTrainingSources}, rev 2. */
    static final String TRAINING = "train:sources-2";

    /**
     * What a worker's startup cache is keyed by beyond its classpath: the module's own JVM flags,
     * {@link #TRAINING}, what the cache was recorded on — a new fixture is a new key, so a cache
     * recorded on the old one is never mapped again — and the worker's own flags, whose collector
     * and heap the cache was recorded under.
     */
    static List<String> aotKeyFlags(Request req) {
        return aotKeyFlags(req, JvmOptions.currentTuning());
    }

    /** As {@link #aotKeyFlags(Request)} under {@code tuning}, the tuning the worker starts with. */
    static List<String> aotKeyFlags(Request req, PluginTuning tuning) {
        List<String> key = new ArrayList<>(novelJvmArgs(req));
        key.add(TRAINING);
        key.addAll(JvmOptions.compilerWorkerFlags(tuning, null));
        return key;
    }

    /**
     * Background AOT trainer: same {@code java -cp worker PluginMain @spec} shape as a real
     * compile, started with the module's own JVM flags, recording with {@code -XX:AOTCacheOutput}
     * while compiling {@link JavacTrainingSources}.
     */
    static List<String> trainerCommand(Request req, String workerCp, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        return trainerCommand(req, JvmOptions.currentTuning(), workerCp, hostJavaHome, aotOutput, scratch);
    }

    /** As above, the trainer started under {@code tuning}, the tuning its worker starts with. */
    static List<String> trainerCommand(
            Request req, PluginTuning tuning, String workerCp, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        return trainerCommand(
                hostJavaHome, workerCp, aotOutput, scratch, trainingRelease(req.release()), novelJvmArgs(req), tuning);
    }

    /**
     * The {@code --release} the trainer compiles at: the module's, but never below {@link
     * JavacTrainingSources#MIN_RELEASE}, which the training module needs. The cache is keyed by
     * the worker, not the release, so a module on 17 maps a cache recorded at 21; recorded at 17
     * the training compile fails and no module gets a cache.
     */
    static int trainingRelease(int moduleRelease) {
        return Math.max(JavacTrainingSources.MIN_RELEASE, moduleRelease);
    }

    /** The trainer argv for a flag set the caller names; the compile path derives it from its request. */
    public static List<String> trainerCommand(
            Path hostJavaHome, String workerCp, Path aotOutput, Path scratch, int release, List<String> module)
            throws IOException {
        return trainerCommand(hostJavaHome, workerCp, aotOutput, scratch, release, module, JvmOptions.currentTuning());
    }

    private static List<String> trainerCommand(
            Path hostJavaHome,
            String workerCp,
            Path aotOutput,
            Path scratch,
            int release,
            List<String> module,
            PluginTuning tuning)
            throws IOException {
        Path classes = scratch.resolve("out");
        Files.createDirectories(classes);
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-java-compiler")
                .configInt("release", release)
                .layout(Map.of(
                        "classesDir",
                        classes,
                        "sourceOutput",
                        scratch.resolve("gen"),
                        "workdir",
                        scratch.resolve("zinc-work")));
        // The worker's own jars stand in for a dependency classpath: javac opens and indexes them.
        for (Path jar : Classpaths.split(workerCp)) sw.cp(jar, "compile");
        for (Path src : JavacTrainingSources.write(scratch.resolve("src"))) sw.source(src);
        Path trainSpec = scratch.resolve("train.spec");
        Files.write(trainSpec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(trainSpec);
        List<String> jvmFlags = workerJvmFlags(List.of("-XX:AOTCacheOutput=" + aotOutput), module, tuning, null);
        // Same classpath as the real fork: the classpath is part of the AOT key, and a
        // thin worker jar alone would CNFE on PluginMain, silently never training.
        return PluginLoader.command(hostJavaHome, workerCp, jvmFlags, List.of("@" + trainSpec.toAbsolutePath()));
    }

    /**
     * The worker JVM's flags, for the resident worker and the AOT trainer alike: {@code aot} (the
     * cache to use or record), the {@code jdk.compiler} access javac plugins need, the {@link
     * JvmOptions#compilerWorkerFlags resident worker's} heap, collector and limits, then the
     * module's own {@link Request#jvmArgs() -J flags} last, so what the module wrote wins over jk's
     * tuning; one jk already passes is not passed twice. One body: a trainer that ran with a
     * different module graph or collector than the worker would record a cache it cannot use.
     */
    static List<String> workerJvmFlags(List<String> aot, List<String> module) {
        return workerJvmFlags(aot, module, JvmOptions.currentTuning(), null);
    }

    /**
     * As {@link #workerJvmFlags(List, List)} under {@code tuning}, with {@code xmxBytes} as the heap
     * ceiling when it is not null ({@link CompilerBudget}).
     */
    static List<String> workerJvmFlags(
            List<String> aot, List<String> module, PluginTuning tuning, @Nullable Long xmxBytes) {
        List<String> flags = new ArrayList<>(aot);
        flags.addAll(JdkCompilerAccess.JVM_FLAGS);
        flags.addAll(JdkCompilerAccess.LOCALE_FLAGS);
        flags.addAll(JvmOptions.compilerWorkerFlags(tuning, xmxBytes));
        // A worker can wait minutes for its next build; give back what malloc holds.
        flags.add(TRIM_NATIVE_HEAP);
        for (String flag : module) {
            if (!flags.contains(flag)) flags.add(flag);
        }
        return flags;
    }

    /**
     * Thin worker + Zinc POM closure. Mixed Scala loads the compiler through a child classloader
     * inside the worker; it is not on this JVM classpath.
     */
    static String workerClasspath(Request req) {
        return WorkerLaunchClasspath.resolve(req.workerJar());
    }
}
