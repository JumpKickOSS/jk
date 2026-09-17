// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.StoreWriteGate;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One Maven-style repository: fetch into {@code repos/<name>/} (Maven layout + {@code .jk} memo).
 * When {@code m2integration} is on, the Maven local repository is a source of vouched bytes and a
 * courtesy copy ({@link M2Adoption}); the store is what jk reads. Offline serves from the named
 * repo store ({@link ArtifactNotFoundException} on miss).
 */
public final class MavenRepo {

    /**
     * Central failover + the standing download preference, the instance the transport holds. Static
     * because the four-hour window is a fact about this machine's IP, not about a repository.
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

    /** The Maven local repository as a source of vouched bytes and a courtesy copy; see {@link M2Adoption}. */
    private final M2Adoption m2;

    /** TTL + conditional-GET cache for maven-metadata.xml; null for non-HTTP transports. */
    private final @Nullable MavenMetadataCache metadataCache;

    /**
     * The HTTP client behind the metadata cache, and the one a POM-declared repository is built
     * over. Null for non-HTTP transports.
     */
    private final @Nullable Http http;

    /**
     * {@code allow-unverified} on the declaring repository table: an artifact this repository
     * publishes no checksum sidecar for may be pinned unverified. Off for every repository that is
     * not declared that way, including the built-ins.
     */
    private final boolean allowUnverified;

    private final boolean allowInsecure;

    /** Release versions are asked of this repository; see {@link #serves}. */
    private final boolean servesReleases;

    /** {@code -SNAPSHOT} versions are asked of this repository; off for every built-in remote. */
    private final boolean servesSnapshots;

    /** The network leg: stream under the host's permit, hash, verify against the published sidecar. */
    private final DownloadLeg download;

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
     * built with per-repo object-store config, with the repository's {@code allow-unverified} opt-in.
     * The client is what keeps the metadata cache and the local-repository probe live.
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
     * This repository with the given release/snapshot policy, sharing its store, transport and
     * client. A repository whose policy leaves out a kind of version is never asked for one.
     */
    public MavenRepo withPolicy(boolean releases, boolean snapshots) {
        return new MavenRepo(
                name,
                baseUrl,
                transport,
                cas,
                credential,
                http,
                m2integration,
                allowUnverified,
                allowInsecure,
                releases,
                snapshots);
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
        this(
                name,
                baseUrl,
                transport,
                cas,
                credential,
                httpOrNull,
                m2integration,
                allowUnverified,
                allowInsecure,
                true,
                true);
    }

    /** As above, with the release/snapshot policy; Maven's default is both on. */
    private MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            @Nullable Http httpOrNull,
            boolean m2integration,
            boolean allowUnverified,
            boolean allowInsecure,
            boolean servesReleases,
            boolean servesSnapshots) {
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
        this.servesReleases = servesReleases;
        this.servesSnapshots = servesSnapshots;
        this.http = httpOrNull;
        // The metadata cache speaks HTTP directly (conditional GET), so it only
        // applies to http(s) repos — a file:// (or other) baseUrl can be paired
        // with an Http client but must keep enumerating via the transport.
        this.metadataCache = (httpOrNull != null && isHttp(this.baseUrl))
                ? new MavenMetadataCache(httpOrNull, cas.root().resolve("metadata"), MavenMetadataCache.DEFAULT_TTL)
                : null;
        this.m2 = new M2Adoption(name, transport, credential, isHttp(this.baseUrl), repoStore, m2integration);
        this.download = new DownloadLeg(name, this.baseUrl, transport, credential, storeDir(), allowUnverified);
    }

    /**
     * A repository a dependency's POM declares, over this repository's store and client: anonymous,
     * with neither {@code allow-unverified} nor {@code allow-insecure} — a POM has no table to opt in
     * with, so its repository is held to the rule a project-declared one meets by default — and
     * with the {@code <releases>} / {@code <snapshots>} policy the POM wrote.
     */
    public MavenRepo declaredByPom(String name, URI url, boolean releases, boolean snapshots) {
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
                false,
                releases,
                snapshots);
    }

    /** True when {@code version} is of a kind this repository is asked for; see {@link #servesSnapshots()}. */
    public boolean serves(String version) {
        return Versions.isSnapshot(version) ? servesSnapshots : servesReleases;
    }

    /** True when release versions are asked of this repository. */
    public boolean servesReleases() {
        return servesReleases;
    }

    /** True when {@code -SNAPSHOT} versions are asked of this repository. */
    public boolean servesSnapshots() {
        return servesSnapshots;
    }

    /** The one-word policy for a diagnostic: {@code releases only}, {@code snapshots only} or {@code releases and snapshots}. */
    public String policyLabel() {
        if (servesReleases && servesSnapshots) return "releases and snapshots";
        return servesSnapshots ? "snapshots only" : "releases only";
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    /** True when this repo carries the HTTP client, so the metadata TTL/conditional-GET cache is live. */
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

    /** Local-only artifact probe (no HTTP): {@link RepoGroup} asks every repo's store before any remote. */
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
     * Versions of this coordinate's {@code group:artifact} available here: online from {@code
     * maven-metadata.xml} through the {@link MavenMetadataCache} for HTTP repos, offline from what
     * the store holds. A missing artifact is an empty list, so {@link RepoGroup} can union across repos.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        if (SessionContext.current().config().offlineOr(false)) {
            return repoStore.versions(coord.group(), coord.artifact());
        }
        try {
            byte[] xml = metadataCache != null
                    ? metadataCache.fetch(baseUrl.resolve(MavenLayout.metadataPath(coord)), credential)
                    : Files.readAllBytes(fetchMetadata(coord).cachePath());
            return MavenMetadata.parse(xml, coord.group(), coord.artifact()).versions();
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
    enum Leg {
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
        // A miss this repository already answered is answered again without a request.
        if (!force && RepoMisses.known(uri)) {
            throw new ArtifactNotFoundException("not found in " + name + ": " + uri, coord);
        }
        // Pinned bytes prefer the mirror; enumeration stays on Central (see Leg).
        URI primary = leg == Leg.ARTIFACT ? CENTRAL_MIRROR.routeForDownload(uri) : uri;
        // A Maven local-repository copy this repository's own checksum vouches for costs one small GET.
        if (mirror && !force) {
            Optional<M2Adoption.Adopted> adopted = m2.tryAdopt(relativePath, uri);
            if (adopted.isPresent()) {
                M2Adoption.Adopted a = adopted.get();
                if (leg == Leg.ARTIFACT) download.countVerified();
                return new Fetched(uri, a.placed(), a.sha256(), a.size());
            }
        }
        // The network leg runs under the host's permit (DownloadLeg). Warm mirror hits short-circuit
        // above, so re-locks stay uncapped, but a cold lock's fan-out is bounded to what the host
        // tolerates.
        // Download + placement write .put- temps into repos/<name>/ and promote them in place — a
        // store wipe must not overlap either leg, or the wiped directory holds an open temp
        // (undeletable on Windows) and the promote writes the store right back (StoreWriteGate).
        try (var held = StoreWriteGate.write()) {
            DownloadLeg.Downloaded stored;
            try {
                stored = download.run(coord, primary, relativePath, mirror, leg, expectedSha256, abort);
            } catch (FetchAbortedException e) {
                throw e;
            } catch (IOException e) {
                // The mirror can lag or simply not carry something Central has. Falling back keeps a
                // preference from becoming a dependency.
                if (primary.equals(uri)) throw e;
                stored = download.run(coord, uri, relativePath, mirror, leg, expectedSha256, abort);
            }
            SessionContext.current().io().remoteDown(stored.size());
            RepoMisses.forget(uri);
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
        } catch (ArtifactNotFoundException missing) {
            RepoMisses.record(uri);
            throw missing;
        }
    }

    /**
     * Put verified bytes on disk: this repository's store, which is what every build reads, then
     * the Maven local repository when integration is on and that slot is empty or already equal.
     * Never overwrites a mismatched local-repo file. Returns the store's path.
     */
    private Path placeArtifact(Coordinate coord, String relativePath, Path source, String sha256) throws IOException {
        repoStore.materialize(relativePath, source, sha256);
        // Fail loudly rather than returning the transient download temp as the "stored" path: a
        // swallowed store-write error (disk full, permissions) otherwise makes sync report success
        // and the later requirePresent gate throws a misleading "run jk sync -F" loop.
        Path placed = repoStore
                .locate(relativePath)
                .orElseThrow(() -> new IOException(
                        "failed to store " + coord.group() + ":" + coord.artifact() + ":" + coord.version() + " at "
                                + relativePath + " (store write failed — check disk space and permissions)"));
        m2.writeThrough(relativePath, placed);
        return placed;
    }

    /**
     * If this repository's store already has a fully materialised artifact ({@code .jk} + bytes),
     * return it without network I/O.
     */
    private Optional<Fetched> tryLocalMirror(Coordinate coord, String relativePath) {
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
        return download.verifiedUpstream();
    }

    /** Artifact downloads this run pinned with no published checksum, under {@code allow-unverified}. */
    public int unverifiedAllowed() {
        return download.unverifiedAllowed();
    }

    /**
     * One sentence per artifact this run verified against an {@code .md5} sidecar alone, sorted:
     * the lock output carries each so the weaker digest is on record.
     */
    public List<String> weakChecksumNotes() {
        return download.weakChecksumNotes();
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
     * rather than replaces, and <strong>no userinfo</strong>. {@link #baseUrl()} is interpolated into
     * every artifact's {@code source} field in {@code jk-lock.toml}, into fetch errors and into the
     * journal, so a credential written into the URL must not travel with it; authentication runs
     * through {@link RepoCredentialResolver} and an {@code Authorization} header, never userinfo.
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
