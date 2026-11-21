// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.ReleaseVerifier;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * One release directory ({@code releases/<version>/}) on a loopback port, signed with a throwaway
 * key: {@code SHA256SUMS} is generated from the artifacts {@link #put} here and {@code
 * SHA256SUMS.sig} signs it, so a test describes a release by naming its files. The knobs model
 * what goes wrong between a release and a client: a status for one path, a manifest that says
 * something else ({@link #freezeSums} then {@link #put}), a body with no declared length. It also
 * serves the signed latest-release pointer ({@link #latest}) with an ETag, answering a matching
 * {@code If-None-Match} with 304.
 */
public final class StubReleaseDirectory implements AutoCloseable {

    private final String version;
    private final HttpServer server;
    private final URI base;
    private final KeyPair key;
    private final ReleaseVerifier verifier;
    private final Map<String, byte[]> artifacts = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private volatile byte @Nullable [] frozenSums;
    private volatile boolean chunked;
    private volatile byte @Nullable [] latestPointer;
    private volatile @Nullable String latestEtag;
    private final List<String> pointerRequests = new CopyOnWriteArrayList<>();

    public StubReleaseDirectory(String version) throws IOException {
        this.version = version;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            key = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("could not create the release key", e);
        }
        verifier = ReleaseVerifier.of(
                List.of(Base64.getEncoder().encodeToString(key.getPublic().getEncoded())));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String prefix = "/releases/" + version + "/";
        server.createContext(prefix, exchange -> {
            String name = exchange.getRequestURI().getPath().substring(prefix.length());
            requested.add(name);
            int status = statuses.getOrDefault(name, 200);
            byte[] body = status != 200 ? null : bodyFor(name);
            if (body == null) {
                exchange.sendResponseHeaders(status == 200 ? 404 : status, -1);
            } else {
                exchange.sendResponseHeaders(200, chunked && artifacts.containsKey(name) ? 0 : body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/releases/latest/LATEST", exchange -> {
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            pointerRequests.add(ifNoneMatch == null ? "" : ifNoneMatch);
            byte[] pointer = latestPointer;
            String etag = latestEtag;
            if (pointer == null) {
                exchange.sendResponseHeaders(404, -1);
            } else if (etag != null && etag.equals(ifNoneMatch)) {
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(304, -1);
            } else {
                if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(200, pointer.length);
                exchange.getResponseBody().write(pointer);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases");
    }

    /** {@code http://127.0.0.1:<port>/releases} — what {@code JK_RELEASES_URL} would name. */
    public URI base() {
        return base;
    }

    /** A verifier trusting this directory's key alone. */
    public ReleaseVerifier verifier() {
        return verifier;
    }

    /** Serve {@code name} with these bytes; the manifest follows unless frozen. */
    public void put(String name, byte[] bytes) {
        artifacts.put(name, bytes);
    }

    /** Answer {@code name} — an artifact, {@code SHA256SUMS} or {@code SHA256SUMS.sig} — with this status. */
    public void status(String name, int status) {
        statuses.put(name, status);
    }

    /** Pin the manifest to this body (still signed), whatever the artifacts say. */
    public void sums(byte[] body) {
        frozenSums = body;
    }

    /** Pin the manifest to what the artifacts hash to now, so a later {@link #put} disagrees with it. */
    public void freezeSums() {
        frozenSums = generatedSums();
    }

    /** Stream artifacts with no {@code Content-Length}, as a proxy or a compressing CDN edge does. */
    public void chunked(boolean chunked) {
        this.chunked = chunked;
    }

    /** Serve {@code latest/LATEST} naming {@code version}, signed with this directory's key, under {@code etag}. */
    public void latest(String version, @Nullable String etag) throws IOException {
        byte[] body = ("version " + version + "\nissued 1757700000\n").getBytes(StandardCharsets.US_ASCII);
        String signature;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key.getPrivate());
            signer.update(body);
            signature = Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the pointer", e);
        }
        latestPointer = (new String(body, StandardCharsets.US_ASCII) + "signature " + signature + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        latestEtag = etag;
    }

    /** The {@code If-None-Match} each pointer request carried ({@code ""} for none), in arrival order. */
    public List<String> pointerRequests() {
        return List.copyOf(pointerRequests);
    }

    /** Every path asked for under the version directory, in arrival order. */
    public List<String> requested() {
        return List.copyOf(requested);
    }

    private byte @Nullable [] bodyFor(String name) throws IOException {
        byte[] frozen = frozenSums;
        byte[] sums = frozen != null ? frozen : generatedSums();
        return switch (name) {
            case "SHA256SUMS" -> sums;
            case "SHA256SUMS.sig" -> sign(sums);
            default -> artifacts.get(name);
        };
    }

    private byte[] generatedSums() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, byte[]> e : new TreeMap<>(artifacts).entrySet()) {
            sb.append(Hashing.sha256Hex(e.getValue()))
                    .append("  ")
                    .append(e.getKey())
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] sums) throws IOException {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key.getPrivate());
            signature.update(sums);
            return (Base64.getEncoder().encodeToString(signature.sign()) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the manifest", e);
        }
    }

    /** The version directory this stub serves. */
    public String version() {
        return version;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
