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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Quarkus platform + rest/arc must lock under the default engine budget (≪ 2 minutes).
 * Requires network (Maven Central); skipped offline.
 */
@Tag("network")
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
                version = "3.28.5"

                [dependencies]
                quarkus-rest = { group = "io.quarkus", name = "quarkus-rest" }
                quarkus-arc  = { group = "io.quarkus", name = "quarkus-arc" }
                """);

        JkBuild project = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(project.dependencies().of(Scope.PLATFORM)).isNotEmpty();

        Path cache = Path.of(System.getProperty("user.home"), ".jk/cache");
        assumeTrue(Files.isDirectory(cache), "local jk cache helps warm metadata");
        Cas cas = new Cas(cache);
        MavenRepo central = new MavenRepo("central", URI.create("https://repo1.maven.org/maven2/"), new Http(), cas);
        long t0 = System.nanoTime();
        Lockfile lock =
                new LockOrchestrator(RepoGroup.of(central)).withProjectDir(tmp).lock(project, "0.10.1-test");
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println(
                "quarkus-rest lock ms=" + ms + " packages=" + lock.artifacts().size());
        assertThat(lock.artifacts()).isNotEmpty();
        assertThat(ms).as("lock wall time %d ms", ms).isLessThan(30_000L);
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
