// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.util.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomRuntimeClasspathTest {

    @Test
    void walks_compile_deps_and_skips_provided(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate sdk = Coordinate.of("cc.jumpkick", "jk-plugin-sdk", "0.12.0");
        Coordinate junit = Coordinate.of("org.junit.jupiter", "junit-jupiter", "5.12.0");
        putJar(store, "local", worker, "worker-bytes");
        putPom(store, "local", worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>cc.jumpkick</groupId>
                      <artifactId>jk-plugin-sdk</artifactId>
                      <version>0.12.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.12.0</version>
                      <scope>provided</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, "local", sdk, "sdk-bytes");
        putPom(store, "local", sdk, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-plugin-sdk</artifactId>
                  <version>0.12.0</version>
                </project>
                """);
        putJar(store, "central", junit, "junit-bytes");

        Path workerJar = store.resolve("repos/local").resolve(MavenLayout.artifactPath(worker));
        List<Path> cp = PomRuntimeClasspath.resolve(workerJar);
        assertThat(cp).contains(workerJar.toAbsolutePath().normalize());
        assertThat(cp)
                .contains(store.resolve("repos/local")
                        .resolve(MavenLayout.artifactPath(sdk))
                        .toAbsolutePath()
                        .normalize());
        assertThat(cp.stream().map(Path::getFileName).map(Path::toString)).doesNotContain("junit-jupiter-5.12.0.jar");
    }

    @Test
    void throws_without_a_pom(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("lonely.jar");
        Files.writeString(jar, "x");
        assertThatThrownBy(() -> PomRuntimeClasspath.resolve(jar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POM");
    }

    private static void putJar(Path store, String repo, Coordinate coord, String bytes) throws Exception {
        put(store, repo, MavenLayout.artifactPath(coord), bytes.getBytes(StandardCharsets.UTF_8));
    }

    private static void putPom(Path store, String repo, Coordinate coord, String xml) throws Exception {
        put(store, repo, MavenLayout.pomPath(coord), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(Path store, String repo, String rel, byte[] bytes) throws Exception {
        Path f = store.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        Files.writeString(Path.of(f + ".sha256"), Hashing.sha256Hex(bytes));
    }
}
