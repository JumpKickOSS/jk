// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.api.HttpLive;
import java.net.BindException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Bind and admission: a held port, a non-loopback bind, and the three independent budgets — RPC,
 * dashboard SSE and MCP SSE — proving a saturated one never starves the others and that a parked
 * MCP wait yields its permit. Fixture in {@link HttpEngineServerHarness}.
 */
@Tag("integration")
class HttpAdmissionTest extends HttpEngineServerHarness {

    @Test
    void bind_on_a_held_port_fails_without_hanging() {
        // `server` (from @BeforeEach) already holds an OS-assigned loopback port. A second server
        // configured for that exact port must lose the bind — and, thanks to the bounded retry that
        // rides out a draining predecessor, give up promptly (a handful of attempts) rather than
        // spin forever. The generous timeout only guards against a regression to an unbounded loop.
        HttpEngineServer collider = new HttpEngineServer(
                httpConfig("127.0.0.1", port, 16),
                webRoot,
                tokenFile,
                logFile,
                "9.9.9-test",
                () -> SNAPSHOT,
                events,
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(collider::start)
                    .isInstanceOf(BindException.class));
        } finally {
            collider.close();
        }
    }

    @Test
    void non_loopback_bind_gates_api_reads_but_not_static() throws Exception {
        JkHttpConfig config = httpConfig("0.0.0.0", 0, 16);
        Path lanTokenFile = stateDir.resolve("lan.http-token");
        HttpEngineServer lan = new HttpEngineServer(
                config,
                webRoot,
                lanTokenFile,
                stateDir.resolve("lan.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            lan.start();
            String lanUrl = lan.url(); // advertises the always-valid loopback form for a wildcard bind
            String lanToken = Files.readString(lanTokenFile).trim();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);

            HttpResponse<String> authorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/status"))
                            .header("Authorization", "Bearer " + lanToken)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(authorized.statusCode()).isEqualTo(200);

            HttpResponse<String> staticContent = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "hello.txt")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(staticContent.statusCode()).isEqualTo(200); // the shell must load by plain navigation
        } finally {
            lan.close();
        }
    }

    @Test
    void saturated_admission_gate_yields_503_with_retry_after() throws Exception {
        int permits = server.admission().rpc().drainPermits();
        try {
            HttpResponse<String> resp = get("/hello.txt");
            assertThat(resp.statusCode()).isEqualTo(503);
            assertThat(resp.headers().firstValue("Retry-After")).contains("1");
        } finally {
            server.admission().rpc().release(permits);
        }
        assertThat(get("/hello.txt").statusCode()).isEqualTo(200);
    }

    @Test
    void open_sse_streams_do_not_starve_rpc_admission() throws Exception {
        // A tiny RPC budget: if streams drew from it, three open streams would 503 everything else.
        HttpEngineServer tiny = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 2),
                webRoot,
                stateDir.resolve("tiny.http-token"),
                stateDir.resolve("tiny.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        var streams = new ArrayList<HttpResponse<Stream<String>>>();
        try {
            tiny.start();
            String url = tiny.url();
            String tok = Files.readString(stateDir.resolve("tiny.http-token")).trim();
            for (int i = 0; i < 3; i++) { // more streams than the whole RPC budget
                HttpResponse<Stream<String>> resp = client.send(
                        HttpRequest.newBuilder(URI.create(url + "api/events?access_token=" + tok))
                                .build(),
                        HttpResponse.BodyHandlers.ofLines());
                assertThat(resp.statusCode()).isEqualTo(200);
                var lines = resp.body().iterator();
                assertThat(nextLine(lines)).isEqualTo(": connected"); // handler is inside its stream loop
                streams.add(resp);
            }
            HttpResponse<String> rpc = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/status"))
                            .header("Authorization", "Bearer " + tok)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(rpc.statusCode()).isEqualTo(200); // RPC admission untouched by the streams
        } finally {
            streams.forEach(r -> r.body().close());
            tiny.close();
        }
    }

    @Test
    void mcp_query_token_only_authorizes_the_sse_get() throws Exception {
        String tok = token();
        // A mutation authorized by a URL token would land in shell history and proxy logs.
        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp?access_token=" + tok))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(401);
        // Bearer-only everywhere except the SSE GET — discovery included.
        HttpResponse<String> discovery = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp?access_token=" + tok))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(discovery.statusCode()).isEqualTo(401);
        // The SSE GET keeps the query form: EventSource cannot set headers.
        HttpResponse<Stream<String>> sse = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "mcp?access_token=" + tok))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
        try {
            assertThat(sse.statusCode()).isEqualTo(200);
            assertThat(nextLine(sse.body().iterator())).isEqualTo(": mcp-events connected");
        } finally {
            sse.body().close();
        }
    }

    @Test
    void mcp_wait_parks_without_holding_the_only_admission_permit() throws Exception {
        // RPC budget of 1: a parked jk_job wait held the permit, so every other
        // request (including the jk_cancel that could un-wedge it) 503'd until timeout.
        HttpEngineServer tiny = new HttpEngineServer(
                httpConfig("127.0.0.1", 0, 1),
                webRoot,
                stateDir.resolve("wait.http-token"),
                stateDir.resolve("wait.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                stubJobs,
                testJournal(),
                List::of,
                () -> EMPTY_CACHE,
                null);
        AtomicBoolean live = new AtomicBoolean(true);
        AtomicInteger livePolls = new AtomicInteger();
        HttpLive.Run run =
                new HttpLive.Run(7L, 1L, "build", "/tmp/x", "c", 1L, 0L, 50.0, "j-1", 0, 0, 1, 2, List.of(), List.of());
        tiny.setLiveRunSupport(
                () -> {
                    livePolls.incrementAndGet();
                    return live.get() ? List.of(run) : List.of();
                },
                null);
        try {
            tiny.start();
            String url = tiny.url();
            String tok = Files.readString(stateDir.resolve("wait.http-token")).trim();
            CompletableFuture<HttpResponse<String>> parked = CompletableFuture.supplyAsync(() -> {
                try {
                    return client.send(
                            HttpRequest.newBuilder(URI.create(url + "mcp"))
                                    .header("Authorization", "Bearer " + tok)
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                                                            + "{\"name\":\"jk_job\",\"arguments\":{\"action\":\"wait\",\"jid\":7,\"timeout_s\":30}}}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // Wait until the request is provably parked inside the wait loop (it polls the live
            // view) with the only permit back in the semaphore — then probe. Probing earlier
            // would race the parked request's own pre-park admission on the budget of one.
            long deadline = System.currentTimeMillis() + 5_000;
            while ((livePolls.get() < 2 || tiny.admission().rpc().availablePermits() < 1)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(livePolls.get()).isGreaterThanOrEqualTo(2); // inside waitUntilGone
            assertThat(tiny.admission().rpc().availablePermits()).isEqualTo(1); // permit yielded
            HttpResponse<String> probe = client.send(
                    HttpRequest.newBuilder(URI.create(url + "api/status"))
                            .header("Authorization", "Bearer " + tok)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(probe.statusCode()).isEqualTo(200); // surface alive while the wait parks
            live.set(false); // job "finishes"; the parked wait completes and reacquires
            HttpResponse<String> done = parked.get(10, TimeUnit.SECONDS);
            assertThat(done.statusCode()).isEqualTo(200);
            assertThat(done.body()).contains("\"finished\":true");
        } finally {
            tiny.close();
        }
    }

    @Test
    void sse_beyond_its_own_cap_is_503_without_touching_rpc_admission() throws Exception {
        int drained = server.admission().webSse().drainPermits();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                HttpResponse<String> resp = get("/api/events");
                assertThat(resp.statusCode()).isEqualTo(503);
                assertThat(resp.body()).contains("too many event streams");
                assertThat(resp.headers().firstValue("Retry-After")).contains("1");
            });
            assertThat(get("/api/status").statusCode()).isEqualTo(200); // RPC budget unaffected
        } finally {
            server.admission().webSse().release(drained);
        }
    }

    @Test
    void exhausted_web_sse_budget_leaves_mcp_streams_connectable() throws Exception {
        int drained = server.admission().webSse().drainPermits();
        try {
            assertThat(get("/api/events").statusCode()).isEqualTo(503);
            HttpResponse<Stream<String>> mcpStream = openMcpEvents();
            try {
                assertThat(mcpStream.statusCode()).isEqualTo(200); // separate budget
                assertThat(nextLine(mcpStream.body().iterator())).isEqualTo(": mcp-events connected");
            } finally {
                mcpStream.body().close();
            }
        } finally {
            server.admission().webSse().release(drained);
        }
    }

    @Test
    void exhausted_mcp_sse_budget_leaves_web_streams_connectable() throws Exception {
        int drained = server.admission().mcpSse().drainPermits();
        try {
            HttpResponse<Stream<String>> rejected = openMcpEvents();
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(String.join("\n", rejected.body().toList())).contains("too many MCP event streams");

            var lines = openEvents(""); // web budget untouched
            assertThat(nextLine(lines)).isEqualTo(": connected");
        } finally {
            server.admission().mcpSse().release(drained);
        }
    }

    @Test
    void rpc_saturation_does_not_block_event_streams() throws Exception {
        int permits = server.admission().rpc().drainPermits();
        try {
            var lines = openEvents("");
            assertThat(nextLine(lines)).isEqualTo(": connected");
        } finally {
            server.admission().rpc().release(permits);
        }
    }
}
