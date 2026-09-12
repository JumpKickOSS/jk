// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/**
 * {@link HttpClient} wrapper: exponential backoff with jitter on 5xx and network {@link IOException}s;
 * never retries 4xx; max 5 attempts.
 *
 * <p>Redirects are followed here, not by the client, so that the policy is jk's: up to {@link
 * #MAX_REDIRECTS} hops, never from https to http, and a hop that leaves the request's origin (scheme,
 * host, port) is re-issued without {@code Authorization} or {@code Cookie}. A private repository
 * that hands a download to a CDN must not hand the CDN the repository token with it.
 */
public final class Http {

    /** Most redirect hops followed for one request — the JDK client's own default. */
    static final int MAX_REDIRECTS = 5;

    /** Headers that authenticate the caller to one origin and must not travel to another. */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "cookie");

    private static final Duration[] BACKOFFS = {
        Duration.ofMillis(100),
        Duration.ofMillis(200),
        Duration.ofMillis(400),
        Duration.ofMillis(800),
        Duration.ofMillis(1600),
    };

    private final HttpClient client;
    private final Duration[] backoffs;

    /**
     * Central-rate-limit failover. Applied here, at the single transport choke point, so
     * every caller benefits and no repository's configured URL — hence nothing in {@code jk-lock.toml}
     * changes when it engages.
     */
    private final CentralMirror centralMirror;

    /** Per-host rate-limit memory; shared across the process and persisted. */
    private final HostCooldown cooldown;

    /** Wall clock a {@code Retry-After} is measured against; a test moves it instead of sleeping. */
    private final Clock clock;

    public Http() {
        this(standardClient(), BACKOFFS);
    }

    /**
     * The client every production {@code Http} wraps; visible so a test can pair it with a short
     * backoff. {@link HttpClient.Redirect#NEVER} because {@link #send} follows redirects itself.
     */
    static HttpClient standardClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * A zero-retry instance for tests that assert failure paths: a refused connection fails in
     * one attempt instead of waiting out the full 3.1s backoff ladder. Never used in production
     * — real callers want the retries.
     */
    public static Http failFast() {
        return new Http(standardClient(), new Duration[0]);
    }

    /** Visible for tests — lets the caller shrink the backoff schedule. */
    Http(HttpClient client, Duration[] backoffs) {
        this(client, backoffs, CentralMirror.standard());
    }

    /** Visible for tests — injects the Central failover so its window can be driven deterministically. */
    Http(HttpClient client, Duration[] backoffs, CentralMirror centralMirror) {
        this(client, backoffs, centralMirror, HostCooldown.standard());
    }

    /** Visible for tests — also injects the per-host cooldown store. */
    Http(HttpClient client, Duration[] backoffs, CentralMirror centralMirror, HostCooldown cooldown) {
        this(client, backoffs, centralMirror, cooldown, Clock.SYSTEM);
    }

    /** Visible for tests — also injects the clock a {@code Retry-After} is read against. */
    Http(HttpClient client, Duration[] backoffs, CentralMirror centralMirror, HostCooldown cooldown, Clock clock) {
        this.client = client;
        this.backoffs = backoffs;
        this.centralMirror = centralMirror;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public HttpResponse<byte[]> get(URI uri) throws IOException, InterruptedException {
        return get(uri, Map.of());
    }

    /**
     * GET with extra request headers — used for conditional GET ({@code If-Modified-Since}, {@code
     * If-None-Match}). 304 responses are returned to the caller as-is.
     */
    public HttpResponse<byte[]> get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        checkOffline(uri);
        uri = centralMirror.route(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(60));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        // Opt into transport-level gzip unless the caller already set
        // their own Accept-Encoding (e.g. testing without compression).
        // The response body is transparently decompressed by
        // `gzipAwareByteArray` when the server replies with
        // Content-Encoding: gzip.
        if (!hasHeaderIgnoreCase(headers, "Accept-Encoding")) {
            builder.header("Accept-Encoding", "gzip");
        }
        HttpRequest request = builder.build();
        return sendWithRetry("GET", uri, request, gzipAwareByteArray(), null);
    }

    /**
     * Streaming GET — returns the response with the body as an {@link InputStream} so the caller can
     * pump bytes through a hash / progress / file sink without buffering the whole payload in memory.
     * Same retry policy as {@link #get(URI)} for connect failures and 5xx; mid-stream failures
     * propagate to the caller (no resume).
     *
     * <p>The per-request timeout is generous (15 min) because JDK archives commonly run 100–250 MB
     * and the standard {@code .get} 60s ceiling would cut them off on slow links.
     */
    public HttpResponse<InputStream> getStream(URI uri) throws IOException, InterruptedException {
        return getStream(uri, Map.of());
    }

    /**
     * Streaming GET with extra request headers (e.g. {@code Authorization} for an authenticated
     * repository). Otherwise identical to {@link #getStream(URI)}: same generous timeout and retry
     * policy, body delivered as an {@link InputStream} the caller pumps to a sink.
     */
    public HttpResponse<InputStream> getStream(URI uri, Map<String, String> headers)
            throws IOException, InterruptedException {
        checkOffline(uri);
        uri = centralMirror.route(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofMinutes(15));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        if (!hasHeaderIgnoreCase(headers, "Accept-Encoding")) {
            builder.header("Accept-Encoding", "gzip");
        }
        HttpRequest request = builder.build();
        // Drain the streamed body before each retry so the connection can be reused.
        return sendWithRetry("GET", uri, request, gzipAwareInputStream(), response -> {
            try (var body = response.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
        });
    }

    /**
     * POST an {@code application/x-www-form-urlencoded} body and ask for a JSON reply. Same
     * retry/offline policy as {@link #get(URI)}: retries on connect failures and 5xx, never on 4xx,
     * max 5 attempts.
     *
     * <p>Responses below 500 are returned to the caller as-is — including 4xx. The OAuth device flow
     * (docs/gh-integration.md) relies on this: GitHub signals {@code authorization_pending} / {@code
     * slow_down} with a non-2xx status plus a JSON {@code error} field, so the poll loop needs the
     * body, not an exception. The form values ride the wire URL-encoded; callers must use https URIs
     * for anything sensitive.
     */
    public HttpResponse<byte[]> postForm(URI uri, Map<String, String> form) throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .build();
        return sendWithRetry("POST", uri, request, gzipAwareByteArray(), null);
    }

    private static String urlEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * PUT a byte body with caller-supplied headers (e.g. {@code Authorization}, {@code Content-Type})
     * — the upload primitive for publishing to Maven repositories and object stores
     * (docs/artifact-repos.md). Same offline guard and retry policy as {@link #get(URI)}: PUT is
     * idempotent, so retrying on connect failures and 5xx is safe; 4xx is returned to the caller
     * as-is (auth failures, 409 conflicts, etc.).
     */
    public HttpResponse<byte[]> put(URI uri, byte[] body, Map<String, String> headers)
            throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofMinutes(15)); // large artifacts on slow links
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        HttpRequest request = builder.build();
        return sendWithRetry("PUT", uri, request, HttpResponse.BodyHandlers.ofByteArray(), null);
    }

    /**
     * A callback invoked on a response whose body is abandoned before another request goes out — a
     * retried 5xx, a followed redirect — used by {@link #getStream} to drain the streamed body so the
     * connection can be reused. May throw {@link IOException} (unlike {@link
     * java.util.function.Consumer}).
     */
    @FunctionalInterface
    private interface BodyDrain<T> {
        void handle(HttpResponse<T> response) throws IOException;
    }

    /**
     * The one send-with-retry loop shared by {@link #get}, {@link #getStream}, {@link #postForm},
     * and {@link #put}: retry on connect failures and 5xx (never on 4xx), {@code backoffs.length + 1}
     * attempts with jittered backoff, then throw a verb-tagged {@link IOException}. {@code drain}
     * (nullable) runs on each retried 5xx and each followed redirect before the next request.
     */
    private <T> HttpResponse<T> sendWithRetry(
            String verb,
            URI uri,
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            @Nullable BodyDrain<T> drain)
            throws IOException, InterruptedException {
        IOException lastIo = null;
        int lastStatus = -1;
        for (int attempt = 0; attempt < backoffs.length + 1; attempt++) {
            if (attempt > 0) {
                Thread.sleep(jittered(backoffs[attempt - 1]));
            }
            // Do not ask a host that is already refusing. One 429 costs one request, not one
            // per permit per attempt — six concurrent permits times five attempts would turn a single
            // refusal into thirty more, which is how a quota window gets held open.
            Optional<Instant> cooling = cooldown.until(request.uri().getHost());
            if (cooling.isPresent()) {
                throw new RateLimitedException(request.uri().getHost(), cooling.get());
            }
            try {
                HttpResponse<T> response = send(request, handler, drain);
                int status = response.statusCode();
                if (status == 429) {
                    // Record before rerouting: the limit is a fact about this host whether or not a
                    // mirror can rescue this particular request.
                    cooldown.noteRateLimited(
                            request.uri().getHost(),
                            HostCooldown.parseRetryAfter(
                                    response.headers().firstValue("Retry-After").orElse(null), clock.instant()));
                }
                // Central's per-IP quota. Open the mirror window and reissue this very
                // request against the mirror, so the resolve that tripped the limit still completes
                // rather than failing and being re-run — a re-run would only spend more of a quota
                // that is already exhausted.
                if (status == 429 && centralMirror.matches(request.uri()) && !centralMirror.active()) {
                    centralMirror.noteRateLimited();
                    URI mirrored = centralMirror.route(request.uri());
                    if (!mirrored.equals(request.uri())) {
                        // The refusal's body is abandoned like a retried 5xx's: a streamed one left
                        // unread holds its connection open for as long as the caller holds the stream.
                        if (drain != null) drain.handle(response);
                        return send(reissue(request, mirrored).build(), handler, drain);
                    }
                }
                if (status < 500) {
                    return response;
                }
                if (drain != null) {
                    drain.handle(response);
                }
                lastStatus = status;
            } catch (RedirectRefusedException e) {
                throw e; // a policy answer, not a network fault: the same chain would refuse again
            } catch (IOException e) {
                lastIo = e;
            }
        }
        // SafeUri, not the URI: a repository declared as https://user:token@host/ would otherwise
        // put its credential into every exhausted-retry message, which is journalled text.
        String shown = SafeUri.forMessage(uri);
        if (lastIo != null) {
            throw new IOException(verb + " " + shown + " failed after " + (backoffs.length + 1) + " attempts", lastIo);
        }
        throw new IOException(
                verb + " " + shown + " returned " + lastStatus + " after " + (backoffs.length + 1) + " attempts");
    }

    /**
     * One request and the redirect chain it starts. A hop that stays on the request's origin keeps
     * every header; one that leaves it is re-issued without the caller's credentials. A downgrade
     * from https to http, a chain longer than {@link #MAX_REDIRECTS}, or a 3xx without a usable
     * {@code Location} is an error rather than a 3xx handed back as if it were a result — a caller
     * reading {@code status >= 400} as failure would otherwise take a redirect for a success with
     * an empty body.
     */
    private <T> HttpResponse<T> send(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, @Nullable BodyDrain<T> drain)
            throws IOException, InterruptedException {
        HttpResponse<T> response = client.send(request, handler);
        for (int hops = 0; isRedirect(response.statusCode()); hops++) {
            URI target = redirectTarget(request.uri(), response);
            if (target == null) {
                // Handed back as a result, a Location-less 3xx reads as a success with an empty
                // body to every caller that takes status >= 400 as the failure line.
                if (drain != null) drain.handle(response);
                throw new RedirectRefusedException(response.statusCode() + " redirect without a usable Location from "
                        + SafeUri.forMessage(request.uri()));
            }
            if (hops >= MAX_REDIRECTS) {
                throw new RedirectRefusedException(
                        "too many redirects (" + MAX_REDIRECTS + ") fetching " + SafeUri.forMessage(request.uri()));
            }
            if (!followable(request.uri(), target)) {
                throw new RedirectRefusedException("refusing a redirect from " + SafeUri.forMessage(request.uri())
                        + " to " + SafeUri.forMessage(target) + ": https to http");
            }
            if (drain != null) drain.handle(response);
            request = redirected(request, response.statusCode(), target);
            response = client.send(request, handler);
        }
        return response;
    }

    /**
     * A redirect this client will not follow — too long a chain, a downgrade to http, or a 3xx
     * naming no target. Not retried: the same answer would come back.
     */
    static final class RedirectRefusedException extends IOException {
        RedirectRefusedException(String message) {
            super(message);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** {@code Location} resolved against the request, or null when the response carries none usable. */
    private static @Nullable URI redirectTarget(URI from, HttpResponse<?> response) {
        Optional<String> location = response.headers().firstValue("Location");
        if (location.isEmpty() || location.get().isBlank()) return null;
        try {
            return from.resolve(location.get().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A redirect may upgrade to https or stay put; it may never drop from https to http. */
    static boolean followable(URI from, URI to) {
        return !("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme()));
    }

    /** Same scheme, host and effective port — the boundary a credential is scoped to. */
    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * The request re-aimed at {@code target}: every header when the origin is unchanged, everything
     * but {@link #CREDENTIAL_HEADERS} when it is not. Method and body are the caller's to decide.
     */
    private static HttpRequest.Builder reissue(HttpRequest request, URI target) {
        boolean sameOrigin = sameOrigin(request.uri(), target);
        return HttpRequest.newBuilder(
                        request,
                        (name, value) -> sameOrigin || !CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
                .uri(target);
    }

    /**
     * The next hop of a redirect chain. A 303, or a 301/302 answering a POST, turns into a GET
     * without the body — what browsers do and what the servers sending those statuses expect;
     * 307 and 308 keep the method and body.
     */
    private static HttpRequest redirected(HttpRequest request, int status, URI target) {
        HttpRequest.Builder next = reissue(request, target);
        if (status == 303 || ((status == 301 || status == 302) && "POST".equals(request.method()))) {
            next.GET();
        }
        return next.build();
    }

    private static long jittered(Duration base) {
        long ms = base.toMillis();
        // +/- 10% jitter
        return ms + (long) ((Math.random() - 0.5) * 0.2 * ms);
    }

    /**
     * Body handler that returns the response as a {@code byte} and transparently inflates the
     * payload when the server set {@code Content-Encoding: gzip}. Java's {@link HttpClient} never
     * decompresses on its own (unlike curl), so without this every gzip-aware caller would have to
     * wrap manually.
     *
     * <p>Callers should treat the returned bytes as the canonical body. The response object retains
     * the original {@code Content-Encoding} and {@code Content-Length} headers — those describe the
     * wire, not the decoded payload, so reading them is now a footgun. In practice jk callers don't,
     * so the tradeoff is acceptable.
     */
    static BodyHandler<byte[]> gzipAwareByteArray() {
        return responseInfo -> {
            var upstream = HttpResponse.BodyHandlers.ofByteArray().apply(responseInfo);
            if (!isGzipped(responseInfo)) return upstream;
            return BodySubscribers.mapping(upstream, raw -> {
                try (var in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        };
    }

    /**
     * Streaming counterpart of {@link #gzipAwareByteArray}. Wraps the incoming {@link InputStream}
     * in a {@link GZIPInputStream} when the response is gzip-encoded; the caller's {@code
     * try-with-resources} closes the gzip stream, which in turn releases the underlying connection.
     */
    static BodyHandler<InputStream> gzipAwareInputStream() {
        return responseInfo -> {
            var upstream = HttpResponse.BodyHandlers.ofInputStream().apply(responseInfo);
            if (!isGzipped(responseInfo)) return upstream;
            return BodySubscribers.mapping(upstream, in -> {
                try {
                    return new GZIPInputStream(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        };
    }

    private static boolean isGzipped(HttpResponse.ResponseInfo info) {
        return info.headers()
                .firstValue("Content-Encoding")
                .map(v -> v.trim().equalsIgnoreCase("gzip"))
                .orElse(false);
    }

    private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (String k : headers.keySet()) {
            if (k.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Fail fast when the user passed {@code --offline} (or set {@code JK_OFFLINE} / {@code
     * config.offline = true}). Any outbound HTTP is short-circuited with a clear error before the
     * request is even constructed; callers that need to fall back to cached data should either avoid
     * calling Http entirely in offline mode or catch this and substitute a cache lookup.
     */
    private static void checkOffline(URI uri) throws OfflineException {
        if (SessionContext.current().config().offlineOr(false)) {
            throw new OfflineException(uri);
        }
    }
}
