// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.host.Classpaths;
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

/**
 * The Kotlin half of a worker compile: what {@link WorkerCompileDriver} tells {@code
 * jk-kotlin-compiler} that it does not tell {@code jk-groovy-compiler}. Two things are genuinely
 * Kotlin-only and are why this is not one spec writer with a flag — kotlinc takes the project JDK
 * as {@code -jdk-home} (groovyc takes no project JDK at all, and {@link GroovycRequest} correctly
 * has no {@code javaHome}), and the Kotlin worker is AOT-cached, so it needs a trainer command.
 */
final class KotlincSpec {

    private KotlincSpec() {}

    /**
     * Render the request into the unified JSONL plugin spec. Package-private so a test can read
     * back what the compiler is actually told (see {@link #trainerCommand}, opened for the same
     * reason).
     */
    static Path write(KotlincRequest request) throws IOException {
        Classpaths.requireArchivesOnDisk(request.classpath(), "the kotlinc classpath");
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
        // project reference APIs it can't run against. Because it reshapes the output it is also an
        // action-key input — ActionKey.forKotlinc hashes this same JDK, normalized the same way.
        sw.arg("-jdk-home").arg(request.javaHome().toAbsolutePath().normalize().toString());
        for (Path src : request.sources()) sw.source(src);
        for (Path cp : request.classpath()) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        for (String arg : request.extraArgs()) sw.arg(arg);
        for (KotlincRequest.Plugin plugin : request.plugins()) {
            sw.compilerPlugin(plugin.id(), plugin.jar(), plugin.options());
        }
        Path spec = Files.createTempFile("jk-kotlinc-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        return spec;
    }

    /**
     * The AOT trainer's command line: the same plugin spawn shape as a real compile, recording with
     * {@code -XX:AOTCacheOutput} while compiling a synthetic hello.kt against the triggering
     * request's own compile classpath (CAS entries are content-named, so there is no jar to hunt
     * for by name — but every real Kotlin compile already carries the version-matched
     * kotlin-stdlib the request pairs with {@code -no-stdlib}). Full startup + compile fidelity is
     * exactly the warmup the cache exists to skip. The trainer inherits the request's own
     * {@code jvmTarget} — the one value the project's pinned Kotlin provably accepts.
     * ({@code WorkerAotBootstrap} deliberately skips kotlinc, so train-on-miss is the only
     * trainer path and this is the single entry point.)
     */
    static List<String> trainerCommand(
            KotlincRequest request, String classpath, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        List<Path> compileClasspath = request.classpath();
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
                .configString("jvmTarget", String.valueOf(request.jvmTarget()))
                .layout(Map.of("classesDir", scratch.resolve("out")))
                .arg("-jdk-home")
                .arg(hostJavaHome.toAbsolutePath().toString())
                .source(source);
        // Real compiles pass version-matched kotlin-stdlib and use -no-stdlib; an (unlikely)
        // empty classpath leaves the plugin's embedded stdlib resolution alone.
        if (!compileClasspath.isEmpty()) {
            sw.arg("-no-stdlib");
            for (Path cp : compileClasspath) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        }
        Path spec = scratch.resolve("train.spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        // Match ForkedJavac / real PluginLoader forks so GC + classpath key the same as production
        // (dedicated train key must match real kotlinc worker keys).
        List<String> jvmFlags = new ArrayList<>();
        jvmFlags.add("-XX:AOTCacheOutput=" + aotOutput);
        jvmFlags.addAll(JvmOptions.batchFlags(1));
        jvmFlags.add("--enable-native-access=ALL-UNNAMED");
        return PluginLoader.command(hostJavaHome, classpath, jvmFlags, List.of("@" + spec.toAbsolutePath()));
    }
}
