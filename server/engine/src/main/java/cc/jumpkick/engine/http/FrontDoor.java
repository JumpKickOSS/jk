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
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
 * {@code ?access_token=}); one that did not, a page, an asset or a coverage report, reaches it with
 * no token at all. The path is forwarded as the browser sent it, so the engine sees the path the
 * front door gated, and a coverage report's path carries the report token the engine itself handed
 * out ({@link HttpCoverageReport}), which the engine checks.
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

    /** The heartbeat's header that says the front door raises the engine's notifications for it. */
    public static final String RELAYS = "X-Jk-Front-Door-Relays";

    /** {@link #RELAYS}' value: decisions' notifications ({@code alert} events) are raised by the front door. */
    public static final String RELAYS_ALERTS = "alerts";

    /** Where a guest takes the front door's control messages, each with the guest's own token. */
    public static final String CONTROL = "/api/front-door/";

    /** How long a control message waits for the engine it is for. */
    private static final Duration CONTROL_WAIT = Duration.ofMillis(1_500);

    private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");

    /** How long discovery waits for the band ports to answer, all of them at once. */
    private static final Duration PROBE = Duration.ofMillis(800);

    private static final Duration UPSTREAM_CONNECT = Duration.ofSeconds(2);

    /** How long a proxied engine may send nothing before it is asked whether it is still there. */
    private static final Duration UPSTREAM_SILENCE = Duration.ofSeconds(30);

    /** How long that question waits; generous, so a busy engine is not taken for a stopped one. */
    private static final Duration STILL_THERE = Duration.ofSeconds(5);

    /** How long a guest's answer to {@code /api/identity} on its paired port is trusted. */
    private static final long VERIFIED_FOR_MS = 2_000;

    /**
     * Request headers never forwarded: those the proxy sets itself or that describe the hop, and those
     * that carry the browser's credential or the front door's address ({@code Referer} names a
     * coverage report's path, which holds its token; {@code Origin}, which the front door's own gate
     * has checked, names the front door and not the engine).
     */
    private static final Set<String> REQUEST_DROPPED = Set.of(
            "host",
            "authorization",
            "cookie",
            "referer",
            "origin",
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

    /** What a page or a report takes; anything else is a 405 before the body is read. */
    private static final String READS = "GET, HEAD";

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

    /** Whether this front door raises the paired engines' notifications ({@link FrontDoorRelay}). */
    private volatile boolean relays;

    /** How long one write to the browser may stay blocked before the proxied exchange is closed. */
    private volatile Duration writeLimit = WriteDeadline.PER_WRITE;

    /** How long a proxied engine may send nothing before the proxy asks whether it is still there. */
    private volatile Duration upstreamSilence = UPSTREAM_SILENCE;

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
     * Every engine on this machine: this one first ({@code self: true}), then each guest engine
     * answering on a band port, with {@code paired} saying whether this front door can proxy it. Every port is
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
        // One row per engine: an engine answering on two ports (or a port listed twice) is listed once,
        // at the port it says it serves on when one of them is that port.
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Object>> e : swept(myPort).entrySet()) {
            Map<String, Object> id = e.getValue();
            if (me != null && me.id().equals(id.get("id"))) continue;
            // A host engine on the band is another home's on this host (a scratch, test or gate home moved
            // off 8910), not a guest this front door could pair with: listing it would offer a dead row.
            if ("host".equals(id.get("role"))) continue;
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

    /**
     * How long one sweep of the band answers {@link #engines}: every dashboard tab lists the engines
     * every 30 s and the machine view on each refresh, and each sweep asks every band port. An engine
     * that starts or stops shows within this, plus the one sweep at most {@link #PROBE} long.
     */
    private static final long SWEPT_FOR_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** One sweep of the band from {@code port}: when it started, and each answering port's identity in band order. */
    private record Sweep(int port, long startedNanos, CompletableFuture<Map<Integer, Map<String, Object>>> answers) {}

    private final Object sweepLock = new Object();

    /** The newest sweep of the band, running or done; callers within {@link #SWEPT_FOR_NANOS} share it. */
    private @Nullable Sweep sweep;

    /**
     * Each band port but {@code myPort} that answers an identity, in band order: the sweep running
     * or done within {@link #SWEPT_FOR_NANOS} when there is one, else a new one. The pairing and this
     * engine's own row are not part of it; {@link #engines} reads those fresh on every call.
     */
    private Map<Integer, Map<String, Object>> swept(int myPort) {
        Sweep current;
        boolean mine = false;
        synchronized (sweepLock) {
            current = sweep;
            long now = clock.nanos();
            if (current == null || current.port() != myPort || now - current.startedNanos() >= SWEPT_FOR_NANOS) {
                current = new Sweep(myPort, now, new CompletableFuture<>());
                sweep = current;
                mine = true;
            }
        }
        if (mine) {
            try {
                Map<Integer, Map<String, Object>> answers = sweepBand(myPort);
                // An interrupted sweep stopped waiting early: its answers are short and not for sharing.
                if (Thread.currentThread().isInterrupted()) forget(current);
                current.answers().complete(answers);
            } catch (RuntimeException | Error e) {
                forget(current);
                current.answers().completeExceptionally(e);
                throw e;
            }
        }
        return current.answers().join();
    }

    private void forget(Sweep done) {
        synchronized (sweepLock) {
            if (sweep == done) sweep = null;
        }
    }

    private Map<Integer, Map<String, Object>> sweepBand(int myPort) {
        Map<Integer, CompletableFuture<@Nullable Map<String, Object>>> asked = new LinkedHashMap<>();
        for (int port : ports) {
            if (port != myPort) asked.putIfAbsent(port, identityAsync(port, PROBE));
        }
        long deadline = clock.nanos() + PROBE.toNanos() + TimeUnit.MILLISECONDS.toNanos(200);
        Map<Integer, Map<String, Object>> answers = new LinkedHashMap<>();
        for (Map.Entry<Integer, CompletableFuture<@Nullable Map<String, Object>>> e : asked.entrySet()) {
            Map<String, Object> id = await(e.getValue(), deadline - clock.nanos());
            if (id != null) answers.put(e.getKey(), Collections.unmodifiableMap(id));
        }
        return Collections.unmodifiableMap(answers);
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
        /**
         * Pages, assets, coverage reports (their report token in the path, which the engine checks)
         * and the engine's identity: no token; reads only.
         */
        OPEN;

        static Kind of(String method, String path) {
            if (path.equals(HttpEngineServer.IDENTITY) && method.equals("GET")) return OPEN;
            if (path.equals("/api/events")) return EVENTS;
            if (path.equals("/api") || path.startsWith("/api/")) return API;
            if (HttpAdmission.isMcpPath(path)) return MCP;
            return OPEN;
        }
    }

    /**
     * Forwards {@code exchange} to paired engine {@code target.id()}, streaming the answer back as it
     * comes, so an event stream flows through; a browser that stops reading is cut off once one write
     * waits past {@link WriteDeadline#PER_WRITE}, which closes the engine's answer too, and an engine
     * that goes silent and then fails to answer whether it is there is given up on ({@link
     * UpstreamWatch}). {@code authorized} says the browser presented this engine's token in the shape {@code kind} takes it;
     * only then does the request carry the target engine's token, in that same shape. A body is read,
     * under the cap the engine itself puts on that path, before the engine is asked anything. An answer's {@code Location} is put back under
     * {@code /engines/<id>/}; one that leads elsewhere, or any header that carries the engine's token,
     * is a 502.
     */
    void proxy(HttpExchange exchange, Target target, Kind kind, boolean authorized) throws IOException {
        if (target.rawPath().isEmpty()) {
            // Relative asset links need the trailing slash.
            exchange.getResponseHeaders().set("Location", PREFIX + target.id() + "/");
            HttpResponses.sendText(exchange, 308, "moved\n");
            return;
        }
        String method = exchange.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        if (!read && kind == Kind.OPEN) {
            exchange.getResponseHeaders().set("Allow", READS);
            HttpResponses.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        byte[] body = read ? new byte[0] : HttpRequests.bytes(exchange, maxBody(method, target.path()));
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
        boolean queryToken = authorized && (kind == Kind.EVENTS || kind == Kind.MCP);
        String query = forwardedQuery(exchange.getRequestURI().getRawQuery(), queryToken ? pair.token() : null);
        URI uri = URI.create("http://127.0.0.1:" + pair.port() + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .method(
                        method,
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
        UpstreamWatch watch = UpstreamWatch.start(upstreamSilence, () -> stillThere(pair));
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            if (watch.end()) {
                HttpResponses.sendText(exchange, 502, "engine " + target.id() + " stopped answering\n");
                return;
            }
            Thread.currentThread().interrupt();
            HttpResponses.sendText(exchange, 502, "proxy interrupted\n");
            return;
        } catch (IOException e) {
            watch.end();
            HttpResponses.sendText(exchange, 502, "engine " + target.id() + " did not answer\n");
            return;
        }
        watch.heard();
        try {
            relay(exchange, resp, target, kind, authorized, uri, pair, method, watch);
        } finally {
            watch.end();
        }
    }

    /** Passes the engine's answer {@code resp} on to the browser, its headers checked, its body as it comes. */
    private void relay(
            HttpExchange exchange,
            HttpResponse<InputStream> resp,
            Target target,
            Kind kind,
            boolean authorized,
            URI uri,
            GuestPairing.Pair pair,
            String method,
            UpstreamWatch watch)
            throws IOException {
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
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> h : resp.headers().map().entrySet()) {
            String name = h.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (RESPONSE_DROPPED.contains(lower) || name.startsWith(":")) continue;
            List<String> values = new ArrayList<>();
            for (String v : h.getValue()) {
                String value = lower.equals("location") ? location(v, uri, target.id()) : v;
                if (value == null || carries(value, pair.token())) {
                    resp.body().close();
                    HttpResponses.sendText(
                            exchange,
                            502,
                            "engine " + target.id() + " answered with a " + name + " the front door cannot pass on\n");
                    return;
                }
                values.add(value);
            }
            headers.put(name, values);
        }
        exchange.getResponseHeaders().putAll(headers);
        boolean empty = resp.statusCode() == 204 || resp.statusCode() == 304 || method.equals("HEAD");
        exchange.sendResponseHeaders(resp.statusCode(), empty ? -1 : 0);
        try (InputStream in = resp.body();
                OutputStream out = new WriteDeadline(exchange.getResponseBody(), writeLimit)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) >= 0; ) {
                watch.heard();
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException closed) {
            // The browser left or stopped reading, or the engine closed or stopped its stream: the
            // exchange ends.
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
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + pair.port() + CONTROL + message))
                .timeout(CONTROL_WAIT)
                .header("Authorization", "Bearer " + pair.token())
                .POST(HttpRequest.BodyPublishers.noBody());
        if (message.equals("present") && relays) b.header(RELAYS, RELAYS_ALERTS);
        HttpRequest req = b.build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> r.statusCode() == 200)
                .exceptionally(failed -> false);
    }

    /** The identity paired engine {@code id} answers on its paired port; null when it does not answer there. */
    @Nullable
    Map<String, Object> identityOfPaired(String id) {
        GuestPairing.Pair pair = paired().get(id);
        if (pair == null) return null;
        Map<String, Object> answering = identityAt(pair.port());
        return answering != null && id.equals(answering.get("id")) ? answering : null;
    }

    /** Says in each heartbeat that this front door raises the paired engines' notifications. */
    public void relays(boolean relays) {
        this.relays = relays;
    }

    /** Test seam: shrink how long an engine may send nothing before the proxy asks whether it is there. */
    void upstreamSilence(Duration silence) {
        this.upstreamSilence = silence;
    }

    /** Whether the engine {@code pair} names still answers as itself; never blocks. */
    private CompletableFuture<Boolean> stillThere(GuestPairing.Pair pair) {
        return identityAsync(pair.port(), STILL_THERE)
                .thenApply(id -> id != null && pair.id().equals(id.get("id")));
    }

    /** Test seam: shrink how long one write may stay blocked on a browser that stopped reading. */
    void writeLimit(Duration limit) {
        this.writeLimit = limit;
    }

    /** The pairings this front door proxies, by engine id; what {@link FrontDoorRelay} subscribes to. */
    Map<String, GuestPairing.Pair> pairs() {
        return paired();
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

    /** The body cap the engine puts on {@code method} at {@code path}. */
    private static int maxBody(String method, String path) {
        return method.equals("PUT") && path.equals("/api/project/file")
                ? HttpProjectApi.MAX_FILE_PUT_BODY_BYTES
                : HttpRequests.MAX_BODY_BYTES;
    }

    /**
     * Upstream {@code location}, resolved against {@code upstream}, the request the proxy sent, as a
     * path under engine {@code id}'s prefix. Null when it leads off the engine's address or to a path
     * the front door would refuse.
     */
    static @Nullable String location(String location, URI upstream, String id) {
        URI to;
        try {
            to = upstream.resolve(new URI(location)).normalize();
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return null;
        }
        if (!"http".equalsIgnoreCase(to.getScheme())
                || !upstream.getHost().equalsIgnoreCase(to.getHost())
                || to.getPort() != upstream.getPort()
                || to.getRawUserInfo() != null) {
            return null;
        }
        String path = to.getRawPath();
        if (path == null || !path.startsWith("/")) return null;
        String mapped = PREFIX + id + path;
        if (target(mapped) == null) return null;
        if (to.getRawQuery() != null) mapped += "?" + to.getRawQuery();
        if (to.getRawFragment() != null) mapped += "#" + to.getRawFragment();
        return mapped;
    }

    /** Whether {@code value} holds {@code token}, as written or percent-encoded. */
    static boolean carries(String value, String token) {
        if (value.contains(token)) return true;
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
                    .contains(token);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
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
