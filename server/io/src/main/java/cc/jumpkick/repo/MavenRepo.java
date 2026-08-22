// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * One Maven-style repository: fetch into {@code repos/<name>/} (Maven layout + {@code .jk} memo).
 * When {@code m2integration} is on, a digest-matching Maven local-repo file is preferred and
 * write-through happens only if that slot is empty or already equal. Offline serves from the
 * named repo store ({@link ArtifactNotFoundException} on miss).
 */
public final class MavenRepo {

    private final String name;
    private final URI baseUrl;
    private final RepoTransport transport;
    private final Cas cas;
    private final RepoArtifactStore repoStore;
    private final RepoCredential credential;
    /** When true, prefer/write the Maven local repository for third-party artifacts. */
    private final boolean m2integration;

    /** TTL + conditional-GET cache for maven-metadata.xml; null for non-HTTP transports. */
    private final MavenMetadataCache metadataCache;

    /**
     * The HTTP client, retained for the small sidecar GETs that are not artifact fetches — currently the
     * {@code.sha1} that confirms an {@code ~/.m2} candidate. Null for non-HTTP transports.
     */
    private final cc.jumpkick.http.Http http;

    /** Central failover + the standing download preference. */
    private final cc.jumpkick.http.CentralMirror centralMirror =
            cc.jumpkick.http.CentralMirror.standard(cc.jumpkick.util.JkDirs.store());

    /** Artifacts pinned this run without an upstream checksum sidecar. */
    private final AtomicInteger missingUpstreamChecksums = new AtomicInteger();

    /** Once-per-instance warn for plaintext http:// base URLs. */
    private final AtomicBoolean httpWarned = new AtomicBoolean();

    public MavenRepo(String name, URI baseUrl, Http http, Cas cas) {
        this(name, baseUrl, http, cas, RepoCredential.ANONYMOUS);
    }

    /**
     * HTTP convenience constructor: selects an {@link HttpTransport} for the URL's scheme via {@link
     * RepoTransports} (which rejects non-http(s)), and authenticates with {@code credential}
     * (anonymous repos pass {@link RepoCredential#ANONYMOUS}). {@code m2integration} defaults to
     * {@code true}. Pass {@code false} to host artifacts only under {@code JK_STORE_DIR}.
     */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, RepoCredential credential) {
        this(name, baseUrl, http, cas, credential, true);
    }

    /** As above, with an explicit {@code m2integration}. */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, RepoCredential credential, boolean m2integration) {
        this(
                name,
                baseUrl,
                RepoTransports.forUrl(baseUrl, Objects.requireNonNull(http, "http")),
                cas,
                credential,
                http,
                m2integration);
    }

    /**
     * General constructor over any {@link RepoTransport} — the entry point for non-HTTP backends
     * (s3://, file://, …) selected by the caller. These don't get the HTTP metadata cache (it has no
     * status/headers to revalidate against). {@code m2integration} defaults to {@code true}.
     */
    public MavenRepo(String name, URI baseUrl, RepoTransport transport, Cas cas, RepoCredential credential) {
        this(name, baseUrl, transport, cas, credential, null, true);
    }

    /** As above, with an explicit {@code m2integration}. */
    public MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            boolean m2integration) {
        this(name, baseUrl, transport, cas, credential, null, m2integration);
    }

    /**
     * Caller-selected transport <em>and</em> the HTTP client, for an http(s) repo whose transport was
     * built with per-repo object-store config.
     *
     * <p>This exists because the transport-only constructors pass {@code null} for the client, and the
     * normal resolve path went through one of them — so for every ordinary build both HTTP-only features
     * silently switched off: the {@code maven-metadata.xml} TTL/conditional-GET cache (which also holds
     * the "reuse a stale copy rather than fail on 429" behaviour) and the {@code ~/.m2} probe. Only a
     * test pinning an override URL took the client-carrying path, which is why the metadata cache looked
     * healthy in tests while never running in practice.
     */
    /** Transport + HTTP client for an http(s) repo. See the note above on why this is separate. */
    public static MavenRepo overTransport(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            Http httpOrNull,
            boolean m2integration) {
        return new MavenRepo(name, baseUrl, transport, cas, credential, httpOrNull, m2integration);
    }

    /**
     * Field-setting constructor. {@code httpOrNull} is the HTTP client when the repo is http(s)
     * (enabling the metadata cache), or {@code null} for a non-HTTP transport. {@code m2integration}
     * is the resolving project's {@code m2integration} value.
     */
    private MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            Http httpOrNull,
            boolean m2integration) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        // Full store for every repo: Maven-layout artifact + {@code .jk} memo under repos/<name>/.
        this.repoStore = RepoArtifactStore.forRepoName(cas.root(), name);
        this.credential = Objects.requireNonNull(credential, "credential");
        this.m2integration = m2integration;
        this.http = httpOrNull;
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

    /**
     * True when this repo carries the HTTP client, i.e. the metadata TTL/conditional-GET cache and the
     * {@code ~/.m2} probe are live. Both silently switch off without it, so it is worth being
     * able to assert on.
     */
    public boolean hasMetadataCache() {
        return metadataCache != null;
    }

    public String name() {
        return name;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public Fetched fetchPom(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.pomPath(coord), true, Leg.RESOLVE);
    }

    public Fetched fetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return fetchArtifact(coord, NO_ABORT);
    }

    /**
     * As {@link #fetchArtifact(Coordinate)}, aborting cooperatively at leg boundaries when
     * {@code abort} turns true (a lock that already failed must not start more
     * downloads). A leg in progress always completes cleanly — the check runs only before the
     * network leg starts and right after the host permit is granted, never mid-download.
     */
    public Fetched fetchArtifact(Coordinate coord, BooleanSupplier abort) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.artifactPath(coord), true, Leg.ARTIFACT, abort, null);
    }

    /**
     * As {@link #fetchArtifact(Coordinate, BooleanSupplier)} but validates a warm local-mirror hit
     * against {@code expectedSha256} (the lock pin). A stale store copy (e.g. an internal repo
     * republished the same GAV and the lock was re-pinned) is evicted and re-fetched from the network
     * instead of failing the whole sync with a checksum-mismatch dead end (JK-2305).
     */
    public Fetched fetchArtifact(Coordinate coord, String expectedSha256, BooleanSupplier abort)
            throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.artifactPath(coord), true, Leg.ARTIFACT, abort, expectedSha256);
    }

    /**
     * Local-only artifact probe (no HTTP). Used by {@link RepoGroup} to hit any repo's CAS mirror
     * before walking remotes that would 404 warm multi-repo re-lock).
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
        // never mirrored; offline version enumeration uses availableVersions.
        return fetch(coord, MavenLayout.metadataPath(coord), false, Leg.RESOLVE);
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

    /**
     * Which leg of the work a fetch belongs to, because the two want opposite repositories.
     *
     * <p>{@link #RESOLVE} — metadata and POMs — decides <em>which versions exist</em>, so it asks Central
     * and only falls back to the mirror once refused; a lagging mirror would otherwise resolve to stale
     * versions. {@link #ARTIFACT} is pinned bytes: the sha256 is already known, so provenance is
     * irrelevant, and preferring the mirror spends its large concurrency budget instead of Sonatype's
     * per-IP quota.
     */
    private enum Leg {
        RESOLVE,
        ARTIFACT
    }

    /** Abort supplier for callers with no abort semantics (POM / metadata legs). */
    private static final BooleanSupplier NO_ABORT = () -> false;

    private Fetched fetch(Coordinate coord, String relativePath, boolean mirror, Leg leg)
            throws IOException, InterruptedException {
        return fetch(coord, relativePath, mirror, leg, NO_ABORT, null);
    }

    private Fetched fetch(
            Coordinate coord,
            String relativePath,
            boolean mirror,
            Leg leg,
            BooleanSupplier abort,
            String expectedSha256)
            throws IOException, InterruptedException {
        if (cc.jumpkick.config.SessionContext.current().config().offlineOr(false)) {
            return fetchOffline(coord, relativePath);
        }
        // warm re-lock — serve immutable GAV paths from repos/<name>/ without re-HTTP.
        // --force always revalidates from the network (checksums re-checked).
        boolean force = cc.jumpkick.config.SessionContext.current().config().forceOr(false);
        if (mirror && !force) {
            Optional<Fetched> local = tryLocalMirror(coord, relativePath);
            if (local.isPresent()) {
                if (expectedSha256 == null || local.get().sha256().equalsIgnoreCase(expectedSha256)) {
                    return local.get();
                }
                // Stale mirror copy against a changed pin: drop it and re-fetch from the network.
                repoStore.evict(relativePath);
            }
        }
        warnPlaintextHttpOnce();
        URI uri = baseUrl.resolve(relativePath);
        // Pinned bytes prefer the mirror; enumeration stays on Central (see Leg).
        URI primary = leg == Leg.ARTIFACT ? centralMirror.routeForDownload(uri) : uri;
        // Before paying for the artifact, see whether the machine's Maven repository already has it
        // . Confirmed against a checksum fetched from THIS repository, so ~/.m2 is only ever a
        // candidate for bytes the remote vouches for.
        if (mirror && !force) {
            Optional<Fetched> fromM2 = tryM2(coord, relativePath, uri);
            if (fromM2.isPresent()) return fromM2.get();
        }
        // Per-host cap around the NETWORK leg only. Warm mirror hits short-circuit
        // above, so re-locks stay uncapped, but a cold lock's fan-out (hundreds of concurrent
        // virtual-thread downloads + sidecar GETs) is bounded to what the host tolerates.
        // boundary checks before host-permit acquisition and again once the permit is
        // granted — a task that queued behind slow downloads must not start a fetch for a lock
        // that failed while it waited. Never checked mid-download (legs finish cleanly).
        checkAbort(abort, coord);
        Downloaded stored;
        try {
            stored = rateLimited(primary, () -> {
                checkAbort(abort, coord);
                return downloadAndVerify(coord, primary, relativePath, mirror);
            });
        } catch (FetchAbortedException e) {
            throw e;
        } catch (IOException e) {
            // The mirror can lag or simply not carry something Central has. Falling back keeps a
            // preference from becoming a dependency.
            if (primary.equals(uri)) throw e;
            checkAbort(abort, coord);
            stored = rateLimited(uri, () -> {
                checkAbort(abort, coord);
                return downloadAndVerify(coord, uri, relativePath, mirror);
            });
        }
        cc.jumpkick.config.SessionContext.current().io().remoteDown(stored.size());
        Path placed = stored.path();
        if (mirror) {
            placed = placeArtifact(coord, relativePath, stored.path(), stored.sha256());
            if (!placed.equals(stored.path())) {
                try {
                    Files.deleteIfExists(stored.path());
                } catch (IOException ignored) {
                    // temp
                }
            }
        }
        return new Fetched(uri, placed, stored.sha256(), stored.size());
    }

    /**
     * Put verified bytes on disk: Maven local repo when integration is on and the slot is empty or
     * already equal; otherwise {@code repos/<name>/}. Never overwrites a mismatched local-repo file.
     */
    private Path placeArtifact(Coordinate coord, String relativePath, Path source, String sha256) throws IOException {
        if (m2integration && cc.jumpkick.config.JkM2Config.resolve().integration()) {
            // Refuse a relativePath (from a possibly hostile GAV) that would escape ~/.m2 (JK-2291).
            Path m2Target = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            Optional<Path> used = writeThroughM2(m2Target, source, relativePath, sha256);
            if (used.isPresent()) return used.get();
        }
        repoStore.materialize(relativePath, source, sha256);
        // Fail loudly rather than returning the transient download temp as the "stored" path: a
        // swallowed store-write error (disk full, permissions) otherwise makes sync report success
        // and the later requirePresent gate throws a misleading "run jk sync -F" loop (JK-2310).
        return repoStore
                .locate(relativePath)
                .orElseThrow(() -> new IOException(
                        "failed to store " + coord.group() + ":" + coord.artifact() + ":" + coord.version() + " at "
                                + relativePath + " (store write failed — check disk space and permissions)"));
    }

    private Optional<Path> writeThroughM2(Path m2Target, Path source, String relativePath, String sha256) {
        try {
            if (Files.isRegularFile(m2Target)) {
                if (!Hashing.sha256Hex(m2Target).equalsIgnoreCase(sha256)) return Optional.empty();
                repoStore.writeMemo(relativePath, m2Target, sha256);
                return Optional.of(m2Target);
            }
            M2CompatWriter.MavenHashes hashes = M2CompatWriter.copyToM2AndHash(source, m2Target);
            M2CompatWriter.writeMavenSidecars(m2Target, hashes.sha1(), hashes.md5());
            M2CompatWriter.writeRemoteRepositories(
                    m2Target.getParent(), name, m2Target.getFileName().toString());
            repoStore.writeMemo(relativePath, m2Target, sha256);
            return Optional.of(m2Target);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Adopt {@code relativePath} out of the Maven local repository when its bytes match the checksum this
     * repository publishes for it.
     *
     * <p>The hash is fetched remotely rather than read from {@code jk-lock.toml} on purpose: it makes the
     * check work during resolve, when no lock entry exists yet, and it keeps the authority with the
     * repository instead of with a directory any {@code mvn install} can write to. A {@code.sha1} is
     * ~40 bytes against a jar that can be tens of megabytes, so the saving is bandwidth — it does not
     * reduce request count, and so does not by itself relieve a per-IP quota.
     *
     * <p>Empty on any doubt whatsoever: lookup disabled, no local file, no HTTP client, sidecar missing
     * or unparseable, or bytes that do not match. Every one of those falls through to the ordinary
     * download, so the worst case is one wasted small GET.
     */
    private Optional<Fetched> tryM2(Coordinate coord, String relativePath, URI uri) {
        if (!m2integration || !cc.jumpkick.config.JkM2Config.resolve().integration()) return Optional.empty();
        if (http == null || !isHttp(baseUrl)) return Optional.empty();
        try {
            Path candidate = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            if (!Files.isRegularFile(candidate)) return Optional.empty();

            // Prefer the collision-resistant .sha256 sidecar; fall back to .sha1 only when the repo
            // doesn't publish one (SHA-1 is chosen-prefix broken, and its match becomes the lock pin
            // for bytes any `mvn install` could have seeded — JK-2321).
            String vouchAlgo;
            Optional<String> advertised = fetchSha256(uri);
            if (advertised.isPresent()) {
                vouchAlgo = "sha256";
                if (!Hashing.fileHex("SHA-256", candidate).equalsIgnoreCase(advertised.get())) {
                    return Optional.empty();
                }
            } else {
                vouchAlgo = "sha1";
                advertised = fetchSha1(uri);
                if (advertised.isEmpty()) return Optional.empty();
                if (!Hashing.fileHex("SHA-1", candidate).equalsIgnoreCase(advertised.get())) {
                    return Optional.empty();
                }
            }

            String sha256 = Hashing.sha256Hex(candidate);
            repoStore.writeMemo(relativePath, candidate, sha256);
            if (cc.jumpkick.config.SessionContext.current().config().verboseOr(false)) {
                System.err.println("jk: adopted " + relativePath + " from Maven local repo (" + vouchAlgo
                        + " confirmed by " + name + ")");
            }
            return Optional.of(new Fetched(uri, candidate, sha256, Files.size(candidate)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The {@code .sha256} this repository publishes beside {@code uri}; empty when absent or malformed. */
    private Optional<String> fetchSha256(URI uri) {
        try {
            var resp = http.get(URI.create(uri + ".sha256"));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return Optional.empty();
            String body = new String(resp.body(), StandardCharsets.UTF_8).strip();
            if (body.isEmpty()) return Optional.empty();
            String first = body.split("\\s+")[0];
            if (first.length() != 64 || !first.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return Optional.empty();
            }
            return Optional.of(first);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The {@code .sha1} this repository publishes beside {@code uri}; empty when absent or malformed. */
    private Optional<String> fetchSha1(URI uri) {
        try {
            var resp = http.get(URI.create(uri + ".sha1"));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return Optional.empty();
            String body = new String(resp.body(), StandardCharsets.UTF_8).strip();
            if (body.isEmpty()) return Optional.empty();
            String first = body.split("\\s+")[0];
            // 40 hex chars, or it is not a SHA-1 (some repos serve an HTML error page with HTTP 200).
            if (first.length() != 40 || !first.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return Optional.empty();
            }
            return Optional.of(first);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private record Downloaded(Path path, String sha256, long size) {}

    /** Per-host concurrency cap around the network leg only; file:// is not capped. */
    private static Downloaded rateLimited(
            URI uri, cc.jumpkick.http.HostRateLimiter.ThrowingSupplier<Downloaded, IOException> work)
            throws IOException, InterruptedException {
        String host = uri.getHost();
        boolean limitHost = host != null && !host.isBlank() && !"file".equalsIgnoreCase(uri.getScheme());
        return limitHost ? cc.jumpkick.http.HostRateLimiter.shared().run(host, work) : work.get();
    }

    private Downloaded downloadAndVerify(Coordinate coord, URI uri, String relativePath, boolean mirror)
            throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        Path shard = cas.root().resolve("repos").resolve(name);
        Files.createDirectories(shard);
        Path tmp = Files.createTempFile(shard, ".put-", ".tmp");
        MessageDigest digest = Hashing.newSha256();
        long size = 0;
        try (InputStream in = transport
                        .fetchStream(uri, credential)
                        .orElseThrow(() -> new ArtifactNotFoundException("not found in " + name + ": " + uri));
                OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
                size += n;
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        String hex = HexFormat.of().formatHex(digest.digest());
        Downloaded stored = new Downloaded(tmp, hex, size);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (mirror) {
            try {
                verifyUpstreamChecksum(coord, uri, relativePath, stored.sha256(), stored.path());
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        // Successful VERIFIED fetch only — a download that fails its upstream checksum must not
        // train the host fetch-duration prior.
        if (ms > 0) {
            try {
                cc.jumpkick.cache.FetchTimings.record(ms);
            } catch (RuntimeException ignored) {
                // advisory
            }
        }
        return stored;
    }

    /**
     * If {@code repos/<name>/} already has a fully materialised artifact ({@code .jk} + bytes),
     * return it without network I/O.
     */
    private Optional<Fetched> tryLocalMirror(Coordinate coord, String relativePath) {
        if (m2integration && cc.jumpkick.config.JkM2Config.resolve().integration()) {
            Path m2File = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            Optional<String> hex = repoStore.readSha256Sidecar(relativePath);
            if (Files.isRegularFile(m2File) && hex.isPresent()) {
                try {
                    if (ArtifactMemo.verify(
                            m2File,
                            repoStore.root() == null
                                    ? ArtifactMemo.jkPath(
                                            cas.root().resolve("repos").resolve(name), relativePath)
                                    : ArtifactMemo.jkPath(repoStore.root(), relativePath),
                            coord.toGav(),
                            hex.get())) {
                        return Optional.of(
                                new Fetched(baseUrl.resolve(relativePath), m2File, hex.get(), Files.size(m2File)));
                    }
                } catch (IOException ignored) {
                    // fall through to the named store
                }
            }
        }
        Optional<Path> located = repoStore.locate(relativePath);
        if (located.isEmpty()) return Optional.empty();
        try {
            Path path = located.get();
            String sha = repoStore.readSha256Sidecar(relativePath).orElseGet(() -> {
                try {
                    return Hashing.sha256Hex(path);
                } catch (IOException e) {
                    return "";
                }
            });
            if (sha.isBlank()) return Optional.empty();
            long size = Files.size(path);
            URI uri = baseUrl.resolve(relativePath);
            return Optional.of(new Fetched(uri, path, sha, size));
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
     * Fetch {@code.sha256} then {@code.sha1} sidecar; mismatch fails closed. Missing sidecar is
     * allowed (TOFU) and counted for the summary line.
     */
    private void verifyUpstreamChecksum(
            Coordinate coord, URI artifactUri, String relativePath, String actualSha256, Path blob)
            throws IOException, InterruptedException {
        Optional<byte[]> sha256Side = transport.fetch(sidecarUri(artifactUri, ".sha256"), credential);
        if (sha256Side.isPresent()) {
            String expected = normalizeChecksum(new String(sha256Side.get(), StandardCharsets.UTF_8));
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
            String expected = normalizeChecksum(new String(sha1Side.get(), StandardCharsets.UTF_8));
            if (isHexChecksum(expected, 40)) {
                String actualSha1 = Hashing.fileHex("SHA-1", blob);
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
        Optional<Fetched> local = tryLocalMirror(coord, relativePath);
        if (local.isPresent()) return local.get();
        throw new ArtifactNotFoundException(
                "offline: " + coord + " (" + relativePath + ") not in local index for " + name);
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
        /** The GAV that was missing, when the thrower knows it; lets callers that walk a POM
         * chain tell "this dep's own POM is absent" from "an ancestor/BOM of it is absent". */
        private final transient Coordinate coordinate;

        public ArtifactNotFoundException(String message) {
            this(message, null);
        }

        public ArtifactNotFoundException(String message, Coordinate coordinate) {
            super(message);
            this.coordinate = coordinate;
        }

        public Coordinate coordinate() {
            return coordinate;
        }
    }

    private static void checkAbort(BooleanSupplier abort, Coordinate coord) throws FetchAbortedException {
        if (abort.getAsBoolean()) {
            throw new FetchAbortedException("fetch aborted before starting " + coord + " (lock already failed)");
        }
    }

    /**
     * Thrown when a fetch is cooperatively skipped at a leg boundary because the caller's abort
     * signal (first materialize failure) turned true. Never wraps a real transfer
     * failure — callers use it to tell abort noise apart from the root cause.
     */
    public static final class FetchAbortedException extends IOException {
        public FetchAbortedException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when the downloaded artifact does not match the repository's published checksum
     * sidecar. Fail closed at lock time.
     */
    public static final class ChecksumMismatchException extends IOException {
        public ChecksumMismatchException(String message) {
            super(message);
        }
    }
}
