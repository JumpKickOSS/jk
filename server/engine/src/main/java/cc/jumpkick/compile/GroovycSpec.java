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
 * The Groovy half of a worker compile: what {@link WorkerCompileDriver} tells {@code
 * jk-groovy-compiler} that it does not tell {@code jk-kotlin-compiler}. groovyc takes no project
 * JDK — there is no {@code -jdk-home} here and no {@code javaHome} on {@link GroovycRequest} —
 * and it has a joint-compilation pass Kotlin does not: {@code stubsOut} is its scratch, the
 * {@code SOURCE} lines already carry the roots' Java neighborhood ({@link GroovycInputs}), and the
 * processor path rides along so the swept javac pass runs the same annotation processors jk's real
 * javac lane does.
 */
final class GroovycSpec {

    private GroovycSpec() {}

    /** Render the request into the unified JSONL plugin spec. */
    static Path write(GroovycRequest request) throws IOException {
        Classpaths.requireArchivesOnDisk(request.classpath(), "the groovyc classpath");
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                .configString("jvmTarget", String.valueOf(request.jvmTarget()));
        Map<String, Path> layout = new LinkedHashMap<>();
        layout.put("classesDir", request.outputDir());
        if (request.workDir() != null) layout.put("workdir", request.workDir());
        sw.layout(layout);
        if (request.stubsOut() != null) sw.extra("stubsOut", request.stubsOut());
        // SOURCE carries the whole joint compile set (explicit sources plus the roots' .java
        // neighborhood) — exactly what ActionKey.forGroovyc hashed. The worker compiles
        // spec.sources() verbatim and never walks a tree.
        for (Path src : GroovycInputs.compileSet(request)) sw.source(src);
        for (Path cp : request.classpath()) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        for (Path pp : request.processorPath()) sw.cp(pp, PluginProtocol.ROLE_PROCESSOR);
        for (String arg : request.extraArgs()) sw.arg(arg);
        Path spec = Files.createTempFile("jk-groovyc-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        return spec;
    }

    /**
     * The AOT trainer's command line: the same plugin spawn shape as a real compile, recording with
     * {@code -XX:AOTCacheOutput} while it joint-compiles a synthetic Groovy class and the Java class
     * it calls, against the triggering request's own compile classpath and {@code jvmTarget}.
     */
    static List<String> trainerCommand(
            GroovycRequest request, String classpath, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        Path groovy = scratch.resolve("src/demo/Hello.groovy");
        Path java = scratch.resolve("src/demo/Util.java");
        Files.createDirectories(groovy.getParent());
        Files.writeString(groovy, """
                package demo

                import groovy.transform.CompileStatic

                @CompileStatic
                class Hello {
                    record Point(int x, int y) {}

                    String greet(List<String> names) {
                        names.findAll { it }.collect { Util.shout(it) }.join(', ')
                    }

                    static void main(String[] args) {
                        def points = (1..10).collect { new Point(it, it * 2) }
                        println new Hello().greet(['a', 'b'])
                        println points.findAll { it.x() % 2 == 0 }.sum { it.y() }
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(java, """
                package demo;

                public final class Util {
                    private Util() {}

                    public static String shout(String s) {
                        return s.toUpperCase();
                    }
                }
                """, StandardCharsets.UTF_8);
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                .configString("jvmTarget", String.valueOf(request.jvmTarget()))
                .layout(Map.of("classesDir", scratch.resolve("out"), "workdir", scratch.resolve("work")))
                .source(groovy)
                .source(java);
        for (Path cp : request.classpath()) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        Path spec = scratch.resolve("train.spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        PluginLoader.sealNetworkPolicy(spec);
        List<String> jvmFlags = new ArrayList<>();
        jvmFlags.add("-XX:AOTCacheOutput=" + aotOutput);
        jvmFlags.addAll(JvmOptions.batchFlags(1));
        jvmFlags.add("--enable-native-access=ALL-UNNAMED");
        return PluginLoader.command(hostJavaHome, classpath, jvmFlags, List.of("@" + spec.toAbsolutePath()));
    }
}
