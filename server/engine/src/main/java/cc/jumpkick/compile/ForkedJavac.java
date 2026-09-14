// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Drives the {@code jk-java-compiler} plugin. Inside a job request the same pull-mode JVM is
 * reused for every module ({@link JavaCompilerHost}); otherwise this is a one-shot {@code @spec}
 * fork.
 *
 * <p>Launched as {@code java -cp <workerJar+POM>} {@code PluginMain --pull} or {@code @<spec>};
 * streams {@value #PREFIX} JSONL on stdout.
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
     * The JDK the compiler worker runs on: jk's own runtime, or the project's JDK when the
     * project's {@code java} level is above what the runtime's javac can emit. A javac cannot
     * target a release newer than itself, so a {@code java = 26} project on an engine running
     * JDK 25 compiles on the JDK its manifest resolved.
     */
    static Path workerJavaHome(Request req) {
        Path host = JavaHomes.runningJavaHome();
        Path project = req.javaHome();
        if (project == null || req.release() <= 0) return host;
        int hostFeature = JvmOptions.hostFeature(host);
        if (req.release() <= hostFeature) return host;
        return JvmOptions.hostFeature(project) >= req.release() ? project : host;
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
            Map<Path, Path> classpathAnalyses) {

        public Request {
            classpathAnalyses = classpathAnalyses == null ? Map.of() : Map.copyOf(classpathAnalyses);
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
                    classpathAnalyses);
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
                    classpathAnalyses);
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
                    Map.of());
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

    static Result oneshot(Request req) {
        try {
            return run(req);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("java worker interrupted", e);
        }
    }

    static Plan oneshotPlan(Request req) {
        try (JavaCompilerHost.Scope ignored = JavaCompilerHost.open()) {
            return JavaCompilerHost.plan(req);
        }
    }

    private static Result run(Request req) throws IOException, InterruptedException {
        Path spec = writeSpec(req);
        try {
            List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
            Map<Path, Set<Path>> generated = new TreeMap<>();
            List<Path> compiledSources = new ArrayList<>();
            @Nullable String[] status = {null};

            // Fork the java-compiler plugin on jk's own runtime, like every plugin, with
            // --release carrying the project's target semantics — unless the project's level is
            // above what this runtime's javac can emit, in which case the worker runs on the
            // project's JDK (workerJavaHome). It's a thin, JDK-only plugin (the compile classpath
            // travels in the spec, not on the plugin's classpath), so its own jar is the whole
            // classpath.
            Path hostJavaHome = workerJavaHome(req);
            Path javaExe = JdkFingerprint.java(hostJavaHome);
            // Thin worker + Maven runtime closure from its POM.
            String workerCp = workerClasspath(req);
            // AOT for this *java* process (ToolProvider host) — not bare `javac` launcher AOT.
            List<String> jvmFlags = workerJvmFlags(PluginAot.javaCompilerFlags(
                    hostJavaHome,
                    workerCp,
                    (aotOutput, scratch) -> trainerCommand(req, workerCp, hostJavaHome, aotOutput, scratch)));
            List<String> command =
                    PluginLoader.command(javaExe, workerCp, jvmFlags, List.of("@" + spec.toAbsolutePath()));
            int exit = new PluginClient(PREFIX)
                    .on(PluginProtocol.DIAGNOSTIC, json -> {
                        // Same contract as the pull-mode host: the locus must live in the
                        // message text (WorkerDiagnostics), not only in the record fields.
                        diagnostics.add(WorkerDiagnostics.located(
                                Jsonl.str(json, "sev"),
                                Jsonl.str(json, "file"),
                                Jsonl.longValue(json, "line", 0),
                                Jsonl.longValue(json, "col", 0),
                                Jsonl.str(json, "msg")));
                    })
                    .on(PluginProtocol.PROVENANCE, json -> {
                        String genStr = Jsonl.str(json, "gen");
                        if (genStr == null) return;
                        Path gen = Path.of(genStr);
                        Set<Path> origins = new TreeSet<>();
                        for (String s : Jsonl.strArray(json, "src")) origins.add(Path.of(s));
                        generated.put(gen, origins);
                    })
                    .on(PluginProtocol.RESULT, json -> {
                        status[0] = Jsonl.str(json, "status");
                        for (String s : Jsonl.strArray(json, "compiled")) {
                            compiledSources.add(Path.of(s));
                        }
                    })
                    .run(command, req.env());
            boolean success = exit == 0 && "OK".equals(status[0]);
            return new Result(success, diagnostics, generated, compiledSources, 0L);
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /** Renders {@code req} and seals the network policy — its forks bypass {@code PluginLaunch}. */
    public static Path writeSpec(Request req) throws IOException {
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
        for (String a : req.extraArgs()) sw.arg(a);
        Path spec = Files.createTempFile("jk-javac-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        return spec;
    }

    /**
     * Background AOT trainer: same {@code java -cp worker PluginMain @spec} shape as a real
     * compile, recording with {@code -XX:AOTCacheOutput} while compiling a synthetic Hello.java.
     */
    static List<String> trainerCommand(Request req, String workerCp, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        return trainerCommandForOptimize(
                hostJavaHome, workerCp, aotOutput, scratch, req.release() > 0 ? req.release() : 25);
    }

    /** Public entry for install {@code jk optimize} / {@link WorkerAotBootstrap}. */
    public static List<String> trainerCommandForOptimize(
            Path hostJavaHome, String workerCp, Path aotOutput, Path scratch) throws IOException {
        return trainerCommandForOptimize(hostJavaHome, workerCp, aotOutput, scratch, 25);
    }

    private static List<String> trainerCommandForOptimize(
            Path hostJavaHome, String workerCp, Path aotOutput, Path scratch, int release) throws IOException {
        Path src = scratch.resolve("Hello.java");
        Files.writeString(src, """
                package demo;
                public class Hello {
                  public static void main(String[] args) {
                    System.out.println("jk-java-compiler aot train");
                  }
                }
                """);
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
                        scratch.resolve("zinc-work")))
                .source(src);
        Path trainSpec = scratch.resolve("train.spec");
        Files.write(trainSpec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(trainSpec);
        List<String> jvmFlags = workerJvmFlags(List.of("-XX:AOTCacheOutput=" + aotOutput));
        Path javaExe = JdkFingerprint.java(hostJavaHome);
        // Same classpath as the real fork: the classpath is part of the AOT key, and a
        // thin worker jar alone would CNFE on PluginMain, silently never training.
        return PluginLoader.command(javaExe, workerCp, jvmFlags, List.of("@" + trainSpec.toAbsolutePath()));
    }

    /**
     * The worker JVM's flags, for the compile fork, the pull-mode host and the AOT trainer alike:
     * {@code aot} (the cache to use or record), the {@code jdk.compiler} access javac plugins need,
     * and the batch-sized heap. One body: a trainer that ran with a different module graph than
     * the compile would record a cache the compile cannot use.
     */
    static List<String> workerJvmFlags(List<String> aot) {
        List<String> flags = new ArrayList<>(aot);
        flags.addAll(JdkCompilerAccess.JVM_FLAGS);
        flags.addAll(JvmOptions.batchFlags(1));
        return flags;
    }

    /**
     * Thin worker + Zinc POM closure. Mixed Scala loads the compiler through a child classloader
     * inside the worker; it is not on this JVM classpath so the AOT key stays Zinc-only.
     */
    static String workerClasspath(Request req) {
        return WorkerLaunchClasspath.resolve(req.workerJar());
    }
}
