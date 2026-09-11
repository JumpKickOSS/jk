// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A path-to-bytes HTTP server on {@code 127.0.0.1:0}, as a JUnit extension.
 *
 * <pre>{@code
 * @RegisterExtension
 * final LoopbackHttp http = new LoopbackHttp();
 * ...
 * http.served().put("/com/foo/bar/1.0/bar-1.0.pom", pom.getBytes(UTF_8));
 * resolve(http.base());
 * }</pre>
 *
 * <p>Sixteen suites in five modules carried a byte-identical copy of this: create on an ephemeral
 * loopback port, one catch-all context that serves a {@code Map<String, byte[]>} and 404s anything
 * absent, {@code start()}, a base URI built from the bound port, and {@code stop(0)} after each
 * test. That block is not per-module behaviour, and every divergence between the copies was an
 * accident — three had grown a request recorder, a per-path counter and a pre-serve gate, each
 * spelled differently.
 *
 * <p><strong>What belongs here and what does not.</strong> This owns the <em>socket</em>: binding,
 * lifecycle, 200-or-404, and the three observation hooks. It does not own <em>content</em>. A
 * fixture that impersonates a third party — a Maven repository's {@code maven-metadata.xml}, an OCI
 * manifest, an OSV response — hand-writes its documents in its own module, deliberately unlike what
 * jk emits, because generating them from jk's writer would let a broken writer feed its own broken
 * reader and the round trip would still pass. So {@code MockMavenServer} keeps every Maven document
 * shape and inherits only the plumbing.
 *
 * <p>One piece of content is synthesized: a request for {@code <path>.sha1} whose {@code <path>}
 * is served, and for which no sidecar was seeded, answers with that body's SHA-1. Every Maven
 * repository publishes that file beside every artifact, and a lock refuses to pin bytes no
 * checksum vouches for, so a stub that impersonates a repository must publish it too; {@link
 * #withoutChecksums()} models the repository that does not.
 *
 * <p>Nothing here reaches the public internet and nothing binds a fixed port, so a suite using it
 * belongs in the fast tier.
 */
public class LoopbackHttp implements BeforeEachCallback, AfterEachCallback {

    /**
     * Live path-to-body map. Mutable on purpose — a test describes a server by seeding paths rather
     * than by writing handlers, and bulk-seeding fixtures put entries in directly. Concurrent
     * because the handler reads it on a server thread while the test thread is still writing.
     */
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();

    /** Paths that answer 302 with a {@code Location} instead of a body — see {@link #redirect}. */
    private final Map<String, URI> redirects = new ConcurrentHashMap<>();

    /** Every request path in arrival order — for "did it re-fetch?" without a bespoke handler. */
    private final List<String> requested = new CopyOnWriteArrayList<>();

    /** Request headers per path, latest request wins — for "what did the client send here?". */
    private final Map<String, Map<String, List<String>>> requestHeaders = new ConcurrentHashMap<>();

    private String host = "127.0.0.1";

    private volatile @Nullable Consumer<String> beforeServe;
    private volatile @Nullable Consumer<String> beforeMiss;
    private volatile boolean checksums = true;
    private boolean concurrent;
    private @Nullable HttpServer server;
    private @Nullable ExecutorService pool;
    private @Nullable URI base;

    /**
     * Serve handlers on a thread pool instead of the single dispatch thread.
     *
     * <p>The default executor runs every exchange on one thread, so a handler that blocks blocks
     * the whole server — which silently turns a concurrency test into a serial one: it cannot get
     * two fetches in flight, so it passes whether or not the code under test parallelises.
     * Call this before the first test.
     */
    public LoopbackHttp concurrent() {
        this.concurrent = true;
        return this;
    }

    /** Stop answering {@code <path>.sha1} for served paths: a repository that publishes no checksums. */
    public LoopbackHttp withoutChecksums() {
        this.checksums = false;
        return this;
    }

    /**
     * Bind {@code host} instead of {@code 127.0.0.1}. {@code localhost} still lands on the loopback
     * interface but is a different host <em>name</em>, which is what a cross-host redirect test
     * needs two listeners to disagree on. Call before the first test.
     */
    public LoopbackHttp host(String host) {
        this.host = host;
        return this;
    }

    /**
     * Invoke {@code gate} with the request path after a body is found and before it is written.
     * May block — that is the point: it is how a test holds one response open while another runs.
     */
    public LoopbackHttp beforeServe(Consumer<String> gate) {
        this.beforeServe = gate;
        return this;
    }

    /**
     * Invoke {@code gate} with the request path when no body is registered for it, before the 404
     * is written. May block, like {@link #beforeServe}: it is how a test decides when a miss lands
     * relative to the responses it is holding open.
     */
    public LoopbackHttp beforeMiss(Consumer<String> gate) {
        this.beforeMiss = gate;
        return this;
    }

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        stop();
    }

    /** Bind an ephemeral loopback port and start serving. Also callable directly (re-startable). */
    public void start() throws IOException {
        HttpServer bound = HttpServer.create(new InetSocketAddress(host, 0), 0);
        if (concurrent) {
            pool = Executors.newCachedThreadPool();
            bound.setExecutor(pool);
        }
        bound.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(path);
            requestHeaders.put(path, Map.copyOf(exchange.getRequestHeaders()));
            URI target = redirects.get(path);
            byte[] body = served.get(path);
            if (body == null && checksums && path.endsWith(".sha1")) {
                byte[] artifact = served.get(path.substring(0, path.length() - ".sha1".length()));
                if (artifact != null) body = sha1Hex(artifact).getBytes(StandardCharsets.UTF_8);
            }
            if (target != null) {
                exchange.getResponseHeaders().add("Location", target.toString());
                exchange.sendResponseHeaders(302, -1);
            } else if (body == null) {
                Consumer<String> gate = beforeMiss;
                if (gate != null) gate.accept(path);
                exchange.sendResponseHeaders(404, -1);
            } else {
                Consumer<String> gate = beforeServe;
                if (gate != null) gate.accept(path);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        bound.start();
        server = bound;
        base = URI.create("http://" + host + ":" + bound.getAddress().getPort());
    }

    /**
     * Idempotent, so a test may kill the server mid-method to prove a path needs no network and the
     * after-each teardown stays harmless.
     */
    public void stop() {
        HttpServer running = server;
        if (running != null) {
            running.stop(0);
            server = null;
        }
        ExecutorService threads = pool;
        if (threads != null) {
            threads.shutdownNow();
            pool = null;
        }
    }

    private static String sha1Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code http://<host>:<port>} ({@code 127.0.0.1} unless {@link #host} said otherwise), no trailing slash. */
    public URI base() {
        return Objects.requireNonNull(base, "LoopbackHttp.base() before start()");
    }

    /** {@link #base} as a string with a trailing slash — the shape a repository URL wants. */
    public String baseUrl() {
        return base + "/";
    }

    /** The live path-to-body map; see the field javadoc for why it is exposed. */
    public Map<String, byte[]> served() {
        return served;
    }

    /** Seed {@code path} with {@code body} as UTF-8 — the common case, spelled once. */
    public LoopbackHttp serve(String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Answer {@code path} with a 302 to {@code target} — the redirect half of a two-listener test. */
    public LoopbackHttp redirect(String path, URI target) {
        redirects.put(path, target);
        return this;
    }

    /**
     * The request headers the latest request for {@code path} carried, keyed as the client sent
     * them (the JDK server normalises names to {@code Title-Case}); empty when nothing asked for it.
     */
    public Optional<Map<String, List<String>>> headersFor(String path) {
        return Optional.ofNullable(requestHeaders.get(path));
    }

    /** Request paths in arrival order, including the ones that 404'd. */
    public List<String> requested() {
        return Collections.unmodifiableList(requested);
    }

    /** How many times {@code path} was requested — a re-fetch assertion without a counter field. */
    public long requestsFor(String path) {
        return requested.stream().filter(path::equals).count();
    }

    /** Forget the recorded requests, keeping the routes — for a second phase in one test. */
    public void clearRequests() {
        requested.clear();
    }

    /** The running server, for a suite that must add a bespoke context beside the route table. */
    public HttpServer server() {
        return Objects.requireNonNull(server, "LoopbackHttp.server() before start()");
    }
}
