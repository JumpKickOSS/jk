// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.HostRateLimiter;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sidecar read counts against the host cap like any other request, and a download leg never
 * waits for one while it holds the host's permit itself.
 */
class ChecksumSidecarsPermitTest {

    private static final String REL = "com/example/widget/1.0/widget-1.0.jar";
    private static final byte[] REAL = "the artifact bytes".getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    final LoopbackHttp repo = new LoopbackHttp().concurrent();

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_sidecar_read_waits_for_the_host_permit() throws Exception {
        repo.served().put("/" + REL, REAL);
        HostRateLimiter limiter = HostRateLimiter.shared();
        String host = repo.base().getHost();
        int permits = limiter.permitsFor(host);
        CountDownLatch held = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holders = Executors.newFixedThreadPool(permits);
        try {
            for (int i = 0; i < permits; i++) {
                holders.submit(() -> limiter.run(host, () -> {
                    held.countDown();
                    release.await();
                    return true;
                }));
            }
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            ChecksumSidecars sidecars = ChecksumSidecars.start(
                    new HttpTransport(new Http()), RepoCredential.ANONYMOUS, URI.create(repo.baseUrl() + REL));
            Thread.sleep(300);
            assertThat(repo.requested())
                    .as("no sidecar request while every permit of the host is held")
                    .isEmpty();

            release.countDown();
            assertThat(sidecars.strongestSha())
                    .contains(new ChecksumSidecars.Published(
                            ChecksumSidecars.Algorithm.SHA1, Hashing.hashHex("SHA-1", REAL)));
            assertThat(repo.requested()).contains("/" + REL + ".sha1");
        } finally {
            release.countDown();
            holders.shutdownNow();
        }
    }

    @Test
    void legs_holding_every_permit_still_finish_their_sidecar_reads(@TempDir Path tmp) throws Exception {
        int permits = HostRateLimiter.shared().permitsFor(repo.base().getHost());
        int fetches = permits + 2;
        List<Coordinate> coords = new ArrayList<>();
        for (int i = 0; i < fetches; i++) {
            Coordinate coord = Coordinate.of("com.example", "w" + i, "1.0");
            coords.add(coord);
            repo.served()
                    .put("/" + MavenLayout.artifactPath(coord), ("bytes of w" + i).getBytes(StandardCharsets.UTF_8));
        }
        // No body is answered until a leg per permit is in flight, so every permit is held by a leg
        // whose sidecar reads still need one.
        CountDownLatch inFlight = new CountDownLatch(permits);
        repo.beforeServe(path -> {
            if (!path.endsWith(".jar")) return;
            inFlight.countDown();
            try {
                inFlight.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        MavenRepo mavenRepo = new MavenRepo("test", repo.base(), new Http(), new Cas(tmp.resolve("store")));
        ExecutorService pool = Executors.newFixedThreadPool(fetches);
        try {
            List<Future<MavenRepo.Fetched>> fetched = new ArrayList<>();
            for (Coordinate coord : coords) fetched.add(pool.submit(() -> mavenRepo.fetchArtifact(coord)));
            for (int i = 0; i < fetches; i++) {
                MavenRepo.Fetched f = fetched.get(i).get(30, TimeUnit.SECONDS);
                assertThat(f.sha256())
                        .isEqualTo(Hashing.sha256Hex(("bytes of w" + i).getBytes(StandardCharsets.UTF_8)));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
