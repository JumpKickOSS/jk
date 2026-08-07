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
 * Quarkus platform + rest/arc must <em>resolve</em> fast. Requires network (Maven Central);
 * skipped offline.
 *
 * <p>The budget is asserted on a <strong>second</strong> lock, after a first one has warmed
 * metadata into the developer's artifact store. The first lock's wall time is dominated by
 * hundreds of Central round trips, so timing it measures the network, not PubGrub — and on a cold
 * or partial store it blew the old 30s deadline and reported a resolve regression that was not
 * one (JK-1597).
 */
@Tag("network")
@Tag("slow")
class QuarkusLockPerfTest {

    /** Resolve budget for a warm store. Cold fetching is deliberately outside the assertion. */
    private static final long WARM_BUDGET_MS = 5_000L;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void quarkus_rest_arc_resolves_under_the_warm_budget(@TempDir Path tmp) throws Exception {
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

        // Warm: whatever metadata this store is missing is fetched here, untimed.
        long coldT0 = System.nanoTime();
        Lockfile warmUp = lock(central, tmp, project);
        long coldMs = (System.nanoTime() - coldT0) / 1_000_000L;

        long t0 = System.nanoTime();
        Lockfile lock = lock(central, tmp, project);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("quarkus-rest lock: cold=" + coldMs + "ms warm=" + ms + "ms packages="
                + lock.artifacts().size());
        assertThat(lock.artifacts()).isNotEmpty();
        // Resolution shape, not just wall time: a lost dedup or a duplicated root shows up here
        // even on a machine too slow or too loaded for the timing assertion to mean much.
        assertThat(lock.artifacts()).hasSameSizeAs(warmUp.artifacts());
        assertThat(ms).as("warm resolve wall time %d ms", ms).isLessThan(WARM_BUDGET_MS);
    }

    private static Lockfile lock(MavenRepo central, Path dir, JkBuild project) throws Exception {
        return new LockOrchestrator(RepoGroup.of(central)).withProjectDir(dir).lock(project, "0.11.0-test");
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
