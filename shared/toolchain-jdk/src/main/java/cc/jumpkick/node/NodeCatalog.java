// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Node's release catalog: {@code index.json} under the distribution root, cached at {@code
 * $JK_STORE_DIR/node-index.json} for 12 h, revalidated with a conditional GET and served from the
 * cache when the network is not there; and each release's {@code SHASUMS256.txt}, cached under
 * {@code $JK_STORE_DIR/node-shasums/} for good, since a release's archives never change.
 */
public final class NodeCatalog {

    public static final Duration DEFAULT_TTL = Duration.ofHours(12);

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withZone(ZoneId.of("GMT"));

    private final Http http;
    private final URI distBase;
    private final Path storeDir;
    private final Duration ttl;
    private Consumer<String> warn = s -> {};

    public NodeCatalog() {
        this(NodeSources.http(), NodeSources.distBase(), JkDirs.store(), DEFAULT_TTL);
    }

    public NodeCatalog(Http http, URI distBase, Path storeDir, Duration ttl) {
        this.http = Objects.requireNonNull(http, "http");
        this.distBase = NodeSources.directory(distBase.toString());
        this.storeDir = Objects.requireNonNull(storeDir, "storeDir");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /** Where "index unreachable, using cached" goes; no-op by default. */
    public NodeCatalog onWarning(Consumer<String> sink) {
        this.warn = Objects.requireNonNull(sink, "sink");
        return this;
    }

    public URI distBase() {
        return distBase;
    }

    /** Every release, newest first. */
    public List<NodeRelease> releases() throws IOException, InterruptedException {
        return releases(false);
    }

    /** As {@link #releases()}; {@code refresh} skips the TTL and asks the network. */
    public List<NodeRelease> releases(boolean refresh) throws IOException, InterruptedException {
        return NodeRelease.parseIndex(new String(indexBody(refresh), StandardCharsets.UTF_8));
    }

    /** {@code archive file name → sha256} for {@code version}, from its {@code SHASUMS256.txt}. */
    public Map<String, String> shasums(String version) throws IOException, InterruptedException {
        Path cached = storeDir.resolve("node-shasums").resolve("v" + version + ".txt");
        String body;
        if (Files.isRegularFile(cached)) {
            body = Files.readString(cached, StandardCharsets.UTF_8);
        } else {
            URI uri = distBase.resolve("v" + version + "/SHASUMS256.txt");
            HttpResponse<byte[]> response = http.get(uri);
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Node " + version + " checksums " + uri + " returned HTTP " + response.statusCode());
            }
            body = new String(response.body(), StandardCharsets.UTF_8);
            AtomicWrites.replace(cached, response.body());
        }
        return parseShasums(body);
    }

    /** The archive of {@code version} for {@code platform}. */
    public URI archive(String version, NodePlatform platform) {
        return distBase.resolve("v" + version + "/" + platform.archiveName(version));
    }

    static Map<String, String> parseShasums(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            String t = line.trim();
            int space = t.indexOf(' ');
            if (space != 64) continue;
            String hex = t.substring(0, 64);
            if (Hashing.checksumFromSidecar(hex, 64).isEmpty()) continue;
            out.put(t.substring(space).trim(), hex.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private byte[] indexBody(boolean refresh) throws IOException, InterruptedException {
        Path cache = storeDir.resolve("node-index.json");
        boolean fresh = !refresh
                && Files.isRegularFile(cache)
                && Files.size(cache) > 0
                && Duration.between(Files.getLastModifiedTime(cache).toInstant(), Clock.SYSTEM.instant())
                                .compareTo(ttl)
                        < 0;
        if (fresh) return Files.readAllBytes(cache);
        URI uri = distBase.resolve("index.json");
        try {
            Map<String, String> headers = Files.isRegularFile(cache)
                    ? Map.of(
                            "If-Modified-Since",
                            HTTP_DATE.format(Files.getLastModifiedTime(cache).toInstant()))
                    : Map.of();
            HttpResponse<byte[]> response = http.get(uri, headers);
            if (response.statusCode() == 304 && Files.isRegularFile(cache)) {
                Files.setLastModifiedTime(cache, FileTime.from(Clock.SYSTEM.instant()));
                return Files.readAllBytes(cache);
            }
            if (response.statusCode() == 200) {
                AtomicWrites.replace(cache, response.body());
                return response.body();
            }
            throw new IOException("Node index " + uri + " returned HTTP " + response.statusCode());
        } catch (IOException e) {
            if (Files.isRegularFile(cache)) {
                warn.accept(
                        "jk: warning — Node index unreachable, using cached " + cache + " (" + e.getMessage() + ")");
                return Files.readAllBytes(cache);
            }
            throw e;
        }
    }
}
