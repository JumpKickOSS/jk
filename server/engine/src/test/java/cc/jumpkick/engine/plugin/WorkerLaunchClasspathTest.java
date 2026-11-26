// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerLaunchClasspathTest {

    @BeforeEach
    void clearCaches() {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        PomRuntimeClasspath.clearResolveCacheForTests();
    }

    /**
     * A worker launched from a workspace {@code target/} tree runs on the workspace libraries its
     * POM names as the build made them, from their own {@code target/} jars, never from a store
     * that may hold another install's or none at all; its third-party dependencies still come from
     * the host store.
     */
    @Test
    void a_workspace_worker_takes_its_sibling_libraries_from_their_target_trees(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host-store");
        Path sandbox = tmp.resolve("sandbox-home");
        Files.createDirectories(sandbox);
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "ws"
                version = "1.0.0"
                [workspace]
                modules = ["plugins/host-worker", "shared/sib"]
                """);
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-host-worker", "1.0.0");
        Coordinate dep = Coordinate.of("org.example", "lib", "1.0");
        Path workerModule = ws.resolve("plugins/host-worker");
        Files.createDirectories(workerModule);
        Files.writeString(workerModule.resolve("jk.toml"), "name = \"jk-host-worker\"\n");
        Path workspaceJar = workerModule.resolve("target/jk-host-worker-1.0.0.jar");
        Files.createDirectories(workspaceJar.getParent());
        // A module output directory, not merely a path containing `target`: the compiled classes
        // beside it are what BuildLayout.isBuildOutput anchors on.
        Files.createDirectories(workspaceJar.resolveSibling("classes"));
        Files.writeString(workspaceJar, "workspace-worker");
        put(host, MavenLayout.artifactPath(worker), "store-worker");
        put(host, MavenLayout.pomPath(worker), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-host-worker</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick</groupId>
                      <artifactId>jk-sib</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path depJar = put(host, MavenLayout.artifactPath(dep), "dep-bytes");
        put(host, MavenLayout.pomPath(dep), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);
        Path sibModule = ws.resolve("shared/sib");
        Files.createDirectories(sibModule.resolve("target/classes"));
        Files.writeString(sibModule.resolve("jk.toml"), "name = \"jk-sib\"\n");
        Path sibJar = sibModule.resolve("target/jk-sib-1.0.0.jar");
        Files.writeString(sibJar, "sib");
        // The sibling's own dependency is reached through its sidecar POM.
        Files.writeString(sibModule.resolve("target/jk-sib-1.0.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-sib</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        String prevHost = System.getProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
        try {
            System.setProperty("jk.env.JK_HOME", sandbox.toString());
            System.setProperty(
                    PomRuntimeClasspath.HOST_STORE_PROPERTY,
                    host.toAbsolutePath().toString());
            List<Path> cp = WorkerLaunchClasspath.paths(workspaceJar);
            // The dependency's bytes come from the host store and are kept in the sandbox's: the
            // host store is read, never written.
            Path depAbs = cp.stream()
                    .filter(p -> "lib-1.0.jar".equals(String.valueOf(p.getFileName())))
                    .findFirst()
                    .orElseThrow();
            assertThat(depAbs).hasSameBinaryContentAs(depJar);
            assertThat(depAbs).startsWith(sandbox.toAbsolutePath().normalize());
            assertThat(cp)
                    .containsExactly(
                            workspaceJar.toAbsolutePath().normalize(),
                            sibJar.toAbsolutePath().normalize(),
                            depAbs);
        } finally {
            if (prevHost == null) System.clearProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
            else System.setProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY, prevHost);
        }
    }

    @Test
    void jk_local_shelf_jars_are_recognised_and_foreign_trees_are_not(@TempDir Path tmp) throws Exception {
        Path shelfJar = JkStores.store()
                .resolve("repos")
                .resolve(RepoArtifactResolver.JK_LOCAL)
                .resolve("cc/jumpkick/example/1.0/example-1.0.jar");
        assertThat(WorkerLaunchClasspath.isJkLocalJar(shelfJar)).isTrue();
        assertThat(WorkerLaunchClasspath.isJkLocalJar(tmp.resolve("repos/jk-local/x.jar")))
                .isFalse();
        assertThat(WorkerLaunchClasspath.isJkLocalJar(tmp.resolve("target/plugins/w.jar")))
                .isFalse();
    }

    @Test
    void pin_jk_local_copies_shelf_jars_into_the_cas_and_leaves_others(@TempDir Path tmp) throws Exception {
        Path other = tmp.resolve("other.jar");
        Files.writeString(other, "other");
        // A path that looks like jk-local but is not under the live store must stay put.
        Path fakeShelf = tmp.resolve("repos/jk-local/cc/jumpkick/x/1/x-1.jar");
        Files.createDirectories(fakeShelf.getParent());
        Files.writeString(fakeShelf, "fake");

        List<Path> pinned = WorkerLaunchClasspath.pinJkLocal(List.of(other, fakeShelf));
        assertThat(pinned)
                .containsExactly(
                        other.toAbsolutePath().normalize(),
                        fakeShelf.toAbsolutePath().normalize());
    }

    @Test
    void a_shelf_memo_is_trusted_only_while_it_describes_the_jar_on_disk(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("x-1.jar");
        Files.writeString(jar, "first");
        String real = Hashing.sha256Hex(jar);
        Path memoFile = jar.resolveSibling(ArtifactMemo.jkFileName("x-1.jar"));

        // A memo carries a 64-hex sha; this one is deliberately not the file's, to show it is trusted.
        String recorded = "f".repeat(64);
        ArtifactMemo.ofBlob(jar, "cc.jumpkick:x:1", recorded).write(memoFile);
        assertThat(WorkerLaunchClasspath.shaOf(jar))
                .as("size and mtime match: no re-hash")
                .isEqualTo(recorded);

        // Same length, other bytes, other mtime: the memo describes the previous jar.
        Files.writeString(jar, "later");
        Files.setLastModifiedTime(
                jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 5_000));
        assertThat(WorkerLaunchClasspath.shaOf(jar))
                .isEqualTo(Hashing.sha256Hex(jar))
                .isNotEqualTo(real);

        Files.deleteIfExists(memoFile);
        assertThat(WorkerLaunchClasspath.shaOf(jar)).as("no memo: hash").isEqualTo(Hashing.sha256Hex(jar));
    }

    private static Path put(Path store, String rel, String bytes) throws Exception {
        return put(store, rel, bytes.getBytes(StandardCharsets.UTF_8));
    }

    private static Path put(Path store, String rel, byte[] bytes) throws Exception {
        Path f = store.resolve("repos/jk-local").resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        RepoArtifactStore.forStoreId(store, RepoArtifactResolver.JK_LOCAL).writeMemo(rel, f, Hashing.sha256Hex(bytes));
        return f.toAbsolutePath().normalize();
    }
}
