// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A registry that answers {@code 401} to anyone who does not authenticate.
 *
 * <p>The point of the fixture is that it does <em>not</em> answer every request the same way: an
 * unauthenticated caller gets a challenge and nothing else, an authenticated one gets the real
 * distribution API. A stub that served the manifest to anybody would pass whether or not jk
 * attaches a credential, which is exactly how {@code RegistryImage.named(...)} shipped with no
 * credential retriever at all.
 *
 * <p>Enough of the OCI distribution API to pull a (zero-layer) base image and push a built one:
 * the version check, manifests by tag and by digest, blobs, and the three-step blob upload. Plain
 * HTTP on the loopback interface — what a local {@code registry:2} serves, and what docker, podman
 * and jk all read over {@code http://}.
 *
 * <p>The advertised port is a doorman rather than the HTTP server itself. Jib tries HTTPS first and
 * only falls back to HTTP after the handshake fails, which is what a real plain-HTTP registry does
 * by closing the connection; {@code com.sun.net.httpserver} would instead sit waiting for a request
 * line that never arrives, and Jib's failover does not cover a connect timeout. So a connection
 * that opens with a TLS record is closed at once and everything else is relayed.
 */
final class FakeRegistry implements AutoCloseable {

    private static final String MANIFEST_TYPE = "application/vnd.docker.distribution.manifest.v2+json";
    private static final String CONFIG_TYPE = "application/vnd.docker.container.image.v1+json";

    private final HttpServer server;
    private final ServerSocket door;
    private final String expectedAuthorization;
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private final Map<String, byte[]> uploads = new ConcurrentHashMap<>();
    private final Map<String, String> manifests = new ConcurrentHashMap<>();
    private final List<String> refused = new CopyOnWriteArrayList<>();
    private final List<String> served = new CopyOnWriteArrayList<>();
    private final AtomicInteger uploadIds = new AtomicInteger();

    private FakeRegistry(HttpServer server, ServerSocket door, String expectedAuthorization) {
        this.server = server;
        this.door = door;
        this.expectedAuthorization = expectedAuthorization;
    }

    /** Start a registry that serves {@code username}/{@code password} and refuses everyone else. */
    static FakeRegistry requiring(String username, String password) throws IOException {
        return start("Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A registry that serves anyone — the public base image a push test builds on, so the failure
     * it asserts can only be the push.
     */
    static FakeRegistry open() throws IOException {
        return start(null);
    }

    private static FakeRegistry start(String expectedAuthorization) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ServerSocket door = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        FakeRegistry registry = new FakeRegistry(server, door, expectedAuthorization);
        server.createContext("/", registry::dispatch);
        server.start();
        int backend = server.getAddress().getPort();
        Thread.ofVirtual().start(() -> {
            while (!door.isClosed()) {
                try {
                    Socket client = door.accept();
                    Thread.ofVirtual().start(() -> relay(client, backend));
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return registry;
    }

    /** {@code 127.0.0.1:<port>} — the registry half of an image reference. */
    String hostPort() {
        return "127.0.0.1:" + door.getLocalPort();
    }

    /** Close a TLS handshake outright; relay a plain HTTP conversation to the real server. */
    private static void relay(Socket client, int backend) {
        try (client) {
            PushbackInputStream fromClient = new PushbackInputStream(client.getInputStream(), 1);
            int first = fromClient.read();
            if (first < 0 || first == 0x16) return; // EOF, or a TLS ClientHello
            fromClient.unread(first);
            try (Socket upstream = new Socket(InetAddress.getLoopbackAddress(), backend)) {
                Thread request = Thread.ofVirtual().start(() -> pump(fromClient, upstream));
                copy(upstream.getInputStream(), client.getOutputStream());
                request.join();
            }
        } catch (IOException | InterruptedException ignored) {
            // A relayed connection dying is how HTTP connections end; the assertions are elsewhere.
        }
    }

    private static void pump(InputStream in, Socket to) {
        try {
            copy(in, to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException ignored) {
            // upstream already gone
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        in.transferTo(out);
        out.flush();
    }

    /**
     * Publish a runnable-shaped image with no layers at {@code <repo>:<tag>} and return its full
     * reference. Zero layers keeps the fixture to manifest + config while still being an image Jib
     * will build on.
     */
    String publishImage(String repo, String tag) {
        byte[] config = ("{\"created\":\"1970-01-01T00:00:00Z\",\"architecture\":\"amd64\",\"os\":\"linux\","
                        + "\"config\":{},\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]},\"history\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        String configDigest = put(config);
        byte[] manifest = ("{\"schemaVersion\":2,\"mediaType\":\"" + MANIFEST_TYPE + "\",\"config\":{\"mediaType\":\""
                        + CONFIG_TYPE + "\",\"size\":" + config.length + ",\"digest\":\"" + configDigest
                        + "\"},\"layers\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        String manifestDigest = put(manifest);
        manifests.put(repo + ":" + tag, manifestDigest);
        manifests.put(repo + ":" + manifestDigest, manifestDigest);
        return hostPort() + "/" + repo + ":" + tag;
    }

    /** True when something pushed a manifest for {@code <repo>:<tag>}. */
    boolean holdsManifest(String repo, String tag) {
        return manifests.containsKey(repo + ":" + tag);
    }

    /** Every request that arrived without the credential, as {@code METHOD /path}. */
    List<String> refused() {
        return List.copyOf(refused);
    }

    /** Every request that was answered — all of them, for an {@link #open} registry. */
    List<String> served() {
        return List.copyOf(served);
    }

    @Override
    public void close() {
        try {
            door.close();
        } catch (IOException ignored) {
            // already closed
        }
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String where =
                exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (expectedAuthorization != null
                && !expectedAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            refused.add(where);
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"jk-fake-registry\"");
            exchange.getResponseHeaders().add("Docker-Distribution-Api-Version", "registry/2.0");
            respond(
                    exchange,
                    401,
                    "{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}]}");
            return;
        }
        served.add(where);
        try {
            route(exchange, body);
        } catch (RuntimeException e) {
            respond(exchange, 500, "{\"errors\":[{\"code\":\"UNKNOWN\",\"message\":\"" + e + "\"}]}");
        }
    }

    private void route(HttpExchange exchange, byte[] body) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        exchange.getResponseHeaders().add("Docker-Distribution-Api-Version", "registry/2.0");

        if (path.equals("/v2/") || path.equals("/v2")) {
            respond(exchange, 200, "{}");
            return;
        }
        int manifestAt = path.indexOf("/manifests/");
        int blobsAt = path.indexOf("/blobs/");
        if (manifestAt > 0) {
            String repo = path.substring("/v2/".length(), manifestAt);
            String reference = path.substring(manifestAt + "/manifests/".length());
            manifest(exchange, method, repo, reference, body);
            return;
        }
        if (blobsAt > 0 && path.contains("/blobs/uploads/")) {
            String repo = path.substring("/v2/".length(), blobsAt);
            upload(exchange, method, repo, path, query, body);
            return;
        }
        if (blobsAt > 0) {
            String digest = path.substring(blobsAt + "/blobs/".length());
            byte[] blob = blobs.get(digest);
            if (blob == null) {
                respond(exchange, 404, "{\"errors\":[{\"code\":\"BLOB_UNKNOWN\"}]}");
                return;
            }
            exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
            send(exchange, method, 200, blob);
            return;
        }
        respond(exchange, 404, "{\"errors\":[{\"code\":\"UNSUPPORTED\"}]}");
    }

    private void manifest(HttpExchange exchange, String method, String repo, String reference, byte[] body)
            throws IOException {
        if (method.equals("PUT")) {
            String digest = put(body);
            manifests.put(repo + ":" + reference, digest);
            manifests.put(repo + ":" + digest, digest);
            exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
            exchange.getResponseHeaders().add("Location", "/v2/" + repo + "/manifests/" + digest);
            respond(exchange, 201, "");
            return;
        }
        String digest = manifests.get(repo + ":" + reference);
        byte[] manifest = digest == null ? null : blobs.get(digest);
        if (manifest == null) {
            respond(exchange, 404, "{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\"}]}");
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", MANIFEST_TYPE);
        exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
        send(exchange, method, 200, manifest);
    }

    /** The three-step blob upload: {@code POST} to start, {@code PATCH} the bytes, {@code PUT} to commit. */
    private void upload(HttpExchange exchange, String method, String repo, String path, String query, byte[] body)
            throws IOException {
        String digestParam = param(query, "digest");
        if (method.equals("POST")) {
            // A cross-repository mount is declined (202, not 201) so the bytes always arrive here
            // — one upload path to keep correct rather than two.
            String id = "u" + uploadIds.incrementAndGet();
            uploads.put(id, new byte[0]);
            exchange.getResponseHeaders().add("Location", "/v2/" + repo + "/blobs/uploads/" + id);
            exchange.getResponseHeaders().add("Range", "0-0");
            respond(exchange, 202, "");
            return;
        }
        String id = path.substring(path.lastIndexOf('/') + 1);
        byte[] sofar = uploads.getOrDefault(id, new byte[0]);
        byte[] merged = new byte[sofar.length + body.length];
        System.arraycopy(sofar, 0, merged, 0, sofar.length);
        System.arraycopy(body, 0, merged, sofar.length, body.length);
        uploads.put(id, merged);
        if (method.equals("PATCH")) {
            exchange.getResponseHeaders().add("Location", "/v2/" + repo + "/blobs/uploads/" + id);
            exchange.getResponseHeaders().add("Range", "0-" + Math.max(0, merged.length - 1));
            respond(exchange, 202, "");
            return;
        }
        // PUT: commit under the digest the client claims, so a later GET serves the same bytes.
        blobs.put(digestParam == null ? digestOf(merged) : digestParam, merged);
        uploads.remove(id);
        exchange.getResponseHeaders().add("Docker-Content-Digest", digestParam);
        exchange.getResponseHeaders().add("Location", "/v2/" + repo + "/blobs/" + digestParam);
        respond(exchange, 201, "");
    }

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String put(byte[] content) {
        String digest = digestOf(content);
        blobs.put(digest, content);
        return digest;
    }

    private static String digestOf(byte[] content) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, exchange.getRequestMethod(), status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, String method, int status, byte[] body) throws IOException {
        if (method.equals("HEAD")) {
            exchange.getResponseHeaders().add("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
