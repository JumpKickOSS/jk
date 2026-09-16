// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.FetchTimings;
import cc.jumpkick.config.JkM2Config;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.http.HostRateLimiter;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.StoreWriteGate;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One Maven-style repository: fetch into {@code repos/<name>/} (Maven layout + {@code .jk} memo).
 * When {@code m2integration} is on, a digest-matching Maven local-repo file is preferred and
 * write-through happens only if that slot is empty or already equal. Offline serves from the
 * named repo store ({@link ArtifactNotFoundException} on miss).
 */
public final class MavenRepo {

    /**
     * Central failover + the standing download preference: {@link CentralMirror#standard()}, the same
     * instance the transport holds. Static because the four-hour window is a fact about this machine's
     * IP rather than about a repository — a per-repository copy would split it.
     */
    private static final CentralMirror CENTRAL_MIRROR = CentralMirror.standard();

    private final String name;
    private final URI baseUrl;
    private final RepoTransport transport;
    private final Cas cas;
    private final RepoArtifactStore repoStore;
    private final RepoCredential credential;
    /** When true, prefer/write the Maven local repository for third-party artifacts. */
    private final boolean m2integration;

    /** TTL + conditional-GET cache for maven-metadata.xml; null for non-HTTP transports. */
    private final @Nullable MavenMetadataCache metadataCache;

    /**
     * The HTTP client, retained for the small sidecar GETs that are not artifact fetches — currently the
     * {@code .sha1} that confirms an {@code ~/.m2} candidate. Null for non-HTTP transports.
     */
    private final @Nullable Http http;

    /**
     * {@code allow-unverified} on the declaring repository table: an artifact this repository
     * publishes no checksum sidecar for may be pinned unverified. Off for every repository that is
     * not declared that way, including the built-ins.
     */
    private final boolean allowUnverified;

    private final boolean allowInsecure;

    /**
     * Artifacts this run whose bytes the repository's published checksum confirmed — downloads and
     * Maven-local adoptions alike.
     */
    private final AtomicInteger verifiedUpstream = new AtomicInteger();

    /** Artifact downloads this run pinned without a published checksum because the repository allows it. */
    private final AtomicInteger unverifiedAllowed = new AtomicInteger();

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
                m2integration,
                false,
                false);
    }

    /**
     * General constructor over any {@link RepoTransport} — the entry point for non-HTTP backends
     * (s3://, file://, …) selected by the caller. These don't get the HTTP metadata cache (it has no
     * status/headers to revalidate against). {@code m2integration} defaults to {@code true}.
     */
    public MavenRepo(String name, URI baseUrl, RepoTransport transport, Cas cas, RepoCredential credential) {
        this(name, baseUrl, transport, cas, credential, null, true, false, false);
    }

    /** As above, with an explicit {@code m2integration}. */
    public MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            boolean m2integration) {
        this(name, baseUrl, transport, cas, credential, null, m2integration, false, false);
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
    /**
     * Transport + HTTP client for an http(s) repo, with the repository's {@code allow-unverified}
     * opt-in. See the note above on why this is separate.
     */
    public static MavenRepo overTransport(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            @Nullable Http httpOrNull,
            boolean m2integration,
            boolean allowUnverified,
            boolean allowInsecure) {
        return new MavenRepo(
                name, baseUrl, transport, cas, credential, httpOrNull, m2integration, allowUnverified, allowInsecure);
    }

    /**
     * Field-setting constructor. {@code httpOrNull} is the HTTP client when the repo is http(s)
     * (enabling the metadata cache), or {@code null} for a non-HTTP transport. {@code m2integration}
     * is the resolving project's {@code m2integration} value; {@code allowUnverified} is the
     * repository table's opt-in.
     */
    private MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            @Nullable Http httpOrNull,
            boolean m2integration,
            boolean allowUnverified,
            boolean allowInsecure) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        // Full store for every repo: Maven-layout artifact + {@code .jk} memo under the store
        // keyed by this repo's ORIGIN — the name is a label, and two projects calling different
        // origins by one name must never read each other's bytes.
        this.repoStore = RepoArtifactStore.forRepository(cas.root(), name, this.baseUrl);
        this.credential = Objects.requireNonNull(credential, "credential");
        this.m2integration = m2integration;
        this.allowUnverified = allowUnverified;
        this.allowInsecure = allowInsecure;
        this.http = httpOrNull;
        // The metadata cache speaks HTTP directly (conditional GET), so it only
        // applies to http(s) repos — a file:// (or other) baseUrl can be paired
        // with an Http client but must keep enumerating via the transport.
        this.metadataCache = (httpOrNull != null && isHttp(this.baseUrl))
                ? new MavenMetadataCache(httpOrNull, cas.root().resolve("metadata"), MavenMetadataCache.DEFAULT_TTL)
                : null;
    }

    /**
     * A repository a dependency's POM declares, over this repository's store and client: anonymous,
     * with neither {@code allow-unverified} nor {@code allow-insecure} — a POM has no table to opt in
     * with, so its repository is held to the rule a project-declared one meets by default.
     */
    public MavenRepo declaredByPom(String name, URI url) {
        Http client = http != null ? http : new Http();
        return new MavenRepo(
                name,
                url,
                RepoTransports.forUrl(url, client),
                cas,
                RepoCredential.ANONYMOUS,
                client,
                m2integration,
                false,
                false);
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

    /** This repository's tree under {@code <store>/repos/}, keyed by its origin. */
    Path storeDir() {
        return Objects.requireNonNull(repoStore.root(), "a remote's store has a root");
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /**
     * True for a plaintext {@code http://} base URL over a network path. Such a repository only
     * reaches here when its table said {@code allow-insecure = true} (or a test pinned it), so this
     * is what the lock summary reports as {@code insecure (allowed)}. A loopback repository that
     * did not opt in is plaintext to nobody but this machine and is not reported.
     */
    public boolean isPlaintext() {
        return "http".equalsIgnoreCase(baseUrl.getScheme())
                && (allowInsecure || !RepositorySpec.loopback(baseUrl.getHost()));
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
     * instead of failing the whole sync with a checksum-mismatch dead end.
     */
    public Fetched fetchArtifact(Coordinate coord, @Nullable String expectedSha256, BooleanSupplier abort)
            throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.artifactPath(coord), true, Leg.ARTIFACT, abort, expectedSha256);
    }

    /**
     * Local-only artifact probe (no HTTP). Used by {@link RepoGroup} to hit any repo's CAS mirror
     * before walking remotes that would 404 warm multi-repo re-lock).
     */
    public Optional<Fetched> tryLocalArtifact(Coordinate coord) {
        boolean force = SessionContext.current().config().forceOr(false);
        if (force) return Optional.empty();
        return tryLocalMirror(coord, MavenLayout.artifactPath(coord));
    }

    /** Local-only POM probe (no HTTP). See {@link #tryLocalArtifact}. */
    public Optional<Fetched> tryLocalPom(Coordinate coord) {
        boolean force = SessionContext.current().config().forceOr(false);
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
        if (SessionContext.current().config().offlineOr(false)) {
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
            @Nullable String expectedSha256)
            throws IOException, InterruptedException {
        if (SessionContext.current().config().offlineOr(false)) {
            return fetchOffline(coord, relativePath);
        }
        // warm re-lock — serve immutable GAV paths from repos/<name>/ without re-HTTP.
        // --force always revalidates from the network (checksums re-checked).
        boolean force = SessionContext.current().config().forceOr(false);
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
        URI uri = baseUrl.resolve(relativePath);
        // Pinned bytes prefer the mirror; enumeration stays on Central (see Leg).
        URI primary = leg == Leg.ARTIFACT ? CENTRAL_MIRROR.routeForDownload(uri) : uri;
        // Before paying for the artifact, see whether the machine's Maven repository already has it
        // . Confirmed against a checksum fetched from THIS repository, so ~/.m2 is only ever a
        // candidate for bytes the remote vouches for.
        if (mirror && !force) {
            Optional<Fetched> fromM2 = tryM2(coord, relativePath, uri, leg);
            if (fromM2.isPresent()) return fromM2.get();
        }
        // Per-host cap around the NETWORK leg only. Warm mirror hits short-circuit
        // above, so re-locks stay uncapped, but a cold lock's fan-out (hundreds of concurrent
        // virtual-thread downloads + sidecar GETs) is bounded to what the host tolerates.
        // boundary checks before host-permit acquisition and again once the permit is
        // granted — a task that queued behind slow downloads must not start a fetch for a lock
        // that failed while it waited. Never checked mid-download (legs finish cleanly).
        checkAbort(abort, coord);
        // Download + placement write .put- temps into repos/<name>/ and promote them in place — a
        // store wipe must not overlap either leg, or the wiped directory holds an open temp
        // (undeletable on Windows) and the promote writes the store right back (StoreWriteGate).
        try (var held = StoreWriteGate.write()) {
            Downloaded stored;
            try {
                stored = rateLimited(primary, () -> {
                    checkAbort(abort, coord);
                    return downloadAndVerify(coord, primary, relativePath, mirror, leg, expectedSha256);
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
                    return downloadAndVerify(coord, uri, relativePath, mirror, leg, expectedSha256);
                });
            }
            SessionContext.current().io().remoteDown(stored.size());
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
    }

    /**
     * Put verified bytes on disk: Maven local repo when integration is on and the slot is empty or
     * already equal; otherwise this repository's store. Never overwrites a mismatched local-repo file.
     */
    private Path placeArtifact(Coordinate coord, String relativePath, Path source, String sha256) throws IOException {
        if (m2integration && JkM2Config.resolve().integration()) {
            // Refuse a relativePath (from a possibly hostile GAV) that would escape ~/.m2.
            Path m2Target = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            Optional<Path> used = writeThroughM2(m2Target, source, relativePath, sha256);
            if (used.isPresent()) return used.get();
        }
        repoStore.materialize(relativePath, source, sha256);
        // Fail loudly rather than returning the transient download temp as the "stored" path: a
        // swallowed store-write error (disk full, permissions) otherwise makes sync report success
        // and the later requirePresent gate throws a misleading "run jk sync -F" loop.
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
                    Objects.requireNonNull(m2Target.getParent()),
                    name,
                    m2Target.getFileName().toString());
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
     * repository instead of with a directory any {@code mvn install} can write to. A {@code .sha1} is
     * ~40 bytes against a jar that can be tens of megabytes, so the saving is bandwidth — it does not
     * reduce request count, and so does not by itself relieve a per-IP quota.
     *
     * <p>Empty on any doubt whatsoever: lookup disabled, no local file, no HTTP client, sidecar missing
     * or unparseable, or bytes that do not match. Every one of those falls through to the ordinary
     * download, so the worst case is one wasted small GET. An adopted artifact counts as verified:
     * the repository's own checksum vouched for it, exactly as it would for a download.
     */
    private Optional<Fetched> tryM2(Coordinate coord, String relativePath, URI uri, Leg leg) {
        if (!m2integration || !JkM2Config.resolve().integration()) return Optional.empty();
        if (http == null || !isHttp(baseUrl)) return Optional.empty();
        try {
            Path candidate = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            if (!Files.isRegularFile(candidate)) return Optional.empty();

            // Prefer the collision-resistant .sha256 sidecar; fall back to .sha1 only when the repo
            // doesn't publish one (SHA-1 is chosen-prefix broken, and its match becomes the lock pin
            // for bytes any `mvn install` could have seeded). The SHA-256 is computed once: it is
            // both the comparison and the memo the adoption records.
            String sha256 = Hashing.sha256Hex(candidate);
            String vouchAlgo;
            Optional<String> advertised = fetchSidecar(uri, ".sha256", 64);
            if (advertised.isPresent()) {
                vouchAlgo = "sha256";
                if (!sha256.equalsIgnoreCase(advertised.get())) {
                    return Optional.empty();
                }
            } else {
                vouchAlgo = "sha1";
                advertised = fetchSidecar(uri, ".sha1", 40);
                if (advertised.isEmpty()) return Optional.empty();
                if (!Hashing.fileHex("SHA-1", candidate).equalsIgnoreCase(advertised.get())) {
                    return Optional.empty();
                }
            }

            repoStore.writeMemo(relativePath, candidate, sha256);
            if (leg == Leg.ARTIFACT) verifiedUpstream.incrementAndGet();
            if (SessionContext.current().config().verboseOr(false)) {
                Log.info("jk: adopted " + relativePath + " from Maven local repo (" + vouchAlgo + " confirmed by "
                        + name + ")");
            }
            return Optional.of(new Fetched(uri, candidate, sha256, Files.size(candidate)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The digest this repository publishes in the {@code suffix} sidecar beside {@code uri}; empty
     * when absent or not a {@code hexLength}-digit digest. Some repositories answer a missing
     * sidecar with an HTML error page under HTTP 200, which is why the body is validated and not
     * merely non-empty.
     */
    private Optional<String> fetchSidecar(URI uri, String suffix, int hexLength) {
        Http client = http;
        if (client == null) return Optional.empty(); // a non-HTTP transport publishes no sidecar this way
        try {
            var resp = client.get(URI.create(uri + suffix));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return Optional.empty();
            return Hashing.checksumFromSidecar(new String(resp.body(), StandardCharsets.UTF_8), hexLength);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private record Downloaded(Path path, String sha256, long size) {}

    /** Per-host concurrency cap around the network leg only; file:// is not capped. */
    private static Downloaded rateLimited(URI uri, HostRateLimiter.ThrowingSupplier<Downloaded, IOException> work)
            throws IOException, InterruptedException {
        String host = uri.getHost();
        boolean limitHost = host != null && !host.isBlank() && !"file".equalsIgnoreCase(uri.getScheme());
        return limitHost ? HostRateLimiter.shared().run(host, work) : work.get();
    }

    private Downloaded downloadAndVerify(
            Coordinate coord, URI uri, String relativePath, boolean mirror, Leg leg, @Nullable String expectedSha256)
            throws IOException, InterruptedException {
        long t0 = Clock.SYSTEM.nanos();
        Path shard = storeDir();
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
        String hex = Hashing.hex(digest.digest());
        Downloaded stored = new Downloaded(tmp, hex, size);
        long ms = (Clock.SYSTEM.nanos() - t0) / 1_000_000L;
        if (mirror) {
            try {
                verifyUpstreamChecksum(coord, uri, relativePath, stored, leg, expectedSha256);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
        // Successful VERIFIED fetch only — a download that fails its upstream checksum must not
        // train the host fetch-duration prior.
        if (ms > 0) {
            try {
                FetchTimings.record(ms);
            } catch (RuntimeException e) {
                // advisory
                Log.debug("downloadAndVerify: advisory", e);
            }
        }
        return stored;
    }

    /**
     * If this repository's store already has a fully materialised artifact ({@code .jk} + bytes),
     * return it without network I/O.
     */
    private Optional<Fetched> tryLocalMirror(Coordinate coord, String relativePath) {
        if (m2integration && JkM2Config.resolve().integration()) {
            Path m2File = MavenLayout.safeResolve(M2Dirs.localRepository(), relativePath);
            Optional<String> hex = repoStore.readSha256Sidecar(relativePath);
            if (Files.isRegularFile(m2File) && hex.isPresent()) {
                try {
                    if (ArtifactMemo.verify(
                            m2File, ArtifactMemo.jkPath(storeDir(), relativePath), coord.toGav(), hex.get())) {
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

    /** Artifacts this run confirmed by the checksum this repository publishes (downloads and adoptions). */
    public int verifiedUpstream() {
        return verifiedUpstream.get();
    }

    /** Artifact downloads this run pinned with no published checksum, under {@code allow-unverified}. */
    public int unverifiedAllowed() {
        return unverifiedAllowed.get();
    }

    /**
     * Check the download against the lock pin when there is one, then against the checksum this
     * repository publishes beside it: {@code .sha256} first, else {@code .sha1}; a mismatch fails
     * closed. The pin is compared first so bytes the lock rejects are discarded here, before
     * anything places them in the store or {@code ~/.m2} — placed bytes would be copied on and
     * re-downloaded on every later sync. With no sidecar at all the bytes are accepted only when
     * the pin vouches for them, when the repository is on local disk ({@code file://} has no
     * network path to tamper with), or when the repository table says {@code allow-unverified =
     * true}; otherwise the fetch is refused, because a pin taken from unverified bytes would
     * protect every later build with a checksum of whatever arrived.
     */
    private void verifyUpstreamChecksum(
            Coordinate coord,
            URI artifactUri,
            String relativePath,
            Downloaded stored,
            Leg leg,
            @Nullable String expectedSha256)
            throws IOException, InterruptedException {
        String actualSha256 = stored.sha256();
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new ChecksumMismatchException("checksum mismatch for " + coord + " from " + name + " (" + relativePath
                    + "): jk-lock.toml pins sha256 " + expectedSha256 + " but got " + actualSha256);
        }
        Optional<byte[]> sha256Side = transport.fetch(sidecarUri(artifactUri, ".sha256"), credential);
        if (sha256Side.isPresent()) {
            Optional<String> parsed =
                    Hashing.checksumFromSidecar(new String(sha256Side.get(), StandardCharsets.UTF_8), 64);
            if (parsed.isPresent()) {
                String expected = parsed.get();
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
                if (leg == Leg.ARTIFACT) verifiedUpstream.incrementAndGet();
                return;
            }
            // Non-hex body (e.g. test servers that path-prefix-match the artifact) → treat as missing.
        }
        Optional<byte[]> sha1Side = transport.fetch(sidecarUri(artifactUri, ".sha1"), credential);
        if (sha1Side.isPresent()) {
            Optional<String> parsed =
                    Hashing.checksumFromSidecar(new String(sha1Side.get(), StandardCharsets.UTF_8), 40);
            if (parsed.isPresent()) {
                String expected = parsed.get();
                // SHA-1 because that is the sidecar Central publishes; the format names the algorithm.
                String actualSha1 = Hashing.fileHex("SHA-1", stored.path());
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
                if (leg == Leg.ARTIFACT) verifiedUpstream.incrementAndGet();
                return;
            }
        }
        // Post-lock: the pin is the authority, and it was taken when the sidecar was checked; it
        // matched above, so a sidecar-less repository needs no further vouching.
        if (expectedSha256 != null) return;
        if ("file".equalsIgnoreCase(baseUrl.getScheme())) return;
        if (!allowUnverified) {
            throw new MissingChecksumException("no upstream checksum for " + coord + " from " + name + " ("
                    + relativePath + "): the repository publishes neither a .sha256 nor a .sha1 sidecar, so the"
                    + " bytes cannot be verified before they are pinned. Set allow-unverified = true on"
                    + " [repositories." + name + "] to pin them anyway.");
        }
        if (leg == Leg.ARTIFACT) unverifiedAllowed.incrementAndGet();
    }

    private static URI sidecarUri(URI artifactUri, String suffix) {
        return URI.create(artifactUri.toString() + suffix);
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

    /**
     * The canonical form of a repository base URL: a trailing slash so {@code resolve} appends
     * rather than replaces, and <strong>no userinfo</strong>.
     *
     * <p>{@link #baseUrl()} is not just a request prefix — {@code LockOrchestrator} interpolates it
     * into every artifact's {@code source} field, so it is committed to {@code jk-lock.toml} and
     * shared with everyone who clones the repository. A base URL declared as
     * {@code https://alice:s3cr3t@nexus.example.com/repo/} (in {@code jk.toml} or, worse, in one
     * developer's {@code ~/.jk/config.toml}) would put that credential in the lockfile, in
     * every fetch error and in the journal. Stripping it here costs nothing: authentication runs
     * through {@link RepoCredentialResolver} and an {@code Authorization} header,
     * and the JDK's {@code HttpClient} never authenticates from userinfo — so the credential half
     * of such a URL was inert on the wire and live everywhere else.
     */
    private static URI normalize(URI uri) {
        URI safe = Objects.requireNonNull(SafeUri.withoutUserInfo(uri));
        String s = safe.toString();
        if (!s.endsWith("/")) {
            return URI.create(s + "/");
        }
        return safe;
    }

    public record Fetched(URI url, Path cachePath, String sha256, long size) {}

    /**
     * Thrown at lock time when a repository publishes no checksum sidecar for an artifact and its
     * table does not say {@code allow-unverified = true}.
     */
    public static final class MissingChecksumException extends IOException {
        public MissingChecksumException(String message) {
            super(message);
        }
    }

    /** Thrown when the requested artifact returns 404 from this repo. */
    public static final class ArtifactNotFoundException extends IOException {
        /** The GAV that was missing, when the thrower knows it; lets callers that walk a POM
         * chain tell "this dep's own POM is absent" from "an ancestor/BOM of it is absent". */
        private final transient @Nullable Coordinate coordinate;

        public ArtifactNotFoundException(String message) {
            this(message, null);
        }

        public ArtifactNotFoundException(String message, @Nullable Coordinate coordinate) {
            super(message);
            this.coordinate = coordinate;
        }

        public @Nullable Coordinate coordinate() {
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
