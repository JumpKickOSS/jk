// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Advisory detail fetches against a server that answers many at once: the audit keeps a bounded
 * number in flight, and one failure stops the fetches not yet started.
 */
class OsvAuditorFetchBoundTest {

    private static final int IDS = 32;

    private HttpServer server;
    private ExecutorService handlers;
    private URI base;
    private volatile String batch = "";
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();
    private final AtomicInteger fetched = new AtomicInteger();

    /** When set, a detail request is answered only once this opens, not after a fixed pause. */
    private volatile @Nullable CountDownLatch hold;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newFixedThreadPool(IDS + 4);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            byte[] body;
            if ("GET".equals(exchange.getRequestMethod())) {
                fetched.incrementAndGet();
                mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    CountDownLatch held = hold;
                    if (held == null) Thread.sleep(100);
                    else held.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                String path = exchange.getRequestURI().getPath();
                String id = path.substring(path.lastIndexOf('/') + 1);
                body = ("{\"id\":\"" + id + "\",\"summary\":\"s\",\"database_specific\":{\"severity\":\"LOW\"}}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getRequestBody().readAllBytes();
                body = batch.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void detail_fetches_run_in_parallel_but_never_more_than_the_bound_at_once() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < IDS; i++) ids.add("GHSA-" + i);
        batch = batchOf(ids);

        var report = new OsvAuditor(client()).audit(lockOf());

        assertThat(report.findings()).hasSize(IDS);
        assertThat(fetched).hasValue(IDS);
        assertThat(mostInFlight.get()).isGreaterThan(1).isLessThanOrEqualTo(OsvAuditor.MAX_FETCHES);
    }

    @Test
    void a_failed_fetch_stops_the_fetches_not_yet_started() throws Exception {
        List<String> ids = new ArrayList<>();
        ids.add("not an id"); // refused before any request goes out
        for (int i = 1; i < IDS; i++) ids.add("GHSA-" + i);
        batch = batchOf(ids);

        // The fetches that did start stay unanswered until the audit has failed, so no slot comes
        // free for a waiting fetch before the refusal is recorded, however slowly this host runs.
        CountDownLatch held = new CountDownLatch(1);
        hold = held;
        try {
            assertThatThrownBy(() -> new OsvAuditor(client()).audit(lockOf()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("malformed");
        } finally {
            held.countDown();
        }
        // Long enough for every fetch the audit left running to have reached the server.
        Thread.sleep(500);
        assertThat(fetched.get()).isLessThanOrEqualTo(OsvAuditor.MAX_FETCHES);
    }

    private static String batchOf(List<String> ids) {
        StringBuilder sb = new StringBuilder("{\"results\":[{\"vulns\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(ids.get(i)).append("\"}");
        }
        return sb.append("]}]}").toString();
    }

    private static Lockfile lockOf() {
        return new Lockfile(
                5,
                "jk test",
                "pubgrub-v1",
                List.of(new Lockfile.Artifact(
                        "g:a:jar:", "1.0", "central+https://...", "sha256:abc", null, List.of(Scope.MAIN), List.of())));
    }

    private OsvClient client() {
        return new OsvClient(base.resolve("/v1/querybatch"), base.resolve("/v1/vulns/"));
    }
}
