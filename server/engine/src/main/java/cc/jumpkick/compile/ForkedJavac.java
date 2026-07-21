// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Drives the {@code jk-java-compiler} plugin: runs javac in-process (under the project's JDK) with
 * annotation processors wrapped for provenance capture, and returns the diagnostics plus the
 * generated-file → originating-source mapping the incremental compiler needs for
 * annotation-processor incrementality.
 *
 * <p>Launched as {@code <javaHome>/bin/java -cp <workerJar>
 * cc.jumpkick.java.compiler.JavaIncrementalCompiler @<spec>}; the plugin streams {@value #PREFIX} JSONL
 * back on stdout. Mirrors {@link KotlincDriver}.
 */
public final class ForkedJavac {

    private static final String PREFIX = "##JKJC:";

    private ForkedJavac() {}

    /**
     * @param generated generated source file → the input source file(s) it originated from
     */
    public record Result(boolean success, List<CompileResult.Diagnostic> diagnostics, Map<Path, Set<Path>> generated) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Request(
            Path javaHome,
            Path workerJar,
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            Path classOutput,
            Path sourceOutput,
            int release,
            List<String> extraArgs) {}

    public static Result compile(Request request) {
        try {
            return run(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("java worker interrupted", e);
        }
    }

    private static Result run(Request req) throws IOException, InterruptedException {
        Path spec = writeSpec(req);
        try {
            List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
            Map<Path, Set<Path>> generated = new TreeMap<>();
            String[] status = {null};

            // Fork the java-compiler plugin on jk's OWN runtime — the same rule as every
            // plugin (requirements.md "plugin host"), and the same javac the non-AP path
            // already uses in-process (ToolProvider on the engine JDK): --release supplies
            // the project's target semantics. It's a thin, JDK-only plugin (the compile
            // classpath travels in the spec, not on the plugin's classpath), so its own
            // jar is the whole classpath.
            boolean win = HostPlatform.isWindows();
            Path hostJavaHome = cc.jumpkick.jdk.JavaHomes.runningJavaHome();
            Path javaExe = hostJavaHome.resolve("bin").resolve(win ? "java.exe" : "java");
            String workerCp = req.workerJar().toString();
            // AOT for this *java* process (ToolProvider host) — not bare `javac` launcher AOT.
            List<String> jvmFlags = new ArrayList<>(cc.jumpkick.engine.plugin.PluginAot.javaCompilerFlags(
                    hostJavaHome,
                    workerCp,
                    (aotOutput, scratch) -> trainerCommand(req, hostJavaHome, aotOutput, scratch)));
            jvmFlags.addAll(cc.jumpkick.engine.plugin.JvmOptions.batchFlags(1));
            List<String> command = cc.jumpkick.engine.plugin.PluginLoader.command(
                    javaExe, workerCp, jvmFlags, List.of("@" + spec.toAbsolutePath()));
            int exit = new cc.jumpkick.engine.plugin.PluginClient(PREFIX)
                    .on(PluginProtocol.DIAGNOSTIC, json -> {
                        String file = Jsonl.str(json, "file");
                        diagnostics.add(new CompileResult.Diagnostic(
                                CompileResult.Severity.fromName(Jsonl.str(json, "sev")),
                                file == null ? null : Path.of(file),
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
                    .on(PluginProtocol.RESULT, json -> status[0] = Jsonl.str(json, "status"))
                    .run(command);
            boolean success = exit == 0 && "OK".equals(status[0]);
            return new Result(success, diagnostics, generated);
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static Path writeSpec(Request req) throws IOException {
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-java-compiler")
                .configInt("release", req.release())
                .layout(Map.of("classesDir", req.classOutput(), "sourceOutput", req.sourceOutput()));
        for (Path s : req.sources()) sw.source(s);
        for (Path c : req.classpath()) sw.cp(c, PluginProtocol.ROLE_COMPILE);
        for (Path p : req.processorPath()) sw.cp(p, PluginProtocol.ROLE_PROCESSOR);
        for (String a : req.extraArgs()) sw.arg(a);
        Path spec = Files.createTempFile("jk-javac-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        return spec;
    }

    /**
     * Background AOT trainer: same {@code java -cp worker PluginMain @spec} shape as a real
     * compile, recording with {@code -XX:AOTCacheOutput} while compiling a synthetic Hello.java.
     */
    private static List<String> trainerCommand(Request req, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        Path src = scratch.resolve("Hello.java");
        Files.writeString(
                src,
                """
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
                .configInt("release", req.release() > 0 ? req.release() : 25)
                .layout(Map.of("classesDir", classes, "sourceOutput", scratch.resolve("gen")))
                .source(src);
        Path trainSpec = scratch.resolve("train.spec");
        Files.write(trainSpec, sw.lines(), StandardCharsets.UTF_8);
        List<String> jvmFlags = new ArrayList<>();
        jvmFlags.add("-XX:AOTCacheOutput=" + aotOutput);
        jvmFlags.addAll(cc.jumpkick.engine.plugin.JvmOptions.batchFlags(1));
        boolean win = HostPlatform.isWindows();
        Path javaExe = hostJavaHome.resolve("bin").resolve(win ? "java.exe" : "java");
        return cc.jumpkick.engine.plugin.PluginLoader.command(
                javaExe, req.workerJar().toString(), jvmFlags, List.of("@" + trainSpec.toAbsolutePath()));
    }
}
