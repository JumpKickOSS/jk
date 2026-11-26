// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.http.Http;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.repo.ObjectStoreTransport;
import cc.jumpkick.repo.transport.RepoTransports;
import cc.jumpkick.run.JkThreads;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A loopback S3 endpoint with the conditional writes a release relies on: {@code If-None-Match: *}
 * creates a key or answers 412, {@code If-Match} replaces the revision it names or answers 412. A
 * write without either is counted in {@link #unguarded} and refused.
 */
public final class FakeReleaseBucket implements AutoCloseable {

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    public final AtomicInteger unguarded = new AtomicInteger();
    public final AtomicInteger writes = new AtomicInteger();
    private final HttpServer server;

    public FakeReleaseBucket() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(JkThreads.platform());
        server.createContext("/", this::handle);
        server.start();
    }

    /** The bucket's endpoint, as {@code AWS_ENDPOINT_URL} or a repository's {@code endpoint} names it. */
    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public ObjectStoreTransport transport() {
        ObjectStoreConfig cfg = new ObjectStoreConfig("us-east-1", endpoint(), "key", "secret", null);
        return (ObjectStoreTransport) RepoTransports.forUrl(URI.create("s3://releases/"), Http.failFast(), cfg);
    }

    public ReleaseBucket bucket(String prefix, String version) {
        return new ReleaseBucket(
                transport(), (ReleaseTarget.Bucket) ReleaseTarget.parse("s3://releases/" + prefix), version);
    }

    /** The object at {@code key} under the bucket ({@code app/1.0/x}), as text; null when absent. */
    public @Nullable String text(String key) {
        byte[] body = objects.get("/releases/" + key);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    public void putText(String key, String body) {
        objects.put("/releases/" + key, body.getBytes(StandardCharsets.UTF_8));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = objects.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("ETag", etag(body));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String absent = exchange.getRequestHeaders().getFirst("If-None-Match");
            String match = exchange.getRequestHeaders().getFirst("If-Match");
            if (!"*".equals(absent) && match == null) {
                unguarded.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            synchronized (objects) {
                byte[] previous = objects.get(path);
                boolean ok = match == null ? previous == null : previous != null && match.equals(etag(previous));
                if (!ok) {
                    exchange.sendResponseHeaders(412, -1);
                    return;
                }
                objects.put(path, body);
                writes.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        }
    }

    private static String etag(byte[] body) {
        return "\"" + Integer.toHexString(Arrays.hashCode(body)) + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
