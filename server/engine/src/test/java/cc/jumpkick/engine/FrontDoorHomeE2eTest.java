// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.http.BandPort;
import cc.jumpkick.host.Guest;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Port 8910 is the person's own home's. An engine of any other home (a test, scratch or gate
 * {@code JK_HOME}) that would bind it serves on a band port instead, and everything its own clients
 * use (the URL file, the status the socket answers, the MCP URL derived from it, the token) names
 * that port. The default home's engine, finding 8910 held by an engine, says which home holds it;
 * held by another program, it serves on a band port and says why. Each test puts a
 * free port in 8910's place, so the real front door, and the engine of whoever runs the suite, is
 * never touched.
 */
@Tag("integration")
class FrontDoorHomeE2eTest extends EngineServerHarness {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, LOOPBACK)) {
            return s.getLocalPort();
        }
    }

    private static JkHttpConfig httpOn(int port, Path webRoot) {
        return new JkHttpConfig("127.0.0.1", port, 16, 16, webRoot.toString(), JkHttpConfig.Mcp.DEFAULTS);
    }

    private EngineServer engine(EnginePaths.Paths p, int frontDoor, boolean ownHome) throws IOException {
        EngineServer server = new EngineServer(
                p, JkEngineConfig.DEFAULTS, httpOn(frontDoor, shortTempDir()), JkVersion.VERSION, null);
        server.httpFront().seams(() -> Guest.NONE, Map.<String, String>of()::get, paired -> {});
        server.httpFront().homeSeams(ownHome, frontDoor);
        return server;
    }

    @Test
    void an_engine_of_another_home_leaves_the_front_door_port_and_its_clients_find_it_by_its_home() throws Exception {
        int frontDoor = freePort();
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = engine(p, frontDoor, false);
        Thread serverThread = runInBackground(server);
        try {
            waitUntil(Duration.ofSeconds(15), () -> hasContent(p.http()));
            String url = Files.readString(p.http()).strip();
            assertThat(URI.create(url).getPort())
                    .as("a band port, not the front door's")
                    .isBetween(BandPort.FIRST, BandPort.LAST);
            try (ServerSocket defaultHomes = new ServerSocket(frontDoor, 1, LOOPBACK)) {
                assertThat(defaultHomes.getLocalPort())
                        .as("the default home's engine can bind it")
                        .isEqualTo(frontDoor);
            }
            assertThat(Files.readString(p.dir().resolve(BandPort.FILE)).strip())
                    .as("kept for the next start, so the origin stays")
                    .isEqualTo(Integer.toString(URI.create(url).getPort()));

            // jk engine status, jk web and jk mcp read the engine's URLs from its own home's socket.
            String mcpUrl;
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                String ack = c.send(ProtoLifecycle.statusRequest());
                assertThat(Jsonl.str(ack, "httpUrl")).isEqualTo(url);
                assertThat(Jsonl.str(ack, "httpError")).isNull();
                mcpUrl = Jsonl.str(ack, "mcpUrl");
            }
            assertThat(mcpUrl).isEqualTo(url.replaceAll("/+$", "") + "/mcp");

            String token = Files.readString(p.httpToken()).strip();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> status = http.send(
                    HttpRequest.newBuilder(URI.create(url + "api/status"))
                            .header("Authorization", "Bearer " + token)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode())
                    .as("the dashboard's API, with the home's token")
                    .isEqualTo(200);
            HttpResponse<String> hello = http.send(
                    HttpRequest.newBuilder(URI.create(mcpUrl))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                                            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                                            + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"1\"}}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(hello.statusCode())
                    .as("MCP at the URL status gives: " + hello.body())
                    .isEqualTo(200);
        } finally {
            server.close();
            serverThread.join(10_000);
        }
    }

    @Test
    void the_default_homes_engine_serves_on_a_band_port_when_another_program_holds_the_front_door_port()
            throws Exception {
        // A program that is no jk engine: it holds the port and answers nothing.
        try (ServerSocket other = new ServerSocket(0, 1, LOOPBACK)) {
            int frontDoor = other.getLocalPort();
            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = engine(p, frontDoor, true);
            Thread serverThread = runInBackground(server);
            try {
                waitUntil(Duration.ofSeconds(30), () -> hasContent(p.http()));
                String url = Files.readString(p.http()).strip();
                int port = URI.create(url).getPort();
                assertThat(port).as("a band port").isBetween(BandPort.FIRST, BandPort.LAST);
                assertThat(Files.readString(p.dir().resolve(BandPort.FILE)).strip())
                        .as("kept for the next start")
                        .isEqualTo(Integer.toString(port));
                String mcpUrl;
                try (Client c = new Client(EnginePaths.activeSocket(p))) {
                    String ack = c.send(ProtoLifecycle.statusRequest());
                    assertThat(Jsonl.str(ack, "httpUrl")).isEqualTo(url);
                    assertThat(Jsonl.str(ack, "httpError"))
                            .as("why jk engine status shows a band port")
                            .isEqualTo("port " + frontDoor + " is held by another program");
                    mcpUrl = Jsonl.str(ack, "mcpUrl");
                }
                String token = Files.readString(p.httpToken()).strip();
                HttpResponse<String> hello = HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(String.valueOf(mcpUrl)))
                                        .header("Authorization", "Bearer " + token)
                                        .header("Content-Type", "application/json")
                                        .header("Accept", "application/json, text/event-stream")
                                        .POST(HttpRequest.BodyPublishers.ofString(
                                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                                                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                                                        + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"1\"}}}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertThat(hello.statusCode())
                        .as("MCP at the URL status gives: " + hello.body())
                        .isEqualTo(200);
            } finally {
                server.close();
                serverThread.join(10_000);
            }
        }
    }

    @Test
    void the_default_homes_engine_says_which_home_holds_the_front_door_port() throws Exception {
        // An engine of another home from a jk older than the rule, still holding 8910.
        HttpServer older = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        int frontDoor = older.getAddress().getPort();
        older.createContext("/api/identity", ex -> {
            byte[] body = ("{\"id\":\"0be7a1e5c0de\",\"role\":\"host\",\"jkHome\":\"/var/tmp/scratch/.jk\",\"port\":"
                            + frontDoor + "}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        older.start();
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = engine(p, frontDoor, true);
        Thread serverThread = runInBackground(server);
        try {
            waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)));
            AtomicReference<@Nullable String> error = new AtomicReference<>();
            waitUntil(Duration.ofSeconds(30), () -> {
                try (Client c = new Client(EnginePaths.activeSocket(p))) {
                    error.set(Jsonl.str(c.send(ProtoLifecycle.statusRequest()), "httpError"));
                } catch (IOException notYet) {
                    return false;
                }
                String e = error.get();
                return e != null && !e.endsWith("retrying");
            });
            assertThat(error.get())
                    .as("what jk engine status shows on its Web UI line")
                    .contains("port " + frontDoor + " is held by the jk engine of home /var/tmp/scratch/.jk")
                    .contains("JK_HOME");
            assertThat(Files.exists(p.http())).as("nothing served elsewhere").isFalse();
        } finally {
            server.close();
            serverThread.join(10_000);
            older.stop(0);
        }
    }
}
