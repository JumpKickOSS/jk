// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.system.SelfShelveCommand;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk self shelve <repos-dir>} copies a dist's {@code repos/jk-local} tree onto the home's
 * shelf with a memo per artifact, so a private install runs the workers built beside its engine,
 * and pins the jars to the engine the home has materialized.
 */
class SelfShelveCommandTest {

    @TempDir
    Path isolatedHome;

    private String prevHome;

    @BeforeEach
    void isolateHome() {
        prevHome = System.getProperty("jk.env.JK_HOME");
        System.setProperty("jk.env.JK_HOME", isolatedHome.toString());
        CliOutput.beginCommand(false);
    }

    @AfterEach
    void restoreHome() {
        if (prevHome == null) System.clearProperty("jk.env.JK_HOME");
        else System.setProperty("jk.env.JK_HOME", prevHome);
    }

    @Test
    void shelves_every_artifact_with_a_memo_and_replaces_stale_bytes(@TempDir Path dist) throws Exception {
        Path repos = dist.resolve("repos");
        Path entry = repos.resolve("jk-local/cc/jumpkick/jk-test-runner/1.0.0");
        Files.createDirectories(entry);
        Files.writeString(entry.resolve("jk-test-runner-1.0.0.jar"), "worker-bytes");
        Files.writeString(entry.resolve("jk-test-runner-1.0.0.pom"), "<project/>");
        Path pack = repos.resolve("jk-local/cc/jumpkick/guards/spring/1.0.0");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("spring-1.0.0.jar"), "pack-bytes");
        Files.writeString(pack.resolve("spring-1.0.0.pom"), "<project/>");

        Path shelf = JkDirs.store().resolve("repos").resolve("jk-local");
        Path shelvedJar = shelf.resolve("cc/jumpkick/jk-test-runner/1.0.0/jk-test-runner-1.0.0.jar");
        Files.createDirectories(shelvedJar.getParent());
        Files.writeString(shelvedJar, "published-bytes");
        Files.writeString(shelvedJar.resolveSibling("jk-test-runner-1.0.0.jk"), "stale memo");

        int[] exit = {0};
        var streams = Capture.both(() -> exit[0] = shelve(repos));

        assertThat(exit[0]).isZero();
        assertThat(TestAnsi.strip(streams.out())).contains("Self").contains("Shelved 4 artifacts");
        assertThat(streams.err()).isEmpty();
        assertThat(shelvedJar).hasContent("worker-bytes");
        Path jarMemo = ArtifactMemo.jkPath(shelf, "cc/jumpkick/jk-test-runner/1.0.0/jk-test-runner-1.0.0.jar");
        assertThat(ArtifactMemo.read(jarMemo)).isPresent();
        assertThat(ArtifactMemo.read(jarMemo).orElseThrow().coordinate()).isEqualTo("cc.jumpkick:jk-test-runner:1.0.0");
        assertThat(shelf.resolve("cc/jumpkick/jk-test-runner/1.0.0/jk-test-runner-1.0.0.pom"))
                .hasContent("<project/>");
        assertThat(ArtifactMemo.jkPath(shelf, "cc/jumpkick/jk-test-runner/1.0.0/jk-test-runner-1.0.0.pom"))
                .exists();
        assertThat(shelf.resolve("cc/jumpkick/guards/spring/1.0.0/spring-1.0.0.jar"))
                .hasContent("pack-bytes");
    }

    @Test
    void with_a_materialized_engine_the_shelved_jars_are_pinned_to_it(@TempDir Path dist) throws Exception {
        Path engineJar = Files.writeString(dist.resolve("jk-engine-" + JkVersion.VERSION + ".jar"), "engine-bytes");
        EngineInstall install = EngineInstall.current();
        install.materializeFromFiles(JkVersion.VERSION, JkStores.storeCas(), engineJar);
        Path repos = dist.resolve("repos");
        Path entry = repos.resolve("jk-local/cc/jumpkick/jk-test-runner/1.0.0");
        Files.createDirectories(entry);
        Path worker = Files.writeString(entry.resolve("jk-test-runner-1.0.0.jar"), "worker-bytes");
        Files.writeString(entry.resolve("jk-test-runner-1.0.0.pom"), "<project/>");

        int[] exit = {0};
        var streams = Capture.both(() -> exit[0] = shelve(repos));

        assertThat(exit[0]).isZero();
        assertThat(TestAnsi.strip(streams.out())).contains("1 jars pinned to engine");
        ShelfManifest pins = ShelfManifest.read(install.shelfFile()).orElseThrow();
        assertThat(pins.pins(Hashing.sha256Hex(engineJar))).isTrue();
        assertThat(pins.source()).isEqualTo(dist.toAbsolutePath().normalize().toString());
        assertThat(pins.jars()).containsExactly(entry("cc.jumpkick:jk-test-runner:1.0.0", Hashing.sha256Hex(worker)));
        assertThat(JkStores.storeCas().pathFor(Hashing.sha256Hex(worker)))
                .as("the shelf publish fed the store first")
                .hasContent("worker-bytes");
    }

    @Test
    void an_engine_jar_the_pointer_does_not_name_by_sha_leaves_the_shelf_unpinned(@TempDir Path dist) throws Exception {
        // A jar dropped into the engine home with no pointer is inferred by name; it has no sha256.
        EngineInstall install = EngineInstall.current();
        Files.createDirectories(install.engineHome());
        Files.writeString(install.engineHome().resolve("jk-engine-" + JkVersion.VERSION + ".jar"), "engine-bytes");
        assertThat(install.resolve(JkVersion.VERSION)).isPresent();
        Path repos = dist.resolve("repos");
        Path entry = repos.resolve("jk-local/cc/jumpkick/jk-test-runner/1.0.0");
        Files.createDirectories(entry);
        Files.writeString(entry.resolve("jk-test-runner-1.0.0.jar"), "worker-bytes");

        int[] exit = {0};
        var streams = Capture.both(() -> exit[0] = shelve(repos));

        assertThat(exit[0]).isZero();
        assertThat(TestAnsi.strip(streams.out()))
                .contains("Shelved 1 artifacts")
                .contains("not pinned");
        assertThat(install.shelfFile()).doesNotExist();
    }

    @Test
    void a_dist_without_a_jk_local_tree_is_refused(@TempDir Path dist) throws Exception {
        Path repos = Files.createDirectories(dist.resolve("repos"));

        int[] exit = {0};
        var streams = Capture.both(() -> exit[0] = shelve(repos));

        assertThat(exit[0]).isNotZero();
        assertThat(TestAnsi.strip(streams.err())).contains("Self").contains("no jk-local shelf under");
        assertThat(JkDirs.store().resolve("repos")).doesNotExist();
    }

    private static int shelve(Path repos) {
        try {
            return new SelfShelveCommand()
                    .run(Invocation.builder().addPositional(repos.toString()).build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
