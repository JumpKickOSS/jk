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

    @Test
    void workspace_codec_joins_the_launch_classpath(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host-store");
        Path sandbox = tmp.resolve("sandbox-home");
        Files.createDirectories(sandbox);
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-host-worker", "1.0.0");
        Coordinate dep = Coordinate.of("org.example", "lib", "1.0");
        Path workspaceJar = tmp.resolve("target/plugins/host-worker/jk-host-worker-1.0.0.jar");
        Files.createDirectories(workspaceJar.getParent());
        // A module output directory, not merely a path containing `target`: the compiled classes
        // beside it are what BuildLayout.isBuildOutput anchors on.
        Files.createDirectories(workspaceJar.resolveSibling("classes").resolve("main"));
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
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
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

        Path sdkClasses = tmp.resolve("target/shared/plugin-sdk/classes/main");
        Files.createDirectories(sdkClasses);
        Path hostCodecJar = tmp.resolve("target/shared/host/lib/jk-host-1.0.0.jar");
        Files.createDirectories(hostCodecJar.getParent());
        Files.writeString(hostCodecJar, "host");

        String prevHome = System.getProperty("jk.env.JK_HOME");
        String prevHost = System.getProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
        try {
            System.setProperty("jk.env.JK_HOME", sandbox.toString());
            System.setProperty(
                    PomRuntimeClasspath.HOST_STORE_PROPERTY,
                    host.toAbsolutePath().toString());
            List<Path> cp = WorkerLaunchClasspath.paths(workspaceJar);
            Path workerAbs = workspaceJar.toAbsolutePath().normalize();
            Path sdkAbs = sdkClasses.toAbsolutePath().normalize();
            Path hostCodecAbs = hostCodecJar.toAbsolutePath().normalize();
            Path depAbs = depJar.toAbsolutePath().normalize();
            // : codec dirs must precede the worker jar so a fresh classes/main wins over the
            // codec the jar vendors.
            assertThat(cp).contains(workerAbs, sdkAbs, hostCodecAbs, depAbs);
            assertThat(cp.indexOf(sdkAbs)).isLessThan(cp.indexOf(workerAbs));
            assertThat(cp.indexOf(hostCodecAbs)).isLessThan(cp.indexOf(workerAbs));
            assertThat(cp.indexOf(sdkAbs)).isLessThan(cp.indexOf(depAbs));
            assertThat(cp.indexOf(hostCodecAbs)).isLessThan(cp.indexOf(depAbs));
        } finally {
            if (prevHome == null) System.clearProperty("jk.env.JK_HOME");
            else System.setProperty("jk.env.JK_HOME", prevHome);
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
