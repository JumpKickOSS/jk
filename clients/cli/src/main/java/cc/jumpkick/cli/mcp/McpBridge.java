// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * MCP over stdio for one project: each newline-delimited JSON-RPC message from the client is
 * POSTed to the engine's {@code /mcp} with the {@code Jk-Project} header, and each reply is
 * written back as one line. The header pins the connection, so the tools take no {@code dir}.
 * While a {@code tools/call} that carries {@code _meta.progressToken} is outstanding, {@link
 * McpProgress} writes {@code notifications/progress} lines for it; every stdout line goes through
 * one lock, so a notification never interleaves with a reply.
 */
public final class McpBridge {

    /** Where the engine serves MCP and its bearer token. Asked again after a failed request. */
    public interface Endpoints {
        Endpoint current() throws IOException;
    }

    public record Endpoint(URI url, String token) {}

    static final String PROJECT_HEADER = "Jk-Project";
    static final String SESSION_HEADER = "Mcp-Session-Id";

    /** Longer than any parked {@code run}: the engine's own wait cap answers first. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(65);

    /** The longest a call with a progress token goes without a notification. */
    private static final Duration HEARTBEAT = Duration.ofSeconds(5);

    /** The shortest gap between two notifications, and how long a call runs before it is followed. */
    private static final Duration GAP = Duration.ofSeconds(1);

    private final Endpoints endpoints;
    private final @Nullable String project;
    private final HttpClient http;
    private final Duration heartbeat;
    private final Duration gap;
    private volatile @Nullable Endpoint endpoint;
    private @Nullable String session;

    public McpBridge(Endpoints endpoints, @Nullable String project) {
        this(endpoints, project, HEARTBEAT, GAP);
    }

    /** As {@link #McpBridge(Endpoints, String)} with the progress pacing; tests shorten it. */
    McpBridge(Endpoints endpoints, @Nullable String project, Duration heartbeat, Duration gap) {
        this.endpoints = endpoints;
        this.project = project;
        this.heartbeat = heartbeat;
        this.gap = gap;
        this.http = Http.proxiedClientBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Serve until the client closes stdin. */
    public void serve(InputStream in, PrintStream out) throws IOException {
        Consumer<String> lines = line -> {
            synchronized (out) {
                out.print(line);
                out.print('\n');
                out.flush();
            }
        };
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String reply = call(line, lines);
            if (!reply.isEmpty()) lines.accept(reply);
        }
    }

    /** {@link #forward}, followed for progress when the message is a {@code tools/call} with a token. */
    private String call(String message, Consumer<String> lines) {
        Object token = McpProgress.tokenOf(message);
        if (token == null) return forward(message);
        try (McpProgress ignored = McpProgress.start(token, this::events, lines, heartbeat, gap)) {
            return forward(message);
        }
    }

    /** The engine's MCP event stream for one progress token. */
    private InputStream events(String token) throws IOException, InterruptedException {
        Endpoint target = endpoint;
        if (target == null) target = endpoints.current();
        String url = target.url().toString();
        URI uri = URI.create(url + (url.contains("?") ? "&" : "?") + "progressToken="
                + URLEncoder.encode(token, StandardCharsets.UTF_8));
        HttpRequest request = Http.proxiedRequest(uri)
                .header("Authorization", "Bearer " + target.token())
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("jk engine event stream answered HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * One message to the engine; the reply as one line, empty for a notification. A request that
     * cannot reach the engine asks for the endpoint again once (the engine may have restarted on a
     * new port), then answers a JSON-RPC error rather than leaving the client waiting.
     */
    String forward(String message) {
        try {
            return oneLine(post(message));
        } catch (RuntimeException e) {
            return error(message, "jk mcp: " + e.getMessage());
        } catch (IOException first) {
            endpoint = null;
            try {
                return oneLine(post(message));
            } catch (IOException | RuntimeException e) {
                return error(message, "jk engine unreachable: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error(message, "interrupted");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(message, "interrupted");
        }
    }

    private String post(String message) throws IOException, InterruptedException {
        if (endpoint == null) endpoint = endpoints.current();
        Endpoint target = endpoint;
        HttpRequest.Builder request = Http.proxiedRequest(target.url())
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + target.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8));
        if (project != null) request.header(PROJECT_HEADER, project);
        if (session != null) request.header(SESSION_HEADER, session);
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        response.headers().firstValue(SESSION_HEADER).ifPresent(id -> session = id);
        int status = response.statusCode();
        if (status == 202) return "";
        if (status / 100 != 2)
            return error(
                    message,
                    "jk engine answered HTTP " + status + ": " + response.body().strip());
        return response.body();
    }

    /** JSON has no raw line breaks outside strings, so dropping them keeps one message per line. */
    static String oneLine(String json) {
        return json.indexOf('\n') < 0 && json.indexOf('\r') < 0
                ? json
                : json.replace("\r", "").replace("\n", "");
    }

    /** A JSON-RPC error for {@code message}'s id; empty when it was a notification. */
    static String error(String message, String text) {
        Object id = null;
        try {
            if (MiniJson.parse(message) instanceof Map<?, ?> m) {
                if (!m.containsKey("id")) return "";
                id = m.get("id");
            }
        } catch (RuntimeException e) {
            // Not JSON: answer with a null id, as JSON-RPC does for a parse error.
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", -32603);
        error.put("message", text);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("jsonrpc", "2.0");
        reply.put("id", wholeId(id));
        reply.put("error", error);
        return MiniJson.write(reply);
    }

    /** The id as the client sent it: MiniJson reads numbers as doubles, so an integral one goes back whole. */
    private static @Nullable Object wholeId(@Nullable Object id) {
        if (id instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.longValue();
        return id;
    }
}
