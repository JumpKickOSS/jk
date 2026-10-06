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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * MCP over stdio for one project: each newline-delimited JSON-RPC message from the client is
 * POSTed to the engine's {@code /mcp} with the {@code Jk-Project} header, and each reply is
 * written back as one line. The header pins the connection, so the tools take no {@code dir}.
 *
 * <p>Requests run concurrently: a parked {@code run} does not hold up a {@code ping} or a second
 * call, and each reply is written when it arrives. {@code initialize} alone is answered before the
 * next line is read, so every later request carries the session it opened. Every stdout line goes
 * through one lock, so lines never interleave.
 *
 * <p>Every outstanding {@code tools/call} carries a progress token, the client's or one the bridge
 * adds, and the engine binds the job a call starts to it. A {@code notifications/cancelled} naming
 * an outstanding call is forwarded with that token, so the engine cancels the call's job and the
 * parked call answers with its verdict. While a call that carries the client's own token is
 * outstanding, {@link McpProgress} writes {@code notifications/progress} lines for it.
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

    /** Requests in flight at once; a line past this waits for one to answer. */
    static final int MAX_IN_FLIGHT = 32;

    /** Prefix of a token the bridge adds; unique per bridge, since the engine's token table is shared. */
    private final String tokenPrefix = "jk-mcp-" + UUID.randomUUID() + "-";

    private final AtomicLong tokens = new AtomicLong();

    /** Outstanding {@code tools/call}s by request id ({@link #idKey}), each with its progress token. */
    private final Map<String, String> outstanding = new ConcurrentHashMap<>();

    private final Endpoints endpoints;
    private final @Nullable String project;
    private final HttpClient http;
    private final Duration heartbeat;
    private final Duration gap;
    private volatile @Nullable Endpoint endpoint;
    private volatile @Nullable String session;

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

    /** Serve until the client closes stdin; returns once every request in flight has answered. */
    public void serve(InputStream in, PrintStream out) throws IOException {
        Consumer<String> lines = line -> {
            synchronized (out) {
                out.print(line);
                out.print('\n');
                out.flush();
            }
        };
        Semaphore room = new Semaphore(MAX_IN_FLIGHT);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Message m = Message.of(line);
                if (m.method().equals("initialize")) {
                    write(lines, forward(line));
                    continue;
                }
                if (m.method().equals("notifications/cancelled")) {
                    write(lines, forward(cancellation(m, line)));
                    continue;
                }
                room.acquireUninterruptibly();
                // Recorded here, on the reading thread, so a cancel on the next line finds the call.
                Call call = Call.of(this, m, line);
                calls.execute(() -> {
                    try {
                        write(lines, call.send(this, lines));
                    } finally {
                        room.release();
                    }
                });
            }
        }
    }

    private static void write(Consumer<String> lines, String reply) {
        if (!reply.isEmpty()) lines.accept(reply);
    }

    /**
     * One request on its way: the message as sent and, for a {@code tools/call}, the key it is
     * outstanding under and the client's own progress token, when it sent one.
     */
    private record Call(
            String sent, @Nullable String key, @Nullable Object clientToken) {

        /** {@code line} ready to send; a {@code tools/call} gets a progress token and is recorded as outstanding. */
        static Call of(McpBridge bridge, Message m, String line) {
            Object id = m.id();
            if (!m.method().equals("tools/call") || id == null) return new Call(line, null, null);
            Object clientToken = McpProgress.tokenOf(line);
            String token = clientToken != null
                    ? String.valueOf(clientToken)
                    : bridge.tokenPrefix + bridge.tokens.incrementAndGet();
            String key = idKey(id);
            bridge.outstanding.put(key, token);
            return new Call(clientToken != null ? line : withProgressToken(m.json(), token), key, clientToken);
        }

        /** {@link #forward}, followed for progress when the client sent the token; no longer outstanding after. */
        String send(McpBridge bridge, Consumer<String> lines) {
            try {
                if (clientToken == null) return bridge.forward(sent);
                try (McpProgress ignored =
                        McpProgress.start(clientToken, bridge::events, lines, bridge.heartbeat, bridge.gap)) {
                    return bridge.forward(sent);
                }
            } finally {
                if (key != null) bridge.outstanding.remove(key);
            }
        }
    }

    /**
     * A {@code notifications/cancelled} as the engine needs it: when it names an outstanding call,
     * with that call's progress token in {@code params._meta}; otherwise as the client sent it.
     */
    String cancellation(Message m, String message) {
        if (!(m.json().get("params") instanceof Map<?, ?> params)) return message;
        Object requestId = params.get("requestId");
        String token = requestId == null ? null : outstanding.get(idKey(requestId));
        return token == null ? message : withProgressToken(m.json(), token);
    }

    /** {@code json} with {@code params._meta.progressToken} set; integral numbers written whole. */
    @SuppressWarnings("unchecked")
    static String withProgressToken(Map<String, Object> json, String token) {
        Map<String, Object> copy = (Map<String, Object>) Objects.requireNonNull(whole(json));
        Map<String, Object> params =
                copy.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : new LinkedHashMap<>();
        Map<String, Object> meta =
                params.get("_meta") instanceof Map<?, ?> mm ? (Map<String, Object>) mm : new LinkedHashMap<>();
        meta.put("progressToken", token);
        params.put("_meta", meta);
        copy.put("params", params);
        return MiniJson.write(copy);
    }

    /**
     * A mutable deep copy with every integral number as a {@code long}: the parser reads numbers as
     * doubles, and an id or a count goes back as the client wrote it.
     */
    private static @Nullable Object whole(@Nullable Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), whole(e.getValue()));
            return out;
        }
        if (value instanceof List<?> l) {
            List<@Nullable Object> out = new ArrayList<>();
            for (Object o : l) out.add(whole(o));
            return out;
        }
        return wholeId(value);
    }

    /** A request id as a map key: {@code 5} and {@code 5.0} are one id, and a string is never a number. */
    static String idKey(Object id) {
        Object whole = wholeId(id);
        return (whole instanceof String ? "s:" : "n:") + whole;
    }

    /** One line from the client: its method ({@code ""} for a reply or garbage), its id, and its parsed form. */
    record Message(String method, @Nullable Object id, Map<String, Object> json) {
        @SuppressWarnings("unchecked")
        static Message of(String line) {
            try {
                if (MiniJson.parse(line) instanceof Map<?, ?> m) {
                    Object method = m.get("method");
                    return new Message(
                            method == null ? "" : String.valueOf(method), m.get("id"), (Map<String, Object>) m);
                }
            } catch (RuntimeException e) {
                // Not JSON: the engine answers the parse error.
            }
            return new Message("", null, Map.of());
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
