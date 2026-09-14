// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shelving a worker also stages the POM graph its launch walks, so a fork resolves from the store
 * and never over the network; a closure the store cannot complete fails the install, naming the
 * gap, instead of the worker later.
 */
class InstallPlansWorkerStagingTest {

    private static final Coordinate WORKER = Coordinate.of("cc.jumpkick", "jk-foo", "1.0");
    private static final Coordinate LIB = Coordinate.of("com.acme", "lib", "1.0");

    @Test
    void a_store_short_of_a_pom_fails_the_install_naming_the_dependency(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        shelveWorker(store);
        put(store, "central", MavenLayout.artifactPath(LIB), "lib-bytes");

        String prev = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_STORE_DIR", store.toAbsolutePath().toString());
        try {
            // Offline: the remotes are unreachable, exactly the first launch this staging exists for.
            Session offline = Session.defaults().withConfig(JkConfig.empty().withOffline(true));
            assertThatThrownBy(() -> SessionContext.where(offline, () -> {
                        InstallPlans.stageWorkerClosure(WORKER, tmp.resolve("cache"), MavenLayout.artifactPath(WORKER));
                        return null;
                    }))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cc.jumpkick:jk-foo:1.0")
                    .hasMessageContaining("launch classpath does not resolve")
                    .hasMessageContaining("com.acme:lib:1.0")
                    .hasMessageContaining("`jk install`");

            put(store, "central", MavenLayout.pomPath(LIB), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.acme</groupId><artifactId>lib</artifactId><version>1.0</version>
                    </project>
                    """);
            PomRuntimeClasspath.clearResolveCacheForTests();
            SessionContext.where(offline, () -> {
                InstallPlans.stageWorkerClosure(WORKER, tmp.resolve("cache"), MavenLayout.artifactPath(WORKER));
                return null;
            });
            Path shelved = store.resolve("repos")
                    .resolve(RepoArtifactResolver.JK_LOCAL)
                    .resolve(MavenLayout.artifactPath(WORKER));
            List<String> names = PomRuntimeClasspath.resolve(shelved).stream()
                    .map(p -> p.getFileName().toString())
                    .toList();
            assertThat(names).containsExactly("jk-foo-1.0.jar", "lib-1.0.jar");
        } finally {
            PomRuntimeClasspath.clearResolveCacheForTests();
            if (prev != null) System.setProperty("jk.env.JK_STORE_DIR", prev);
            else System.clearProperty("jk.env.JK_STORE_DIR");
        }
    }

    private static void shelveWorker(Path store) throws Exception {
        put(store, RepoArtifactResolver.JK_LOCAL, MavenLayout.artifactPath(WORKER), "worker-bytes");
        put(store, RepoArtifactResolver.JK_LOCAL, MavenLayout.pomPath(WORKER), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId><artifactId>jk-foo</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
    }

    private static Path put(Path store, String repo, String rel, String content) throws Exception {
        Path f = store.resolve("repos").resolve(repo).resolve(rel);
        Files.createDirectories(f.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(f, bytes);
        RepoArtifactStore.forStoreId(store, repo).writeMemo(rel, f, Hashing.sha256Hex(bytes));
        return f;
    }
}
