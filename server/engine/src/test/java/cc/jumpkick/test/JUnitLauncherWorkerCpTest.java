// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Thin test-runner jars rebuild -cp from the installed Maven POM. */
class JUnitLauncherWorkerCpTest {

    @Test
    void thin_runner_classpath_includes_plugin_sdk(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Coordinate runner = Coordinate.of("cc.jumpkick", "jk-test-runner", "1.0");
        Coordinate sdk = Coordinate.of("cc.jumpkick", "jk-plugin-sdk", "1.0");
        Path thin = putJar(store, runner, "thin");
        putPom(store, runner, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick</groupId>
                      <artifactId>jk-plugin-sdk</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path sdkJar = putJar(store, sdk, "sdk");
        putPom(store, sdk, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-plugin-sdk</artifactId>
                  <version>1.0</version>
                </project>
                """);

        List<Path> paths = PomRuntimeClasspath.resolve(thin);
        assertThat(paths)
                .contains(
                        thin.toAbsolutePath().normalize(),
                        sdkJar.toAbsolutePath().normalize());
    }

    private static Path putJar(Path store, Coordinate coord, String bytes) throws Exception {
        return put(store, MavenLayout.artifactPath(coord), bytes.getBytes(StandardCharsets.UTF_8));
    }

    private static void putPom(Path store, Coordinate coord, String xml) throws Exception {
        put(store, MavenLayout.pomPath(coord), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Path put(Path store, String rel, byte[] bytes) throws Exception {
        Path f = store.resolve("repos/local").resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        RepoArtifactStore.forRepoName(store, "local").writeMemo(rel, f, Hashing.sha256Hex(bytes));
        return f;
    }
}
