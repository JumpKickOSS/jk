// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.util.Hashing;
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
        cc.jumpkick.repo.EffectivePomBuilder.clearProcessCache();
        cc.jumpkick.repo.RepoGroup.clearProcessFetchCache();
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
        Path jsonlJar = tmp.resolve("target/shared/jsonl/lib/jk-jsonl-1.0.0.jar");
        Files.createDirectories(jsonlJar.getParent());
        Files.writeString(jsonlJar, "jsonl");

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
            Path jsonlAbs = jsonlJar.toAbsolutePath().normalize();
            Path depAbs = depJar.toAbsolutePath().normalize();
            assertThat(cp.getFirst()).isEqualTo(workerAbs);
            assertThat(cp).contains(sdkAbs, jsonlAbs, depAbs);
            assertThat(cp.indexOf(sdkAbs)).isLessThan(cp.indexOf(depAbs));
            assertThat(cp.indexOf(jsonlAbs)).isLessThan(cp.indexOf(depAbs));
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
        Path f = store.resolve("repos/local").resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        Files.writeString(Path.of(f + ".sha256"), Hashing.sha256Hex(bytes));
        return f.toAbsolutePath().normalize();
    }
}
