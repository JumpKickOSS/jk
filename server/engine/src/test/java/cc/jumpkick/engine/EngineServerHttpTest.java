// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The engine's embedded HTTP server (docs/http.md) as the socket sees it: the URL file and token
 * it writes, an advisory bind failure, and a build triggered over REST streaming its lifecycle
 * back over SSE. A real-HTTP contract test — the fixture lives in {@link EngineServerHarness}.
 */
@Tag("integration")
class EngineServerHttpTest extends EngineServerHarness {

    @Test
    void http_enabled_serves_writes_url_file_and_reports_in_status() throws Exception {
        Path stateDir = shortTempDir();
        EnginePaths.Paths p = paths(stateDir);
        Path web = Files.createDirectories(stateDir.resolve("web"));
        Files.writeString(web.resolve("hello.txt"), "hi from the engine");

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, httpOnEphemeralPort(web), "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> hasContent(p.http()));
        String url = Files.readString(p.http());
        assertThat(url).startsWith("http://127.0.0.1:").endsWith("/");

        var httpClient = HttpClient.newHttpClient();
        var response = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "hello.txt")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hi from the engine");

        String token = Files.readString(p.httpToken()).trim();
        assertThat(token).isNotEmpty(); // minted alongside the URL file

        // The REST surface serves the same vitals the socket status-ack carries. /api/* is
        // token-gated even on loopback, so the token is not optional here.
        var apiStatus = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "api/status"))
                        .header("Authorization", "Bearer " + token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(apiStatus.statusCode()).isEqualTo(200);
        assertThat(apiStatus.body()).contains("\"version\":\"1.0\"").contains("\"httpUrl\":\"" + url + "\"");

        var unauthenticated = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "api/status")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode())
                .as("/api/* fails closed without a token")
                .isEqualTo(401);

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String ack = c.send(ProtoLifecycle.statusRequest());
            assertThat(Jsonl.str(ack, "httpUrl")).isEqualTo(url);
            assertThat(Jsonl.str(ack, "httpError")).isNull();
            assertThat(c.send(ProtoLifecycle.shutdown())).isNotNull(); // bye
        }
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(Files.exists(p.http())).isFalse(); // cleaned up with the other engine files
        // The token deliberately survives shutdown so an open dashboard tab stays valid across a
        // restart (docs/http.md); only `jk engine rotate-token` removes it.
        assertThat(Files.exists(p.httpToken())).isTrue();
    }

    @Test
    void http_bind_failure_is_advisory_not_fatal() throws Exception {
        Path stateDir = shortTempDir();
        EnginePaths.Paths p = paths(stateDir);
        try (var blocker = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var http = new JkHttpConfig(
                    "127.0.0.1",
                    blocker.getLocalPort(),
                    16,
                    16,
                    stateDir.resolve("web").toString(),
                    JkHttpConfig.Mcp.DEFAULTS);
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, http, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                // The engine's primary role is unharmed...
                assertThat(EngineProtocol.typeOf(c.send(ProtoLifecycle.ping()))).isEqualTo(EngineProtocol.PONG);
                // ...and status reports the bind failure instead of a URL.
                String ack = c.send(ProtoLifecycle.statusRequest());
                assertThat(Jsonl.str(ack, "httpUrl")).isNull();
                assertThat(Jsonl.str(ack, "httpError")).isNotEmpty();
            }
            assertThat(Files.exists(p.http())).isFalse(); // no URL file for a server that isn't up
            server.close();
            serverThread.join(5_000);
        }
    }

    @Test
    void http_build_trigger_streams_lifecycle_events_over_sse() throws Exception {
        Path stateDir = shortTempDir();
        EnginePaths.Paths p = paths(stateDir);
        EngineServer server =
                new EngineServer(p, JkEngineConfig.DEFAULTS, httpOnEphemeralPort(stateDir.resolve("web")), "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> hasContent(p.http()) && hasContent(p.httpToken()));
        String url = Files.readString(p.http());
        String token = Files.readString(p.httpToken()).trim();
        var httpClient = HttpClient.newHttpClient();

        // Subscribe to the event stream first, so the request events can't race past us. The token
        // rides the query string, which is the only way EventSource can carry it — and the only
        // path for which the server accepts it there.
        var sse = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "api/events?access_token=" + token))
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(sse.statusCode()).isEqualTo(200);
        var lines = sse.body().iterator();

        // Anything past bootstrap carries the engine generation, so a dashboard left open across a
        // restart gets a 409 instead of driving the wrong engine. GET /api/status is bootstrap and
        // hands it over.
        var status = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "api/status"))
                        .header("Authorization", "Bearer " + token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(status.statusCode()).isEqualTo(200);
        String epoch = jsonString(status.body(), "engineEpoch");
        assertThat(epoch).isNotBlank();

        // A dir whose jk.toml exists but won't parse: the trigger accepts it (202), the build fails
        // fast and deterministically, and both lifecycle events flow — exactly the plumbing under test.
        Path project = Files.createDirectories(stateDir.resolve("broken-project"));
        Files.writeString(project.resolve("jk.toml"), "this is [not] valid = toml =");

        var staleEpoch = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "api/build"))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Jk-Engine-Epoch", "from-a-previous-engine")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"dir\":" + Jsonl.quote(project.toString()) + "}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(staleEpoch.statusCode())
                .as("a request from a previous engine generation is refused")
                .isEqualTo(409);

        var rejected = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "api/build"))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Jk-Engine-Epoch", epoch)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"dir\":"
                                + Jsonl.quote(
                                        stateDir.resolve("no-such-project").toString()) + "}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(400); // validation runs before any thread forks

        var accepted = httpClient.send(
                HttpRequest.newBuilder(URI.create(url + "api/build"))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Jk-Engine-Epoch", epoch)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"dir\":" + Jsonl.quote(project.toString()) + "}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.body()).contains("\"jid\":");

        String startData = awaitSseData(lines, "request-start");
        assertThat(startData).contains("\"kind\":\"build\"").contains(Jsonl.quote(project.toString()));
        String finishData = awaitSseData(lines, "request-finish");
        assertThat(finishData).contains("\"success\":false").contains("\"millis\":");

        server.close();
        serverThread.join(5_000);
    }
}
