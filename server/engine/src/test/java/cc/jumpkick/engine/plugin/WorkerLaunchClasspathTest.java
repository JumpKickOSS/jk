// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
