// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
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

    /** Every request path in arrival order — for "did it re-fetch?" without a bespoke handler. */
    private final List<String> requested = new CopyOnWriteArrayList<>();

    private volatile Consumer<String> beforeServe;
    private volatile Consumer<String> beforeMiss;
    private boolean concurrent;
    private HttpServer server;
    private ExecutorService pool;
    private URI base;

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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        if (concurrent) {
            pool = Executors.newCachedThreadPool();
            server.setExecutor(pool);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(path);
            byte[] body = served.get(path);
            if (body == null) {
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
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /**
     * Idempotent, so a test may kill the server mid-method to prove a path needs no network and the
     * after-each teardown stays harmless.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /** {@code http://127.0.0.1:<port>}, no trailing slash. */
    public URI base() {
        return base;
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
        return server;
    }
}
