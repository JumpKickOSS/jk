// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One Maven-style repository: fetch into {@link Cas}, materialize under {@code repos/<name>/}.
 * Optional one-way {@code ~/.m2} mirror via {@link M2CompatWriter}; offline serves from the repo
 * store only ({@link ArtifactNotFoundException} on miss).
 */
public final class MavenRepo {

    private final String name;
    private final URI baseUrl;
    private final RepoTransport transport;
    private final Cas cas;
    private final RepoArtifactStore repoStore; // full store: artifact + .sha256 sidecar under repos/<name>/
    private final RepoCredential credential;
    private final boolean mirrorToM2; // project.m2install: also copy fetched artifacts into ~/.m2

    /** TTL + conditional-GET cache for maven-metadata.xml; null for non-HTTP transports. */
    private final MavenMetadataCache metadataCache;

    /** Artifacts pinned this run without an upstream checksum sidecar (JK-1065). */
    private final java.util.concurrent.atomic.AtomicInteger missingUpstreamChecksums =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Once-per-instance warn for plaintext http:// base URLs (JK-1065). */
    private final java.util.concurrent.atomic.AtomicBoolean httpWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    public MavenRepo(String name, URI baseUrl, Http http, Cas cas) {
        this(name, baseUrl, http, cas, RepoCredential.ANONYMOUS);
    }

    /**
     * HTTP convenience constructor: selects an {@link HttpTransport} for the URL's scheme via {@link
     * RepoTransports} (which rejects non-http(s)), and authenticates with {@code credential}
     * (anonymous repos pass {@link RepoCredential#ANONYMOUS}). {@code mirrorToM2} defaults to
     * {@code false} — use {@link #MavenRepo(String, URI, Http, Cas, RepoCredential, boolean)} to
     * opt a project's resolve into the {@code ~/.m2} mirror.
     */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, RepoCredential credential) {
        this(name, baseUrl, http, cas, credential, false);
    }

    /** As above, with an explicit {@code mirrorToM2}. */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, RepoCredential credential, boolean mirrorToM2) {
        this(
                name,
                baseUrl,
                RepoTransports.forUrl(baseUrl, Objects.requireNonNull(http, "http")),
                cas,
                credential,
                http,
                mirrorToM2);
    }

    /**
     * General constructor over any {@link RepoTransport} — the entry point for non-HTTP backends
     * (s3://, file://, …) selected by the caller. These don't get the HTTP metadata cache (it has no
     * status/headers to revalidate against). {@code mirrorToM2} defaults to {@code false}.
     */
    public MavenRepo(String name, URI baseUrl, RepoTransport transport, Cas cas, RepoCredential credential) {
        this(name, baseUrl, transport, cas, credential, null, false);
    }

    /** As above, with an explicit {@code mirrorToM2}. */
    public MavenRepo(
            String name, URI baseUrl, RepoTransport transport, Cas cas, RepoCredential credential, boolean mirrorToM2) {
        this(name, baseUrl, transport, cas, credential, null, mirrorToM2);
    }

    /**
     * Field-setting constructor. {@code httpOrNull} is the HTTP client when the repo is http(s)
     * (enabling the metadata cache), or {@code null} for a non-HTTP transport. {@code mirrorToM2}
     * is the resolving project's {@code project.m2install} value — {@code false} for resolvers not
     * tied to a specific project's declared dependencies (tool/plugin/script/git resolution).
     */
    private MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            Http httpOrNull,
            boolean mirrorToM2) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        // Full store for every repo: artifact + .sha256 sidecar under repos/<name>/.
        this.repoStore = RepoArtifactStore.forRepoName(cas.root(), name);
        this.credential = Objects.requireNonNull(credential, "credential");
        this.mirrorToM2 = mirrorToM2;
        // The metadata cache speaks HTTP directly (conditional GET), so it only
        // applies to http(s) repos — a file:// (or other) baseUrl can be paired
        // with an Http client but must keep enumerating via the transport.
        this.metadataCache = (httpOrNull != null && isHttp(this.baseUrl))
                ? new MavenMetadataCache(httpOrNull, cas.root().resolve("metadata"), MavenMetadataCache.DEFAULT_TTL)
                : null;
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    public String name() {
        return name;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public Fetched fetchPom(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.pomPath(coord), true);
    }

    public Fetched fetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.artifactPath(coord), true);
    }

    /**
     * Local-only artifact probe (no HTTP). Used by {@link RepoGroup} to hit any repo's CAS mirror
     * before walking remotes that would 404 (JK-1202 warm multi-repo re-lock).
     */
    public Optional<Fetched> tryLocalArtifact(Coordinate coord) {
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        if (force) return Optional.empty();
        return tryLocalMirror(coord, MavenLayout.artifactPath(coord));
    }

    /** Local-only POM probe (no HTTP). See {@link #tryLocalArtifact}. */
    public Optional<Fetched> tryLocalPom(Coordinate coord) {
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        if (force) return Optional.empty();
        return tryLocalMirror(coord, MavenLayout.pomPath(coord));
    }

    public Fetched fetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        // maven-metadata.xml has no version key and is stale offline, so it's
        // never mirrored; offline version enumeration uses availableVersions().
        return fetch(coord, MavenLayout.metadataPath(coord), false);
    }

    /**
     * Versions of this coordinate's {@code group:artifact} available here. Online: parses {@code
     * maven-metadata.xml}, served through the {@link MavenMetadataCache} (TTL + conditional GET) for
     * HTTP repos so back-to-back resolves don't re-download the index. Offline: lists what the local
     * repo holds. A missing artifact yields an empty list rather than an error so {@link RepoGroup}
     * can union across repos.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        if (cc.jumpkick.config.SessionContext.current().config().offlineOr(false)) {
            return repoStore.versions(coord.group(), coord.artifact());
        }
        try {
            byte[] xml = metadataCache != null
                    ? metadataCache.fetch(baseUrl.resolve(MavenLayout.metadataPath(coord)), credential)
                    : Files.readAllBytes(fetchMetadata(coord).cachePath());
            return MavenMetadata.parse(xml).versions();
        } catch (ArtifactNotFoundException notFound) {
            return List.of();
        }
    }

    private Fetched fetch(Coordinate coord, String relativePath, boolean mirror)
            throws IOException, InterruptedException {
        if (cc.jumpkick.config.SessionContext.current().config().offlineOr(false)) {
            return fetchOffline(coord, relativePath);
        }
        // JK-1088: warm re-lock — serve immutable GAV paths from repos/<name>/ without re-HTTP.
        // --force always revalidates from the network (checksums re-checked).
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        if (mirror && !force) {
            Optional<Fetched> local = tryLocalMirror(coord, relativePath);
            if (local.isPresent()) return local.get();
        }
        warnPlaintextHttpOnce();
        URI uri = baseUrl.resolve(relativePath);
        // Per-host cap around the NETWORK leg only (JK-1221): warm mirror hits short-circuit
        // above, so re-locks stay uncapped, but a cold lock's fan-out (hundreds of concurrent
        // virtual-thread downloads + sidecar GETs) is bounded to what the host tolerates.
        String host = uri.getHost();
        boolean limitHost = host != null && !host.isBlank() && !"file".equalsIgnoreCase(uri.getScheme());
        Cas.Stored stored = limitHost
                ? cc.jumpkick.http.HostRateLimiter.shared().run(host, () -> downloadAndVerify(coord, uri, relativePath, mirror))
                : downloadAndVerify(coord, uri, relativePath, mirror);
        if (mirror) {
            // Primary store: materialise a human-readable, hard-linked copy under repos/<name>/.
            repoStore.materialize(relativePath, stored.path(), stored.sha256());
            if (mirrorToM2) {
                // Opt-in mirror: copy (never hard-link — jk doesn't control writes to ~/.m2).
                Path m2Target = M2Dirs.localRepository().resolve(relativePath);
                try {
                    M2CompatWriter.MavenHashes mavenHashes = M2CompatWriter.copyToM2AndHash(stored.path(), m2Target);
                    M2CompatWriter.writeMavenSidecars(m2Target, mavenHashes.sha1(), mavenHashes.md5());
                    M2CompatWriter.writeRemoteRepositories(
                            m2Target.getParent(), name, m2Target.getFileName().toString());
                } catch (IOException ignored) {
                    // Best-effort: the CAS blob and repos/<name>/ copy are already written; a
                    // ~/.m2 mirror failure is non-fatal.
                }
            }
        }
        return new Fetched(uri, stored.path(), stored.sha256(), stored.size());
    }

    /**
     * The network leg: stream the body straight into the CAS (hashing as it flows, so a
     * multi-hundred-MB JAR never sits in the heap as a single byte[]), then cross-check the
     * published sidecar before pinning (JK-1065). Skips the check for metadata
     * ({@code mirror=false}) — only POMs/artifacts establish the lock pin. Runs under the
     * per-host permit so body + sidecar GETs count as one in-flight unit.
     */
    private Cas.Stored downloadAndVerify(Coordinate coord, URI uri, String relativePath, boolean mirror)
            throws IOException, InterruptedException {
        Cas.Stored stored;
        try (var in = transport
                .fetchStream(uri, credential)
                .orElseThrow(() -> new ArtifactNotFoundException("not found in " + name + ": " + uri))) {
            stored = cas.putStream(in);
        }
        if (mirror) {
            verifyUpstreamChecksum(coord, uri, relativePath, stored.sha256());
        }
        return stored;
    }

    /**
     * If {@code repos/<name>/} already has a fully materialised artifact (sidecar + bytes), return
     * it as a {@link Fetched} without network I/O. Empty when absent / unreadable.
     */
    private Optional<Fetched> tryLocalMirror(Coordinate coord, String relativePath) {
        Optional<Path> path = repoStore.locate(relativePath);
        if (path.isEmpty()) return Optional.empty();
        try {
            // Prefer store sidecar when present; otherwise hash once (immutable GAV).
            String sha = repoStore.storedSha256(relativePath).orElseGet(() -> {
                try {
                    return Hashing.sha256Hex(Files.readAllBytes(path.get()));
                } catch (IOException e) {
                    return "";
                }
            });
            if (sha.isBlank()) return Optional.empty();
            long size = Files.size(path.get());
            URI uri = baseUrl.resolve(relativePath);
            return Optional.of(new Fetched(uri, path.get(), sha, size));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** How many mirrored artifacts were pinned without an upstream .sha256/.sha1 this run. */
    public int missingUpstreamChecksums() {
        return missingUpstreamChecksums.get();
    }

    private void warnPlaintextHttpOnce() {
        String scheme = baseUrl.getScheme();
        if (scheme == null || !"http".equalsIgnoreCase(scheme)) return;
        if (!httpWarned.compareAndSet(false, true)) return;
        System.err.println("jk: warning: repository `"
                + name
                + "` uses plaintext http:// ("
                + baseUrl
                + ") — lock-time fetches can be MITM'd; prefer https");
    }

    /**
     * Fetch {@code .sha256} then {@code .sha1} sidecar; mismatch fails closed. Missing sidecar is
     * allowed (TOFU) and counted for the summary line.
     */
    private void verifyUpstreamChecksum(Coordinate coord, URI artifactUri, String relativePath, String actualSha256)
            throws IOException, InterruptedException {
        Optional<byte[]> sha256Side = transport.fetch(sidecarUri(artifactUri, ".sha256"), credential);
        if (sha256Side.isPresent()) {
            String expected = normalizeChecksum(new String(sha256Side.get(), java.nio.charset.StandardCharsets.UTF_8));
            if (isHexChecksum(expected, 64)) {
                if (!expected.equalsIgnoreCase(actualSha256)) {
                    throw new ChecksumMismatchException("upstream checksum mismatch for "
                            + coord
                            + " from "
                            + name
                            + " ("
                            + relativePath
                            + "): expected sha256 "
                            + expected
                            + " but got "
                            + actualSha256);
                }
                return;
            }
            // Non-hex body (e.g. test servers that path-prefix-match the artifact) → treat as missing.
        }
        Optional<byte[]> sha1Side = transport.fetch(sidecarUri(artifactUri, ".sha1"), credential);
        if (sha1Side.isPresent()) {
            String expected = normalizeChecksum(new String(sha1Side.get(), java.nio.charset.StandardCharsets.UTF_8));
            if (isHexChecksum(expected, 40)) {
                Path blob = cas.pathFor(actualSha256);
                String actualSha1 = Hashing.hashHex("SHA-1", Files.readAllBytes(blob));
                if (!expected.equalsIgnoreCase(actualSha1)) {
                    throw new ChecksumMismatchException("upstream checksum mismatch for "
                            + coord
                            + " from "
                            + name
                            + " ("
                            + relativePath
                            + "): expected sha1 "
                            + expected
                            + " but got "
                            + actualSha1);
                }
                return;
            }
        }
        missingUpstreamChecksums.incrementAndGet();
    }

    private static URI sidecarUri(URI artifactUri, String suffix) {
        return URI.create(artifactUri.toString() + suffix);
    }

    /** Sidecar bodies are often {@code <hex>  <filename>} — take the first hex token. */
    static String normalizeChecksum(String body) {
        if (body == null) return "";
        String t = body.trim();
        if (t.isEmpty()) return "";
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        int tab = t.indexOf('\t');
        if (tab > 0) t = t.substring(0, tab);
        return t.trim();
    }

    /** True when {@code s} is a lowercase/upper hex digest of length {@code len}. */
    static boolean isHexChecksum(String s, int len) {
        if (s == null || s.length() != len) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /**
     * Serve a fetch from the named repo's full store under {@code repos/<name>/}; a coordinate
     * that was never fetched (or mirrored) here is treated as not-found and the resolver falls
     * through cleanly.
     */
    private Fetched fetchOffline(Coordinate coord, String relativePath) throws IOException {
        Optional<Path> found = repoStore.locate(relativePath);
        if (found.isEmpty()) {
            throw new ArtifactNotFoundException(
                    "offline: " + coord + " (" + relativePath + ") not in local index for " + name);
        }
        Path path = found.get();
        // Hash by streaming the file rather than reading it whole — keeps an
        // offline resolve of a large mirrored artifact under the heap cap too.
        return new Fetched(path.toUri(), path, Hashing.sha256Hex(path), Files.size(path));
    }

    private static URI normalize(URI uri) {
        String s = uri.toString();
        if (!s.endsWith("/")) {
            return URI.create(s + "/");
        }
        return uri;
    }

    public record Fetched(URI url, Path cachePath, String sha256, long size) {}

    /** Thrown when the requested artifact returns 404 from this repo. */
    public static final class ArtifactNotFoundException extends IOException {
        public ArtifactNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when the downloaded artifact does not match the repository's published checksum
     * sidecar (JK-1065). Fail closed at lock time.
     */
    public static final class ChecksumMismatchException extends IOException {
        public ChecksumMismatchException(String message) {
            super(message);
        }
    }
}
