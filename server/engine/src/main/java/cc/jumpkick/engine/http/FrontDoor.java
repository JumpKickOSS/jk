// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The front door: every engine on this machine behind this engine's dashboard. It lists the engines
 * that answer {@code GET /api/identity} on the guests' band ports, and proxies a paired one under
 * {@code /engines/<id>/}, server-side, with that engine's own token, so the browser holds only this
 * engine's token. Only {@code 127.0.0.1} on a band port is reached, never through a proxy, and only
 * for an engine whose pairing names it and which answers there with that id.
 *
 * <p>The browser's token is checked here and never forwarded: a request that presented it reaches
 * the engine with that engine's token instead ({@code Authorization}, the event stream's
 * {@code ?access_token=}, a coverage report's path segment); one that did not, a page or an asset,
 * reaches it with no token at all. The path is forwarded as the browser sent it, so the engine sees
 * the path the front door gated.
 */
public final class FrontDoor {

    /** The path prefix an engine's pages and API are proxied under. */
    public static final String PREFIX = "/engines/";

    /** The identity field that names the front-door protocol an engine speaks. */
    public static final String PROTOCOL_KEY = "frontDoor";

    /**
     * The front-door protocol this engine speaks: pairing, the proxy, the control messages. A WSL
     * guest pairs only with a host that speaks the same one. {@code JK_FRONT_DOOR_PROTOCOL} in the
     * environment the engine starts in stands in another, to try a mismatch.
     */
    public static final int PROTOCOL = protocol(ProcessEnv.get("JK_FRONT_DOOR_PROTOCOL"));

    static int protocol(@Nullable String override) {
        try {
            return override == null || override.isBlank() ? 1 : Integer.parseInt(override.strip());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Where a guest takes the front door's control messages, each with the guest's own token. */
    public static final String CONTROL = "/api/front-door/";

    /** How long a control message waits for the engine it is for. */
    private static final Duration CONTROL_WAIT = Duration.ofMillis(1_500);

    private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");

    /** How long discovery waits for the band ports to answer, all of them at once. */
    private static final Duration PROBE = Duration.ofMillis(800);

    private static final Duration UPSTREAM_CONNECT = Duration.ofSeconds(2);

    /** How long a guest's answer to {@code /api/identity} on its paired port is trusted. */
    private static final long VERIFIED_FOR_MS = 2_000;

    /**
     * Request headers never forwarded: those the proxy sets itself or that describe the hop, and those
     * that carry the browser's credential or the front door's address ({@code Referer} names a
     * coverage report's path, which holds the token).
     */
    private static final Set<String> REQUEST_DROPPED = Set.of(
            "host",
            "authorization",
            "cookie",
            "referer",
            "connection",
            "content-length",
            "transfer-encoding",
            "upgrade",
            "keep-alive",
            "expect",
            "te",
            "trailer",
            "proxy-authorization",
            "proxy-connection");

    /** Response headers the proxy's own framing replaces, or that would speak for the front door. */
    private static final Set<String> RESPONSE_DROPPED =
            Set.of("connection", "content-length", "transfer-encoding", "keep-alive", "set-cookie", "www-authenticate");

    private final Supplier<@Nullable EngineIdentity> self;
    private final IntSupplier selfPort;
    private final Path guests;
    private final List<Integer> ports;
    private final HttpClient client;
    private final Clock clock;
    private final Map<String, Long> verified = new ConcurrentHashMap<>();

    /**
     * @param guests the directory guests write their pairing to ({@link GuestPairing})
     * @param ports the band ports to look for engines on
     */
    public FrontDoor(Supplier<@Nullable EngineIdentity> self, IntSupplier selfPort, Path guests, List<Integer> ports) {
        this(self, selfPort, guests, ports, Clock.SYSTEM);
    }

    FrontDoor(
            Supplier<@Nullable EngineIdentity> self,
            IntSupplier selfPort,
            Path guests,
            List<Integer> ports,
            Clock clock) {
        this.self = self;
        this.selfPort = selfPort;
        this.guests = guests;
        this.ports = List.copyOf(ports);
        this.clock = clock;
        this.client = Http.loopbackClientBuilder()
                .connectTimeout(UPSTREAM_CONNECT)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The band ports a guest binds, 8911 to 8999. */
    public static List<Integer> band() {
        List<Integer> out = new ArrayList<>();
        for (int p = BandPort.FIRST; p <= BandPort.LAST; p++) out.add(p);
        return out;
    }

    /**
     * Every engine on this machine: this one first ({@code self: true}), then each engine answering on
     * a band port, with {@code paired} saying whether this front door can proxy it. Every port is
     * asked at once: on Windows a loopback port with no listener takes about a second to refuse.
     */
    public List<Map<String, Object>> engines() {
        List<Map<String, Object>> out = new ArrayList<>();
        EngineIdentity me = self.get();
        int myPort = selfPort.getAsInt();
        if (me != null) {
            Map<String, Object> m = new LinkedHashMap<>(me.toMap(myPort));
            m.put("self", true);
            m.put("paired", true);
            out.add(m);
        }
        Map<String, GuestPairing.Pair> paired = paired();
        Map<Integer, CompletableFuture<@Nullable Map<String, Object>>> asked = new LinkedHashMap<>();
        for (int port : ports) {
            if (port != myPort) asked.putIfAbsent(port, identityAsync(port, PROBE));
        }
        long deadline = clock.nanos() + PROBE.toNanos() + TimeUnit.MILLISECONDS.toNanos(200);
        // One row per engine: an engine answering on two ports (or a port listed twice) is listed once,
        // at the port it says it serves on when one of them is that port.
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        for (Map.Entry<Integer, CompletableFuture<@Nullable Map<String, Object>>> e : asked.entrySet()) {
            Map<String, Object> id = await(e.getValue(), deadline - clock.nanos());
            if (id == null || (me != null && me.id().equals(id.get("id")))) continue;
            String engine = String.valueOf(id.get("id"));
            Map<String, Object> earlier = found.get(engine);
            boolean own = e.getKey().equals(id.get("port"));
            if (earlier != null && (!own || e.getKey().equals(earlier.get("port")))) continue;
            GuestPairing.Pair pair = paired.get(engine);
            Map<String, Object> m = new LinkedHashMap<>(id);
            m.put("self", false);
            m.put("paired", pair != null && id.get("port") instanceof Integer p && pair.port() == p);
            found.put(engine, m);
        }
        out.addAll(found.values());
        return out;
    }

    private static @Nullable Map<String, Object> await(
            CompletableFuture<@Nullable Map<String, Object>> f, long remainingNanos) {
        try {
            return f.get(Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            f.cancel(true);
            return null;
        }
    }

    /** The identity an engine on {@code port} answers, or null when nothing jk answers there. */
    @Nullable
    Map<String, Object> identityAt(int port) {
        return await(identityAsync(port, PROBE), PROBE.toNanos() + UPSTREAM_CONNECT.toNanos());
    }

    private CompletableFuture<@Nullable Map<String, Object>> identityAsync(int port, Duration timeout) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + HttpEngineServer.IDENTITY))
                .timeout(timeout)
                .GET()
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .<@Nullable Map<String, Object>>thenApply(FrontDoor::identityOf)
                .exceptionally(failed -> null);
    }

    private static @Nullable Map<String, Object> identityOf(HttpResponse<String> r) {
        if (r.statusCode() != 200 || !(MiniJson.parse(r.body()) instanceof Map<?, ?> m)) return null;
        if (!(m.get("id") instanceof String id) || !ID.matcher(id).matches() || !(m.get("port") instanceof Number)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        // JSON numbers parse as doubles; a port is an integer, here and on the wire.
        out.put("port", ((Number) m.get("port")).intValue());
        return out;
    }

    /** {@code GET /api/engines}: the engines on this machine. */
    void handleList(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("engines", engines());
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(body).toString());
    }

    /**
     * A request under {@link #PREFIX}: the engine's id, the path within it as the browser sent it
     * (still percent-encoded), and that path decoded, which is what both engines route and gate on.
     */
    record Target(String id, String rawPath, String path) {}

    /**
     * The target of {@code rawPath}, a request's raw path under {@link #PREFIX}; null when it is
     * malformed: no 12-hex id, a dot segment, an empty segment, or an escape that decodes to a
     * separator ({@code / \ ? #}), a {@code %} or a control character, any of which could make the
     * engine route a path other than the one gated here.
     */
    static @Nullable Target target(String rawPath) {
        if (!rawPath.startsWith(PREFIX)) return null;
        String rest = rawPath.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        if (!ID.matcher(id).matches()) return null;
        String raw = slash < 0 ? "" : rest.substring(slash);
        if (raw.isEmpty()) return new Target(id, "", "");
        // Before parsing: a leading "//" reads as an authority.
        if (raw.contains("//")) return null;
        String path;
        try {
            path = URI.create(raw).getPath();
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (path == null || !path.startsWith("/") || path.contains("\\")) return null;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '%' || c == '?' || c == '#') return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c")) return null;
        String[] segments = path.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            if (s.equals(".") || s.equals("..")) return null;
            if (s.isEmpty() && i < segments.length - 1) return null;
        }
        return new Target(id, raw, path);
    }

    /** What a proxied request asks of the engine, by the path it routes on. */
    enum Kind {
        /** {@code /api/events}: the event stream, which may carry its token in the query. */
        EVENTS,
        /** {@code /api} and below: a bearer token. */
        API,
        /** The MCP endpoint: a bearer token, or the query's on its event-stream GET. */
        MCP,
        /** {@code /report/<token>/…}: a coverage report, the token in the path. */
        REPORT,
        /** Pages and assets: no token. */
        OPEN;

        static Kind of(String path) {
            if (path.equals("/api/events")) return EVENTS;
            if (path.equals("/api") || path.startsWith("/api/")) return API;
            if (HttpAdmission.isMcpPath(path)) return MCP;
            if (path.startsWith(HttpCoverageReport.PREFIX)) return REPORT;
            return OPEN;
        }
    }

    /**
     * Forwards {@code exchange} to paired engine {@code target.id()}, streaming the answer back as it
     * comes, so an event stream flows through. {@code authorized} says the browser presented this
     * engine's token in the shape {@code kind} takes it; only then does the request carry the target
     * engine's token, in that same shape.
     */
    void proxy(HttpExchange exchange, Target target, Kind kind, boolean authorized) throws IOException {
        if (target.rawPath().isEmpty()) {
            // Relative asset links need the trailing slash.
            exchange.getResponseHeaders().set("Location", PREFIX + target.id() + "/");
            HttpResponses.sendText(exchange, 308, "moved\n");
            return;
        }
        GuestPairing.Pair pair = paired().get(target.id());
        if (pair == null) {
            HttpResponses.sendText(exchange, 404, "engine " + target.id() + " is not paired with this one\n");
            return;
        }
        if (!answers(pair)) {
            verified.remove(pair.id() + ":" + pair.port());
            HttpResponses.sendText(
                    exchange, 502, "engine " + target.id() + " does not answer on port " + pair.port() + "\n");
            return;
        }
        String path = target.rawPath();
        if (kind == Kind.REPORT) path = reportPath(path, authorized ? pair.token() : null);
        if (path == null) {
            HttpResponses.sendText(exchange, 404, "not found\n");
            return;
        }
        boolean queryToken = authorized && (kind == Kind.EVENTS || kind == Kind.MCP);
        String query = forwardedQuery(exchange.getRequestURI().getRawQuery(), queryToken ? pair.token() : null);
        URI uri = URI.create("http://127.0.0.1:" + pair.port() + path + (query == null ? "" : "?" + query));
        byte[] body = exchange.getRequestBody().readAllBytes();
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .method(
                        exchange.getRequestMethod(),
                        body.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        if (authorized && (kind == Kind.API || kind == Kind.EVENTS || kind == Kind.MCP)) {
            req.header("Authorization", "Bearer " + pair.token());
        }
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (REQUEST_DROPPED.contains(name.toLowerCase(Locale.ROOT))) return;
            for (String v : values) {
                try {
                    req.header(name, v);
                } catch (IllegalArgumentException restricted) {
                    // A header HttpClient sets itself.
                }
            }
        });
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpResponses.sendText(exchange, 502, "proxy interrupted\n");
            return;
        } catch (IOException e) {
            HttpResponses.sendText(exchange, 502, "engine " + target.id() + " did not answer\n");
            return;
        }
        if (resp.statusCode() == 401 && authorized) {
            // The engine refused its own token from the pairing: a stale pairing, never the browser's
            // fault. A 401 here would make the dashboard drop the front door's token.
            resp.body().close();
            HttpResponses.sendText(
                    exchange,
                    502,
                    "engine " + target.id() + " refused the token it paired with; it pairs again when it restarts\n");
            return;
        }
        resp.headers().map().forEach((name, values) -> {
            if (RESPONSE_DROPPED.contains(name.toLowerCase(Locale.ROOT)) || name.startsWith(":")) return;
            exchange.getResponseHeaders().put(name, values);
        });
        boolean empty =
                resp.statusCode() == 204 || resp.statusCode() == 304 || "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(resp.statusCode(), empty ? -1 : 0);
        try (InputStream in = resp.body();
                OutputStream out = exchange.getResponseBody()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) >= 0; ) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException closed) {
            // The browser left, or the engine closed its stream; either ends the exchange.
        }
    }

    /**
     * Tells every other paired engine this front door is there ({@code present}) or stopping ({@code
     * leaving}), each with its own token, all at once; answers how many took it.
     */
    public int tell(String message) {
        EngineIdentity me = self.get();
        List<CompletableFuture<Boolean>> sent = new ArrayList<>();
        for (GuestPairing.Pair pair : paired().values()) {
            if (me != null && me.id().equals(pair.id())) continue;
            sent.add(control(pair, message));
        }
        int took = 0;
        for (CompletableFuture<Boolean> f : sent) {
            try {
                if (f.get(CONTROL_WAIT.toMillis() + 500, TimeUnit.MILLISECONDS)) took++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException e) {
                f.cancel(true);
            }
        }
        return took;
    }

    /**
     * Asks the paired guest holding {@code port} (8910) to give it up: reads who answers there, and
     * sends that guest {@code step-aside} on its own port with its own token. False when no paired
     * guest holds it, or it did not let go.
     */
    public boolean askToStepAside(int port) {
        Map<String, Object> holder = identityAt(port);
        if (holder == null || !"guest".equals(holder.get("role"))) return false;
        GuestPairing.Pair pair = paired().get(String.valueOf(holder.get("id")));
        if (pair == null) return false;
        try {
            return control(pair, "step-aside").get(CONTROL_WAIT.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private CompletableFuture<Boolean> control(GuestPairing.Pair pair, String message) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + pair.port() + CONTROL + message))
                .timeout(CONTROL_WAIT)
                .header("Authorization", "Bearer " + pair.token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> r.statusCode() == 200)
                .exceptionally(failed -> false);
    }

    /** The pairings in the guests' directory whose port is one this front door looks on. */
    private Map<String, GuestPairing.Pair> paired() {
        Map<String, GuestPairing.Pair> out = GuestPairing.read(guests);
        out.values().removeIf(pair -> !ports.contains(pair.port()));
        return out;
    }

    /** Whether paired engine {@code pair} answers on its port with its id, trusted for a moment. */
    private boolean answers(GuestPairing.Pair pair) {
        long now = clock.millis();
        Long at = verified.get(pair.id() + ":" + pair.port());
        if (at != null && now - at < VERIFIED_FOR_MS) return true;
        Map<String, Object> answering = identityAt(pair.port());
        if (answering == null || !pair.id().equals(answering.get("id"))) return false;
        verified.put(pair.id() + ":" + pair.port(), now);
        return true;
    }

    /**
     * {@code rawPath}, a coverage report's {@code /report/<token>/…}, with the token segment replaced
     * by {@code token}; null when there is no token segment or the browser's did not authorize it.
     */
    static @Nullable String reportPath(String rawPath, @Nullable String token) {
        if (token == null) return null;
        String rest = rawPath.substring(HttpCoverageReport.PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        return HttpCoverageReport.PREFIX + URLEncoder.encode(token, StandardCharsets.UTF_8) + rest.substring(slash);
    }

    /** The token segment of a coverage report's path, decoded; null when there is none. */
    static @Nullable String reportToken(String path) {
        if (!path.startsWith(HttpCoverageReport.PREFIX)) return null;
        String rest = path.substring(HttpCoverageReport.PREFIX.length());
        int slash = rest.indexOf('/');
        return slash <= 0 ? null : rest.substring(0, slash);
    }

    /**
     * {@code raw} without the browser's {@code access_token}, and with {@code token} in its place when
     * the request carries the engine's token in its query; null when nothing is left.
     */
    static @Nullable String forwardedQuery(@Nullable String raw, @Nullable String token) {
        List<String> parts = new ArrayList<>();
        boolean had = false;
        if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split("&")) {
                if (part.equals("access_token") || part.startsWith("access_token=")) {
                    had = true;
                } else if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
        }
        if (had && token != null) parts.addFirst("access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        return parts.isEmpty() ? null : String.join("&", parts);
    }
}
