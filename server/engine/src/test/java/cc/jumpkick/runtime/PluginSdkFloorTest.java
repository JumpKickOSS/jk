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
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
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
 * resolved from the consumer's declared repositories, at the SDK version the plugin's manifest
 * names (the running jk's, with a note, when it names none), and the fork classpath is made of
 * them.
 *
 * <p>{@code cc.jumpkick} resolves from the {@code jumpkick} repository alone, so the consumer
 * declares a file tree this test publishes <em>as</em> {@code jumpkick}, with stand-ins for {@code
 * jk-plugin-sdk} and {@code jk-host}: nothing reaches the public first-party repository, which need
 * not have published the running jk yet. The fetch lands in the ambient store, the same store {@link
 * PluginSdkFloor#classpath} reads.
 */
@Tag("integration")
class PluginSdkFloorTest {

    @Test
    void a_path_pins_lock_carries_the_sdk_floor_and_the_fork_classpath_contains_it(@TempDir Path tmp) throws Exception {
        Path repo = publishSdk(tmp.resolve("repo"), JkVersion.VERSION);
        JkBuild build = consumer(tmp.resolve("proj"), repo);
        PluginDeclaration decl = build.plugins().getFirst();
        assertThat(PluginSdkFloor.needsFloor(decl)).isTrue();

        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(build, null, cas);
        List<String> notes = new ArrayList<>();
        List<Lockfile.Artifact> rows = PluginSdkFloor.rows(repos, decl, null, notes::add);

        assertThat(rows)
                .extracting(Lockfile.Artifact::name)
                .containsExactly("cc.jumpkick:jk-plugin-sdk:jar:", "cc.jumpkick:jk-host:jar:");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.version()).isEqualTo(JkVersion.VERSION);
            assertThat(row.scopes()).containsExactly(Scope.PLUGIN);
            assertThat(row.pinnedBy()).isEqualTo("plugin:path:hello");
            assertThat(row.source()).as("names the stand-in it came from").startsWith("jumpkick+file:");
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

    @Test
    void a_manifest_that_names_its_sdk_pins_the_floor_at_that_version_without_a_note(@TempDir Path tmp)
            throws Exception {
        // A version no jk release has, so nothing but the manifest can be where the pin came from.
        String declared = "0.0.1-sdk-test";
        Path repo = publishSdk(tmp.resolve("repo"), declared);
        JkBuild build = consumer(tmp.resolve("proj"), repo);
        PluginDeclaration decl = build.plugins().getFirst();
        PluginDescriptor manifest = PluginDescriptors.parse("""
                [plugin]
                id      = "hello"
                table   = "hello"
                version = "0.1.0"
                sdk     = "%s"
                """.formatted(declared), "hello.jar!jk-plugin.toml");
        assertThat(PluginSdkFloor.version(manifest)).isEqualTo(declared);
        assertThat(PluginSdkFloor.version(null)).isEqualTo(JkVersion.VERSION);

        RepoGroup repos = RepoGroupBuilder.buildFor(build, null, JkStores.storeCas());
        List<String> notes = new ArrayList<>();
        List<Lockfile.Artifact> rows = PluginSdkFloor.rows(repos, decl, manifest, notes::add);

        assertThat(rows)
                .extracting(Lockfile.Artifact::name)
                .containsExactly("cc.jumpkick:jk-plugin-sdk:jar:", "cc.jumpkick:jk-host:jar:");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.version()).isEqualTo(declared);
            assertThat(row.scopes()).containsExactly(Scope.PLUGIN);
            assertThat(row.pinnedBy()).isEqualTo("plugin:path:hello");
        });
        assertThat(notes).as("a declared SDK version needs no note").isEmpty();
    }

    /** A consumer pinning {@code hello.jar} by path, with {@code repo} standing in for {@code jumpkick}. */
    private static JkBuild consumer(Path project, Path repo) throws Exception {
        Files.createDirectories(project);
        Path pluginJar = jar(project.resolve("hello.jar"), "hello-plugin");
        String pin = Hashing.sha256Hex(pluginJar);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "demo"
                group   = "com.demo"
                version = "0.1.0"

                [m2]
                integration = false
                install = false

                [repositories.jumpkick]
                url    = "%s"
                groups = ["cc.jumpkick", "cc.jumpkick.*"]

                [plugins]
                hello = { path = "hello.jar", sha256 = "%s" }
                """.formatted(repo.toUri(), pin));
        return JkBuildParser.parse(project.resolve("jk.toml"));
    }

    /** Stand-ins for the SDK floor in Maven layout: a jar and a POM per artifact at {@code version}. */
    private static Path publishSdk(Path repo, String version) throws Exception {
        for (String artifact : PluginSdkFloor.ARTIFACTS) {
            Path dir = Files.createDirectories(
                    repo.resolve("cc/jumpkick").resolve(artifact).resolve(version));
            jar(dir.resolve(artifact + "-" + version + ".jar"), artifact);
            Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), """
                    <project><modelVersion>4.0.0</modelVersion>
                    <groupId>cc.jumpkick</groupId><artifactId>%s</artifactId><version>%s</version>
                    </project>
                    """.formatted(artifact, version));
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
