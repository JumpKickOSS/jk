// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.PluginClient;
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
import java.util.stream.Collectors;

/**
 * Drives Groovy compilation by forking the {@code jk-groovy-compiler} plugin, which runs the
 * Groovy 5 compiler in-process on jk's own runtime.
 *
 * <p>The plugin is launched as {@code <hostJavaHome>/bin/java -cp <workerClasspath>
 * cc.jumpkick.plugin.process.PluginMain @<spec>}. It streams JSONL back on stdout (each line
 * prefixed {@value #PROTOCOL_PREFIX}); we collect the diagnostics and the terminal result.
 */
public final class GroovycDriver {

    /** Mirrors the plugin's manifest prefix. */
    private static final String PROTOCOL_PREFIX = "##JKGC:";

    private static final String WORKER_MAIN = "cc.jumpkick.plugin.process.PluginMain";

    public GroovycResult compile(GroovycRequest request) {
        try {
            return run(request);
        } catch (IOException e) {
            // One retry when the worker pipe closes mid-compile (ticket-1053 flake).
            if (cc.jumpkick.engine.plugin.PluginProcess.isPipeClosed(e)) {
                try {
                    return run(request);
                } catch (IOException e2) {
                    throw new UncheckedIOException(
                            "groovy compile failed after pipe-closed retry: " + e2.getMessage(), e2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("groovy compile interrupted on retry", ie);
                }
            }
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("groovy compile interrupted", e);
        }
    }

    private GroovycResult run(GroovycRequest request) throws IOException, InterruptedException {
        Path spec = writeSpec(request);
        try {
            // The plugin is jk's OWN process: it runs on jk's runtime (plugins are built at jk's
            // language level and must not be hostage to the project's pinned JDK). The bytecode
            // level is an input: writeSpec passes it as the spec's jvmTarget.
            Path hostJavaHome = cc.jumpkick.jdk.JavaHomes.runningJavaHome();
            String classpath = request.workerClasspath().stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(java.io.File.pathSeparator));
            List<String> rest = new ArrayList<>(List.of(
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
            java.util.ArrayDeque<String> chatter = new java.util.ArrayDeque<>();
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
                diagnostics.add("groovyc worker exited " + exit + " without diagnostics; last output:");
                diagnostics.addAll(chatter);
            }
            return new GroovycResult(success, String.join("\n", diagnostics));
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /** Render the request into the unified JSONL plugin spec. */
    private static Path writeSpec(GroovycRequest request) throws IOException {
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                .configString("jvmTarget", String.valueOf(request.jvmTarget()));
        java.util.Map<String, Path> layout = new java.util.LinkedHashMap<>();
        layout.put("classesDir", request.outputDir());
        if (request.workDir() != null) layout.put("workdir", request.workDir());
        sw.layout(layout);
        if (request.stubsOut() != null) sw.extra("stubsOut", request.stubsOut());
        if (!request.javaSourceRoots().isEmpty()) {
            sw.configList(
                    "javaSourceRoots",
                    request.javaSourceRoots().stream()
                            .map(p -> p.toAbsolutePath().toString())
                            .toList());
        }
        for (Path src : request.sources()) sw.source(src);
        for (Path cp : request.classpath()) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        for (String arg : request.extraArgs()) sw.arg(arg);
        Path spec = Files.createTempFile("jk-groovyc-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        return spec;
    }
}
