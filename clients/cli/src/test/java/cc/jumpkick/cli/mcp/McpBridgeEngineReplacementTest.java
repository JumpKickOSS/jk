// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.IsolatedState;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.EngineServer;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The real {@code jk mcp} bridge against real engines: it opens its session on one, a rebuilt
 * engine takes the endpoint over, and the bridge's next call lands on the successor under the same
 * {@code Mcp-Session-Id}. The successor answers it from the connection the first engine stored, so
 * the agent keeps its session and its episode, and the bridge never repeats {@code initialize}.
 */
@Tag("integration")
@IsolatedState
class McpBridgeEngineReplacementTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkm-");

    private record Engine(EngineServer server, Thread thread) {}

    private final List<Engine> engines = new ArrayList<>();

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stopAndJoinEveryEngineStarted() throws InterruptedException {
        for (Engine e : engines) {
            e.server().close();
            e.thread().join(WAIT.toMillis());
        }
        engines.clear();
        JvmOptions.resetSharedPlanForTests();
        http.close();
    }

    @Test
    void the_bridge_keeps_its_session_and_episode_across_an_engine_replacement() throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        Path web = tempDirs.create();
        Path project = tempDirs.create();
        Engine first = start(paths, web, "aaaaaaaaaaaa");
        await(() -> mcpUrl(paths) != null);

        PipedOutputStream client = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(client, 1 << 16);
        BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
        McpBridge bridge = new McpBridge(() -> endpoint(paths), project.toString());
        CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
            try {
                bridge.serve(stdin, new PrintStream(new Lines(stdout), true, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        send(
                client,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"1\"}}}");
        assertThat(next(stdout)).contains("\"protocolVersion\":\"2025-06-18\"");
        send(client, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(client, query(2));
        assertThat(next(stdout)).contains("\"id\":2");
        Map<String, Object> before = onlyAgentSession(paths);
        String id = String.valueOf(before.get("id"));

        Engine successor = start(paths, web, "bbbbbbbbbbbb");
        assertThat(first.thread().join(WAIT))
                .as("the first engine was displaced and exited")
                .isTrue();
        await(() -> mcpUrl(paths) != null);

        send(client, query(3));
        assertThat(next(stdout)).contains("\"id\":3");
        client.close();
        served.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        Map<String, Object> after = onlyAgentSession(paths);
        assertThat(after)
                .as("the same session, not a new one for a re-initialized bridge")
                .containsEntry("id", id)
                .containsEntry("label", before.get("label"))
                .containsEntry("state", "live");
        Map<String, Object> page = get(paths, "/api/sessions/" + id);
        assertThat(MiniJson.list(page, "episodes")).hasSize(1);
        List<Object> episodes = new ArrayList<>();
        for (Object a : MiniJson.list(page, "actions")) episodes.add(((Map<?, ?>) a).get("episode"));
        assertThat(episodes).as("both calls, in one episode").containsExactly(id + ".1", id + ".1");
        assertThat(successor.thread().isAlive()).isTrue();
    }

    private Engine start(EnginePaths.Paths paths, Path web, String buildId) {
        JkHttpConfig httpConfig = new JkHttpConfig("127.0.0.1", 0, 16, 16, web.toString(), JkHttpConfig.Mcp.DEFAULTS);
        EngineServer server =
                new EngineServer(paths, JkEngineConfig.DEFAULTS, httpConfig, "1.0.0-SNAPSHOT", buildId, null);
        Thread t = new Thread(
                () -> {
                    try {
                        server.run();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                "test-engine-" + buildId);
        t.setDaemon(true);
        t.start();
        Engine e = new Engine(server, t);
        engines.add(e);
        return e;
    }

    /** A {@code query} with no question: answered as an error, and logged as the session's action. */
    private static String query(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"query\",\"arguments\":{}}}";
    }

    /** The one session the bridge's client opened, as the serving engine lists it. */
    private Map<String, Object> onlyAgentSession(EnginePaths.Paths paths) throws Exception {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Object row : MiniJson.list(get(paths, "/api/sessions"), "sessions")) {
            if (row instanceof Map<?, ?> m && "claude-code".equals(m.get("client"))) agents.add(cast(m));
        }
        assertThat(agents).hasSize(1);
        return agents.getFirst();
    }

    private Map<String, Object> get(EnginePaths.Paths paths, String path) throws Exception {
        McpBridge.Endpoint endpoint = endpoint(paths);
        URI base = endpoint.url().resolve("/");
        HttpResponse<String> response =
                http.send(request(base.resolve(path), endpoint, ""), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409 && MiniJson.parse(response.body()) instanceof Map<?, ?> conflict) {
            // A dashboard learns the engine generation it talks to from the first refusal.
            String epoch = String.valueOf(conflict.get("engineEpoch"));
            response = http.send(request(base.resolve(path), endpoint, epoch), HttpResponse.BodyHandlers.ofString());
        }
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return cast((Map<?, ?>) requireNonNull(MiniJson.parse(response.body())));
    }

    private static HttpRequest request(URI uri, McpBridge.Endpoint endpoint, String epoch) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + endpoint.token());
        if (!epoch.isEmpty()) b.header("X-Jk-Engine-Epoch", epoch);
        return b.GET().build();
    }

    private static McpBridge.Endpoint endpoint(EnginePaths.Paths paths) throws IOException {
        String url = mcpUrl(paths);
        if (url == null) throw new IOException("no engine serves MCP yet");
        return new McpBridge.Endpoint(
                URI.create(url), Files.readString(paths.httpToken()).trim());
    }

    private static @Nullable String mcpUrl(EnginePaths.Paths paths) {
        return EngineProbe.status(EnginePaths.activeSocket(paths))
                .map(EngineProbe.Status::mcpUrl)
                .orElse(null);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Await.until(WAIT, condition);
    }

    private static String next(BlockingQueue<String> stdout) throws InterruptedException {
        return requireNonNull(stdout.poll(WAIT.toSeconds(), TimeUnit.SECONDS), "the bridge wrote no reply");
    }

    private static void send(PipedOutputStream client, String line) throws IOException {
        client.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        client.flush();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** Every stdout line the bridge writes. */
    private static final class Lines extends OutputStream {
        private final BlockingQueue<String> lines;
        private final StringBuilder line = new StringBuilder();

        Lines(BlockingQueue<String> lines) {
            this.lines = lines;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                lines.add(line.toString());
                line.setLength(0);
            } else {
                line.append((char) b);
            }
        }
    }
}
