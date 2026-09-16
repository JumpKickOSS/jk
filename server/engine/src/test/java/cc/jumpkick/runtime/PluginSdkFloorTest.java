// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoGroup;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A path-pinned plugin's lock carries its SDK floor as ordinary {@code plugin}-scoped rows
 * resolved from the consumer's declared repositories, and the fork classpath is made of them.
 *
 * <p>The declared repository is a file tree this test publishes, with stand-ins for
 * {@code jk-plugin-sdk} and {@code jk-host} at the running jk's version; the first-party repository
 * jk ships with is consulted too and wins when it serves the release. Either way the fetch lands in
 * the ambient store, the same store {@link PluginSdkFloor#classpath} reads.
 */
@Tag("integration")
class PluginSdkFloorTest {

    @Test
    void a_path_pins_lock_carries_the_sdk_floor_and_the_fork_classpath_contains_it(@TempDir Path tmp) throws Exception {
        Path repo = publishSdk(tmp.resolve("repo"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path pluginJar = jar(project.resolve("hello.jar"), "hello-plugin");
        String pin = Hashing.sha256Hex(pluginJar);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "demo"
                group   = "com.demo"
                version = "0.1.0"

                [m2]
                integration = false
                install = false

                [repositories]
                local = "%s"

                [plugins]
                hello = { path = "hello.jar", sha256 = "%s" }
                """.formatted(repo.toUri(), pin));
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        PluginDeclaration decl = build.plugins().getFirst();
        assertThat(PluginSdkFloor.needsFloor(decl)).isTrue();

        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(build, null, cas);
        List<String> notes = new ArrayList<>();
        List<Lockfile.Artifact> rows = PluginSdkFloor.rows(repos, decl, notes::add);

        assertThat(rows)
                .extracting(Lockfile.Artifact::name)
                .containsExactly("cc.jumpkick:jk-plugin-sdk:jar:", "cc.jumpkick:jk-host:jar:");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.version()).isEqualTo(JkVersion.VERSION);
            assertThat(row.scopes()).containsExactly(Scope.PLUGIN);
            assertThat(row.pinnedBy()).isEqualTo("plugin:path:hello");
            assertThat(row.source()).as("names the repository it came from").contains("+");
            assertThat(row.checksum()).startsWith("sha256:");
        });
        assertThat(notes).singleElement().asString().contains("path:hello").contains(JkVersion.VERSION);

        Lockfile lock = PluginSdkFloor.withRows(
                new Lockfile(Lockfile.CURRENT_VERSION, "test", Lockfile.RESOLUTION_ALGORITHM, List.of()), rows);
        List<Path> classpath = PluginSdkFloor.classpath(lock, cas);
        assertThat(classpath).hasSize(2).allSatisfy(p -> assertThat(p).isRegularFile());
        assertThat(classpath.get(0).getFileName().toString()).startsWith("jk-plugin-sdk-");
        assertThat(classpath.get(1).getFileName().toString()).startsWith("jk-host-");
    }

    /** Stand-ins for the SDK floor in Maven layout: a jar and a POM per artifact at the running version. */
    private static Path publishSdk(Path repo) throws Exception {
        for (String artifact : PluginSdkFloor.ARTIFACTS) {
            Path dir = Files.createDirectories(
                    repo.resolve("cc/jumpkick").resolve(artifact).resolve(JkVersion.VERSION));
            jar(dir.resolve(artifact + "-" + JkVersion.VERSION + ".jar"), artifact);
            Files.writeString(dir.resolve(artifact + "-" + JkVersion.VERSION + ".pom"), """
                    <project><modelVersion>4.0.0</modelVersion>
                    <groupId>cc.jumpkick</groupId><artifactId>%s</artifactId><version>%s</version>
                    </project>
                    """.formatted(
                            artifact, JkVersion.VERSION));
        }
        return repo;
    }

    private static Path jar(Path file, String marker) throws Exception {
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("marker.txt"));
            jar.write(marker.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }
}
