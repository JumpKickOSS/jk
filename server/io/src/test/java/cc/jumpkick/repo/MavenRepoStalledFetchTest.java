// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.InFlightRequests;
import cc.jumpkick.model.Coordinate;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A fetch whose repository accepts every request and answers none: the body read is interrupted
 * (a stall watch, a cancel), and the two checksum reads that started beside it end with it instead
 * of parking on the repository for the whole request timeout.
 */
class MavenRepoStalledFetchTest {

    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    private HttpServer server;
    private URI base;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch arrived = new CountDownLatch(3);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            arrived.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        SessionContext.reset();
    }

    @Test
    @Timeout(30)
    void an_interrupted_body_fetch_ends_its_checksum_reads_with_it(@TempDir Path tempDir) throws Exception {
        MavenRepo repo = new MavenRepo("never", base, new Http(), new Cas(tempDir));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread fetching = new Thread(() -> {
            try {
                repo.fetchPom(Coordinate.of("com.acme.never", "never-parent", "1.0"));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        fetching.start();
        assertThat(arrived.await(20, TimeUnit.SECONDS))
                .as("the body and both SHA sidecars were asked for")
                .isTrue();
        assertThat(InFlightRequests.waitingOn()).contains("never-parent-1.0.pom.sha256", "never-parent-1.0.pom.sha1");

        fetching.interrupt();
        fetching.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(fetching.isAlive()).as("the fetch left the read at once").isFalse();
        assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!InFlightRequests.waitingOn().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(InFlightRequests.waitingOn())
                .as("no sidecar read is still parked on the repository")
                .isEmpty();
    }
}
