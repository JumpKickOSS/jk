// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A launch resolves from what the install staged: a jar without its POM, or a missing jar, is a
 * resolution-time error naming the coordinate and the install that stages it — never a silent
 * prune that the worker pays for later with NoClassDefFoundError.
 */
class PomRuntimeClasspathStagingTest {

    @BeforeEach
    void clearCaches() {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        PomRuntimeClasspath.clearResolveCacheForTests();
    }

    @Test
    void dep_without_its_own_pom_fails_naming_the_install_that_stages_it(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate fat = Coordinate.of("com.foo", "fat", "1.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>fat</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, RepoArtifactResolver.JK_LOCAL, fat, "fat-bytes");

        // The jar alone says nothing about what it needs: a store short of the POM is an install
        // that did not stage the graph, not a dependency-free artifact.
        assertThatThrownBy(() -> resolve(store, workerJar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:fat:1.0")
                .hasMessageContaining("no POM")
                .hasMessageContaining("`jk install`");
    }

    @Test
    void a_missing_jar_names_the_install_that_stages_it(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>gone</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThatThrownBy(() -> resolve(store, workerJar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.foo:gone:1.0 was not found")
                .hasMessageContaining("`jk install`");
    }

    @Test
    void throws_without_a_pom(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("lonely.jar");
        Files.writeString(jar, "x");
        assertThatThrownBy(() -> PomRuntimeClasspath.resolve(jar, PomRuntimeClasspath.localRepos(tmp)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POM");
    }

    @Test
    void missing_parent_pom_is_loud_not_a_silent_prune(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-test-runner", "0.12.0");
        Coordinate lib = Coordinate.of("com.foo", "lib", "1.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-test-runner</artifactId>
                  <version>0.12.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>lib</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        putJar(store, RepoArtifactResolver.JK_LOCAL, lib, "lib-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, lib, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.foo</groupId><artifactId>parent</artifactId><version>9</version>
                  </parent>
                  <groupId>com.foo</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertThatThrownBy(() -> resolve(store, workerJar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete POM chain")
                .hasMessageContaining("com.foo:parent:9");
    }

    private static List<Path> resolve(Path store, Path workerJar) {
        return PomRuntimeClasspath.resolve(workerJar, PomRuntimeClasspath.localRepos(store));
    }

    private static Path putJar(Path store, String repo, Coordinate coord, String bytes) throws Exception {
        return put(store, repo, MavenLayout.artifactPath(coord), bytes.getBytes(StandardCharsets.UTF_8));
    }

    private static void putPom(Path store, String repo, Coordinate coord, String xml) throws Exception {
        put(store, repo, MavenLayout.pomPath(coord), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Path put(Path store, String repo, String rel, byte[] bytes) throws Exception {
        Path f = store.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
        RepoArtifactStore.forStoreId(store, repo).writeMemo(rel, f, Hashing.sha256Hex(bytes));
        return f;
    }
}
