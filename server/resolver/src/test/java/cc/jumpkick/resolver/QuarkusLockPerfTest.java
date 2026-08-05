// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quarkus platform + rest/arc must lock under the default engine budget (≪ 2 minutes).
 * Requires network (Maven Central); skipped offline. Warm metadata lives in the developer's
 * artifact store (not the action-cache tier, and not hermetic {@code JK_HOME} from Gradle).
 */
@Tag("network")
@Tag("slow")
class QuarkusLockPerfTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quarkus_rest_arc_locks_under_30s(@TempDir Path tmp) throws Exception {
        assumeTrue(networkOk(), "Maven Central unreachable");
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                name = "quarkus-hello"
                group = "com.example.qhello"
                version = "0.1.0"
                jdk = "25"
                java = 25

                [quarkus]
                version = "3.38.0"

                [dependencies]
                quarkus-rest = { group = "io.quarkus", name = "quarkus-rest" }
                quarkus-arc  = { group = "io.quarkus", name = "quarkus-arc" }
                """);

        JkBuild project = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(project.dependencies().of(Scope.PLATFORM)).isNotEmpty();

        Path store = developerStore();
        assumeTrue(Files.isDirectory(store), "local jk store helps warm metadata");
        Cas cas = new Cas(store);
        MavenRepo central = new MavenRepo("central", URI.create("https://repo1.maven.org/maven2/"), new Http(), cas);
        long t0 = System.nanoTime();
        Lockfile lock =
                new LockOrchestrator(RepoGroup.of(central)).withProjectDir(tmp).lock(project, "0.11.0-test");
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println(
                "quarkus-rest lock ms=" + ms + " packages=" + lock.artifacts().size());
        assertThat(lock.artifacts()).isNotEmpty();
        assertThat(ms).as("lock wall time %d ms", ms).isLessThan(30_000L);
    }

    /**
     * Platform / XDG artifact store for the real user home, ignoring hermetic
     * {@code JK_HOME}/{@code JK_STORE_DIR} that Gradle test conventions inject.
     */
    static Path developerStore() {
        Function<String, String> env = k -> switch (k) {
            case "JK_HOME", "JK_STORE_DIR", "JK_DATA_DIR", "JK_CACHE_DIR" -> null;
            default -> System.getenv(k);
        };
        return JkDirs.of(env, System.getProperty("user.home")).storeDir();
    }

    private static boolean networkOk() {
        try {
            var c = (java.net.HttpURLConnection)
                    URI.create("https://repo1.maven.org/maven2/").toURL().openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("HEAD");
            return c.getResponseCode() >= 200 && c.getResponseCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
