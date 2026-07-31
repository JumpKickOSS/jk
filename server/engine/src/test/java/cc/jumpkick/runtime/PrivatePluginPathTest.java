// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.util.Hashing;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * : path-pinned private plugins with required sha256 (fail closed on missing /
 * mismatch).
 */
@Tag("integration")
class PrivatePluginPathTest {

    private static final String MANIFEST = """
            [plugin]
            id      = "acme"
            table   = "acme"
            version = "1.0.0"

            [schema]
            widgets = { type = "bool", default = false }

            [[contribute.compiler-args]]
            javac = ["-Aacme.widgets=${config.widgets}"]
            """;

    @Test
    void path_pin_with_matching_sha256_materializes_and_validates(@TempDir Path tmp) throws Exception {
        Path vendor = Files.createDirectories(tmp.resolve("outside").resolve("vendor"));
        Path jar = writePluginJar(vendor.resolve("acme-rules-1.0.0.jar"));
        String hex = Hashing.sha256Hex(jar);

        Path project = Files.createDirectories(tmp.resolve("proj"));
        // Path is relative to the project; jar lives outside the monorepo-style tree.
        Path relVendor = project.relativize(jar);
        Files.writeString(
                project.resolve("jk.toml"), """
                [project]
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                [plugins]
                acme = { path = "%s", sha256 = "%s" }

                [acme]
                widgets = true
                """.formatted(relVendor.toString().replace('\\', '/'), hex));

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.plugins()).hasSize(1);
        assertThat(build.plugins().getFirst().isPathPin()).isTrue();
        assertThat(build.plugins().getFirst().sha256()).isEqualTo(hex);
        assertThat(build.pluginConfig("acme")).isEmpty(); // not materialized yet

        Cas cas = new Cas(tmp.resolve("cache"));
        Path casJar = cas.putFile(jar, hex);
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of(new Lockfile.PluginEntry("path:acme", "local", "sha256:" + hex))),
                project.resolve("jk-lock.toml"));
        PluginDescriptorOps.materialize(project, hex, casJar);
        assertThat(PluginDescriptorOps.ensureMaterialized(project, tmp.resolve("cache")))
                .isFalse();

        build = JkBuildParser.reparse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("acme")).isPresent();
        assertThat(build.pluginConfig("acme").orElseThrow().bool("widgets", false))
                .isTrue();
        assertThat(PluginContributions.javacArgs(build, project, Set.of())).contains("-Aacme.widgets=true");
    }

    @Test
    void missing_sha256_is_a_parse_error(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                [plugins]
                acme = { path = "vendor/x.jar" }
                """);
        assertThatThrownBy(() -> JkBuildParser.parse(project.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("sha256")
                .hasMessageContaining("acme");
    }

    @Test
    void wrong_sha256_on_path_fails_at_lock(@TempDir Path tmp) throws Exception {
        Path vendor = Files.createDirectories(tmp.resolve("vendor"));
        Path jar = writePluginJar(vendor.resolve("acme.jar"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                [plugins]
                acme = { path = "%s", sha256 = "%s" }
                """.formatted(
                        project.relativize(jar).toString().replace('\\', '/'),
                        "0000000000000000000000000000000000000000000000000000000000000000"));

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        // Drive lock-plugins via the real pipeline helper used by jk lock.
        var result = LockPipelines.lockPipeline(
                        project, build, tmp.resolve("cache"), null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(result.success()).isFalse();
        assertThat(result.errors().toString()).containsIgnoringCase("sha256");
    }

    @Test
    void coord_without_sha256_is_a_parse_error() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                        [project]
                        name = "demo"
                        group = "com.demo"
                        version = "0.1.0"

                        [plugins]
                        hello = { group = "com.example", name = "hello", version = "1.0.0" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("sha256");
    }

    private static Path writePluginJar(Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, mf)) {
            jos.putNextEntry(new JarEntry("jk-plugin.toml"));
            jos.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }
}
