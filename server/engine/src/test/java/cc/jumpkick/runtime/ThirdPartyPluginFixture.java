// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

/**
 * Publishes a third-party plugin to a {@code file://} Maven-layout repo for the offline plugin
 * fixtures: compile the worker source, jar it with a hand-written {@code jk-plugin.toml}, drop a
 * sibling POM next to it.
 *
 * <p>The jar is assembled here rather than by jk's own plugin writer on purpose: a fixture that
 * impersonates a third-party publisher must not share code with the producer under test, or the
 * two agree on a shape neither validates.
 */
final class ThirdPartyPluginFixture {

    private ThirdPartyPluginFixture() {}

    /**
     * Compile {@code mainJava} (one top-level class named {@code mainClass}), jar it with
     * {@code manifestToml} as the plugin manifest, and publish jar + POM under {@code repo}.
     *
     * @return {@code repo}, ready to be named as a {@code [repositories]} URI
     */
    static Path publish(
            Path repo,
            String group,
            String artifact,
            String version,
            String mainClass,
            String mainJava,
            String manifestToml)
            throws Exception {
        Path src = Files.createTempDirectory("jk-plugin-fixture-src");
        Path srcFile = src.resolve(mainClass + ".java");
        Files.writeString(srcFile, mainJava);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", src.toString(), srcFile.toString());
        if (rc != 0) throw new IllegalStateException("fixture compile failed");

        Path dir = Files.createDirectories(
                repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version));
        Path jar = dir.resolve(artifact + "-" + version + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, mf)) {
            jos.putNextEntry(new JarEntry("jk-plugin.toml"));
            jos.write(manifestToml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry(mainClass + ".class"));
            jos.write(Files.readAllBytes(src.resolve(mainClass + ".class")));
            jos.closeEntry();
        }
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                </project>
                """.formatted(group, artifact, version));
        return repo;
    }
}
