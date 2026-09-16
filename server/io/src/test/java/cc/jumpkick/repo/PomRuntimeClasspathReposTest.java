// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which repositories a worker's runtime closure is read from and fetched through: the built-in
 * set a lock resolves against, Google's Android Maven included, so a closure that lives only
 * there resolves from an empty store.
 */
class PomRuntimeClasspathReposTest {

    @BeforeEach
    void clearCaches() {
        PomRuntimeClasspath.clearResolveCacheForTests();
        RepoGroup.clearProcessFetchCache();
    }

    /**
     * The Android worker's apksig is published on Google's Android Maven and nowhere else, so the
     * worker view reads the {@code repos/google} store like the first-party three.
     */
    @Test
    void a_dependency_shelved_under_the_google_store_is_on_the_worker_classpath(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-android", "0.13.3");
        Coordinate apksig = Coordinate.of("com.android.tools.build", "apksig", "9.4.0");
        Path workerJar = putJar(store, RepoArtifactResolver.JK_LOCAL, worker, "worker-bytes");
        putPom(store, RepoArtifactResolver.JK_LOCAL, worker, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-android</artifactId>
                  <version>0.13.3</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.android.tools.build</groupId>
                      <artifactId>apksig</artifactId>
                      <version>9.4.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path apksigJar = putJar(store, RepositorySpec.GOOGLE, apksig, "apksig-bytes");
        putPom(store, RepositorySpec.GOOGLE, apksig, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.android.tools.build</groupId>
                  <artifactId>apksig</artifactId>
                  <version>9.4.0</version>
                </project>
                """);

        List<Path> cp = PomRuntimeClasspath.resolve(workerJar, PomRuntimeClasspath.localRepos(store));
        assertThat(cp).contains(apksigJar.toAbsolutePath().normalize());
    }

    /** The remotes a miss walks are the lock's built-in set, Google routing the Android groups. */
    @Test
    void the_worker_remotes_are_jumpkick_central_then_google_with_the_android_groups_routed(@TempDir Path tmp) {
        RepoGroup repos = PomRuntimeClasspath.storeRepos(tmp.resolve("store"));
        List<String> names = repos.repos().stream().map(MavenRepo::name).toList();
        assertThat(names.subList(names.size() - 3, names.size()))
                .containsExactly(RepositorySpec.JUMPKICK_NAME, RepositorySpec.CENTRAL, RepositorySpec.GOOGLE);
        assertThat(repos.routedGroups().get(names.lastIndexOf(RepositorySpec.GOOGLE)))
                .contains("com.android.*", "androidx.*");
        assertThat(repos.exclusiveGroups().get(names.lastIndexOf(RepositorySpec.GOOGLE)))
                .as("Google claims nothing exclusively")
                .isEmpty();
        assertThat(repos.exclusiveGroups().get(names.lastIndexOf(RepositorySpec.CENTRAL)))
                .as("Central claims nothing")
                .isEmpty();
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
