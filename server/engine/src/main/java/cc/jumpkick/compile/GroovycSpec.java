// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Groovy half of a worker compile: what {@link WorkerCompileDriver} tells {@code
 * jk-groovy-compiler} that it does not tell {@code jk-kotlin-compiler}. groovyc takes no project
 * JDK — there is no {@code -jdk-home} here and no {@code javaHome} on {@link GroovycRequest} —
 * and it has a joint-compilation pass Kotlin does not: {@code stubsOut} and {@code javaSourceRoots}
 * are the inputs to it, and the processor path rides along so the swept javac pass runs the same
 * annotation processors jk's real javac lane does.
 */
final class GroovycSpec {

    private GroovycSpec() {}

    /** Render the request into the unified JSONL plugin spec. */
    static Path write(GroovycRequest request) throws IOException {
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-groovy-compiler")
                .configString("jvmTarget", String.valueOf(request.jvmTarget()));
        Map<String, Path> layout = new LinkedHashMap<>();
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
        for (Path pp : request.processorPath()) sw.cp(pp, PluginProtocol.ROLE_PROCESSOR);
        for (String arg : request.extraArgs()) sw.arg(arg);
        Path spec = Files.createTempFile("jk-groovyc-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        return spec;
    }
}
