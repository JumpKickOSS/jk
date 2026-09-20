// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
     * back what the compiler is actually told.
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
}
