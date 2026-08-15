// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Drives Kotlin compilation by forking the {@code jk-kotlin-compiler} plugin, which runs the Kotlin
 * Build Tools API in-process on jk's own runtime (the project JDK rides as -jdk-home).
 *
 * <p>The plugin is launched as {@code <javaHome>/bin/java -cp <workerClasspath>
 * cc.jumpkick.kotlin.compiler.KotlinCompiler @<spec>}. It streams JSONL back on stdout (each
 * line prefixed {@value #PROTOCOL_PREFIX}); we collect the diagnostics and the terminal result.
 * Replaces the former {@code <kotlin-home>/bin/kotlinc} subprocess.
 */
public final class KotlincDriver {

    /** Mirrors the plugin's {@code Jsonl.PREFIX}. */
    private static final String PROTOCOL_PREFIX = "##JKKC:";

    private static final String WORKER_MAIN = "cc.jumpkick.plugin.process.PluginMain";

    /**
     * Trainer {@code jvmTarget} when no real request is in hand (bare optimize / smoke). The
     * trainer runs on the PROJECT's kotlinc worker classpath, and Kotlin rejects unknown JVM
     * targets ("Unknown JVM target: 25" on pre-2.2.20 lines), so this must stay a target every
     * supported Kotlin line accepts — do NOT bump it alongside the host JDK (a too-new
     * value silently kills AOT training for projects pinning older Kotlin). Production
     * train-on-miss uses the triggering request's own jvmTarget instead, which that Kotlin
     * version already compiles with.
     */
    static final int TRAINER_FALLBACK_JVM_TARGET = 21;

    public KotlincResult compile(KotlincRequest request) {
        try {
            return run(request);
        } catch (IOException e) {
            // One retry when the worker pipe closes mid-compile flake).
            if (cc.jumpkick.engine.plugin.PluginProcess.isPipeClosed(e)) {
                try {
                    return run(request);
                } catch (IOException e2) {
                    throw new UncheckedIOException(
                            "kotlin compile failed after pipe-closed retry: " + e2.getMessage(), e2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("kotlin compile interrupted on retry", ie);
                }
            }
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("kotlin compile interrupted", e);
        }
    }

    private KotlincResult run(KotlincRequest request) throws IOException, InterruptedException {
        Path spec = writeSpec(request);
        try {
            // The plugin is jk's OWN process: it runs on jk's runtime (plugins are built at
            // jk's language level — class-file 69 — and must not be hostage to the project's
            // pinned JDK; requirements.md promises a 17+ project floor). The project JDK is an
            // INPUT: writeSpec passes it as kotlinc's -jdk-home so cross-compilation still
            // resolves the pinned JDK's platform classes.
            Path hostJavaHome = cc.jumpkick.jdk.JavaHomes.runningJavaHome();
            String classpath = request.workerClasspath().stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));
            List<String> rest = new ArrayList<>();
            // AOT cache for the plugin process (PluginAot): the Kotlin compiler IS this classpath, so
            // the cache tames its multi-second JIT warmup. Mapped when one exists for (host JDK,
            // GC, classpath); else a background trainer compiles a synthetic hello.kt so the NEXT
            // kotlin build maps it. JVM flags, so they precede -cp.
            rest.addAll(cc.jumpkick.engine.plugin.PluginAot.kotlincFlags(
                    hostJavaHome,
                    classpath,
                    (aotOutput, scratch) -> trainerCommand(request, classpath, hostJavaHome, aotOutput, scratch)));
            rest.addAll(List.of(
                    // Silence the JDK's native-access / Unsafe warnings the compiler triggers.
                    "--enable-native-access=ALL-UNNAMED", "-cp", classpath, WORKER_MAIN, "@" + spec.toAbsolutePath()));
            List<String> cmd = cc.jumpkick.engine.plugin.JvmOptions.javaCommand(
                    hostJavaHome.resolve("bin").resolve("java").toString(), 1, rest);

            List<String> diagnostics = new ArrayList<>();
            String[] status = {null};
            // Non-protocol lines (JDK/compiler chatter) are dropped on success, but a plugin
            // that DIES before speaking protocol (a broken classpath, a JVM crash) leaves its
            // whole story there — keep a bounded tail and surface it on failure, or the build
            // fails with an empty diagnostic and no way to see why.
            ArrayDeque<String> chatter = new ArrayDeque<>();
            int exit = new PluginClient(PROTOCOL_PREFIX)
                    .on(
                            PluginProtocol.DIAGNOSTIC,
                            json -> diagnostics.add(Jsonl.str(json, "sev") + ": " + Jsonl.str(json, "msg")))
                    .on(PluginProtocol.RESULT, json -> status[0] = Jsonl.str(json, "status"))
                    .passthrough(line -> {
                        if (chatter.size() >= 40) chatter.removeFirst();
                        chatter.addLast(line);
                    })
                    .run(cmd);
            boolean success = exit == 0 && "COMPILATION_SUCCESS".equals(status[0]);
            if (!success && diagnostics.isEmpty() && !chatter.isEmpty()) {
                diagnostics.add("kotlinc worker exited " + exit + " without diagnostics; last output:");
                diagnostics.addAll(chatter);
            }
            return new KotlincResult(success, String.join("\n", diagnostics));
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /**
     * The AOT trainer's command line: the same plugin spawn shape as {@link #run}, recording with
     * {@code -XX:AOTCacheOutput} while compiling a synthetic hello.kt against the triggering
     * request's own compile classpath (CAS entries are content-named, so there is no jar to hunt
     * for by name — but every real Kotlin compile already carries the version-matched
     * kotlin-stdlib the request pairs with {@code -no-stdlib}). Full startup + compile fidelity
     * exactly the warmup the cache exists to skip. The trainer inherits the request's own
     * {@code jvmTarget} — the one value the project's pinned Kotlin provably accepts.
     */
    static List<String> trainerCommand(
            KotlincRequest request, String classpath, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        return trainerCommandForOptimize(
                hostJavaHome, classpath, aotOutput, scratch, request.classpath(), request.jvmTarget());
    }

    /**
     * Public train-command entry (tests / manual smoke). Production train-on-miss uses {@link
     * #trainerCommand} with the real project compile classpath + jvmTarget via {@link #run}; this
     * bare path has no request, so it trains at {@link #TRAINER_FALLBACK_JVM_TARGET}.
     */
    public static List<String> trainerCommandForOptimize(
            Path hostJavaHome, String classpath, Path aotOutput, Path scratch) throws IOException {
        return trainerCommandForOptimize(
                hostJavaHome, classpath, aotOutput, scratch, List.of(), TRAINER_FALLBACK_JVM_TARGET);
    }

    private static List<String> trainerCommandForOptimize(
            Path hostJavaHome,
            String classpath,
            Path aotOutput,
            Path scratch,
            List<Path> compileClasspath,
            int jvmTarget)
            throws IOException {
        Path source = scratch.resolve("Hello.kt");
        Files.writeString(source, """
                package demo

                data class Point(val x: Int, val y: Int)

                sealed interface Shape
                data class Circle(val r: Double) : Shape
                data class Square(val s: Double) : Shape

                fun area(shape: Shape): Double = when (shape) {
                    is Circle -> Math.PI * shape.r * shape.r
                    is Square -> shape.s * shape.s
                }

                fun main() {
                    val points = (1..10).map { Point(it, it * 2) }
                    val shapes: List<Shape> = listOf(Circle(2.0), Square(3.0))
                    println(points.filter { it.x % 2 == 0 }.joinToString { "${it.x},${it.y}" })
                    println(shapes.sumOf { area(it) })
                }
                """);
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                .configString("jvmTarget", String.valueOf(jvmTarget))
                .layout(Map.of("classesDir", scratch.resolve("out")))
                .arg("-jdk-home")
                .arg(hostJavaHome.toAbsolutePath().toString())
                .source(source);
        // Real compiles pass version-matched kotlin-stdlib and use -no-stdlib; bare optimize
        // train has no project classpath, so leave the plugin's embedded stdlib resolution alone.
        if (compileClasspath != null && !compileClasspath.isEmpty()) {
            sw.arg("-no-stdlib");
            for (Path cp : compileClasspath) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        }
        Path spec = scratch.resolve("train.spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        // Match ForkedJavac / real PluginLoader forks so GC + classpath key the same as production
        // (dedicated train key must match real kotlinc worker keys).
        List<String> jvmFlags = new ArrayList<>();
        jvmFlags.add("-XX:AOTCacheOutput=" + aotOutput);
        jvmFlags.addAll(cc.jumpkick.engine.plugin.JvmOptions.batchFlags(1));
        jvmFlags.add("--enable-native-access=ALL-UNNAMED");
        Path javaExe = hostJavaHome.resolve("bin").resolve("java");
        return cc.jumpkick.engine.plugin.PluginLoader.command(
                javaExe, classpath, jvmFlags, List.of("@" + spec.toAbsolutePath()));
    }

    /** Render the request into the unified JSONL plugin spec. */
    private static Path writeSpec(KotlincRequest request) throws IOException {
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                .configString("jvmTarget", String.valueOf(request.jvmTarget()));
        Map<String, Path> layout = new LinkedHashMap<>();
        layout.put("classesDir", request.outputDir());
        if (request.workingDir() != null) layout.put("workdir", request.workingDir());
        if (request.snapshotDir() != null) layout.put("snapshotDir", request.snapshotDir());
        sw.layout(layout);
        if (request.moduleName() != null && !request.moduleName().isBlank()) {
            sw.configString("moduleName", request.moduleName());
        }
        // Cross-compile against the project's pinned JDK: the plugin HOST is jk's runtime, so without
        // -jdk-home kotlinc would resolve platform classes from jk's newer JDK and let a 17-pinned
        // project reference APIs it can't run against.
        sw.arg("-jdk-home").arg(request.javaHome().toAbsolutePath().toString());
        for (Path src : request.sources()) sw.source(src);
        for (Path cp : request.classpath()) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        for (String arg : request.extraArgs()) sw.arg(arg);
        for (KotlincRequest.Plugin plugin : request.plugins()) {
            sw.compilerPlugin(plugin.id(), plugin.jar(), plugin.options());
        }
        Path spec = Files.createTempFile("jk-kotlinc-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        return spec;
    }
}
