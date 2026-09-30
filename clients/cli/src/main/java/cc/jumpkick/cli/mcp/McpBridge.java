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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * MCP over stdio for one project: each newline-delimited JSON-RPC message from the client is
 * POSTed to the engine's {@code /mcp} with the {@code Jk-Project} header, and each reply is
 * written back as one line. The header pins the connection, so the tools take no {@code dir}.
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

    private final Endpoints endpoints;
    private final @Nullable String project;
    private final HttpClient http;
    private @Nullable Endpoint endpoint;
    private @Nullable String session;

    public McpBridge(Endpoints endpoints, @Nullable String project) {
        this.endpoints = endpoints;
        this.project = project;
        this.http = Http.proxiedClientBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Serve until the client closes stdin. */
    public void serve(InputStream in, PrintStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String reply = forward(line);
            if (reply.isEmpty()) continue;
            out.print(reply);
            out.print('\n');
            out.flush();
        }
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
