// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The checksum that confirms a Maven-local candidate is read the way a download's is: the {@code
 * .sha256} and {@code .sha1} sidecars in flight together, the strongest published one deciding.
 */
class M2AdoptionSidecarsTest {

    private static final String REL = "com/example/widget/1.0/widget-1.0.jar";
    private static final byte[] REAL = "the genuine artifact bytes".getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    final LoopbackHttp repo = new LoopbackHttp().concurrent().withoutChecksums();

    @AfterEach
    void reset() {
        System.clearProperty("jk.m2.local");
        SessionContext.reset();
    }

    @Test
    void both_sha_sidecars_are_in_flight_at_once(@TempDir Path tmp) throws Exception {
        seedM2(tmp.resolve("m2"), REAL);
        // No .sha256 published; the .sha1 vouches. The miss is held open until the .sha1 request
        // has arrived, so two sequential reads never get to the .sha1 in time.
        repo.served().put("/" + REL + ".sha1", Hashing.hashHex("SHA-1", REAL).getBytes(StandardCharsets.UTF_8));
        CountDownLatch sha1Asked = new CountDownLatch(1);
        AtomicBoolean sha1ArrivedDuringSha256Miss = new AtomicBoolean();
        repo.beforeServe(path -> {
            if (path.endsWith(".sha1")) sha1Asked.countDown();
        });
        repo.beforeMiss(path -> {
            if (!path.endsWith(".sha256")) return;
            try {
                sha1ArrivedDuringSha256Miss.set(sha1Asked.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        MavenRepo mavenRepo = new MavenRepo("test", repo.base(), new Http(), new Cas(tmp.resolve("store")));

        MavenRepo.Fetched fetched = mavenRepo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(REAL));
        assertThat(repo.requested())
                .as("the candidate was adopted, not downloaded")
                .doesNotContain("/" + REL);
        assertThat(sha1ArrivedDuringSha256Miss)
                .as("the .sha1 was asked for while the .sha256 was still outstanding")
                .isTrue();
    }

    private static void seedM2(Path m2, byte[] bytes) throws Exception {
        System.setProperty("jk.m2.local", m2.toString());
        Path p = m2.resolve(REL);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
    }
}
