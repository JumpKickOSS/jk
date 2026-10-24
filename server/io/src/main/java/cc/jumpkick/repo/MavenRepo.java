// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.http.ConnectFaults;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.HttpStatusException;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.StoreWriteGate;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One Maven-style repository: fetch into where its store keeps artifacts (Maven layout + {@code .jk}
 * memo): the Maven local repository for Central and the repositories {@code settings.xml}
 * declares, {@code repos/<id>/} for the rest ({@link M2Backing}). A file already in the local
 * repository is used where it is, with no download, once its bytes verify as the lock's pin or,
 * unpinned, as the checksum the repository publishes; one the repository disowns is evicted
 * (removed only if jk put it there) and downloaded again. Offline serves from the store
 * ({@link ArtifactNotFoundException} on miss).
 */
public final class MavenRepo {

    /**
     * Central failover + the standing download preference, the instance the transport holds. Static
     * because the four-hour window is a fact about this machine's IP, not about a repository.
     */
    private static final CentralMirror CENTRAL_MIRROR = CentralMirror.standard();

    private final String name;
    private final URI baseUrl;

    /**
     * The settings.xml mirror this repository's requests go to, or null when they go to {@link
     * #baseUrl}. A transport fact only: name, {@link #baseUrl} and the store's origin key are the
     * repository's own.
     */
    private final @Nullable Mirror mirror;

    /** Where a request for a relative path is opened: the mirror's URL, else {@link #baseUrl}. */
    private final URI fetchBase;

    private final RepoTransport transport;
    private final Cas cas;
    private final RepoArtifactStore repoStore;
    private final RepoCredential credential;
    /** When true, Central and the repositories {@code settings.xml} declares keep their artifacts in the Maven local repository. */
    private final boolean m2integration;

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

    /**
     * The fault that put this repository out of the job, once a request met one — nothing answers
     * at its address, or a {@linkplain #passable passable} one refuses access — so it is asked
     * nothing more for the rest of the job; else null. An address that answers nothing is also
     * remembered process-wide by {@link ConnectFaults}, so a fresh object over the same URL is
     * refused before it dials as well.
     */
    private final AtomicReference<@Nullable String> unreachable = new AtomicReference<>();

    /** {@code optional = true} on the declaring table, or a repository a dependency's POM declares. */
    private final boolean optional;

    /** A settings.xml mirror standing in for a repository: the URL its requests open, the mirror's credential, its label. */
    public record Mirror(String id, URI url, RepoCredential credential, String label) {}

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
                snapshots,
                mirror,
                optional);
    }

    /**
     * This repository as one the resolve can do without ({@code optional = true}): once nothing
     * answers at its address it is passed over like a blocked one instead of stopping the resolve.
     */
    public MavenRepo withOptional(boolean optional) {
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
                servesReleases,
                servesSnapshots,
                mirror,
                optional);
    }

    /**
     * This repository with every request opened at {@code mirror}'s URL and authenticated with the
     * mirror's credential, sharing its store, policy and client. The transport is re-selected for
     * the mirror's scheme. Name, {@link #baseUrl()} and the store are unchanged: the lock still
     * records this repository, and a lock written through a mirror is byte-identical to one
     * written without.
     */
    public MavenRepo mirroredThrough(Mirror mirror) {
        Http client = http != null ? http : new Http();
        return new MavenRepo(
                name,
                baseUrl,
                RepoTransports.forUrl(mirror.url(), client),
                cas,
                mirror.credential(),
                client,
                m2integration,
                allowUnverified,
                allowInsecure,
                servesReleases,
                servesSnapshots,
                mirror,
                optional);
    }

    /** The settings.xml mirror this repository's requests go through, when one applies. */
    public Optional<Mirror> mirror() {
        return Optional.ofNullable(mirror);
    }

    /**
     * The credential every request carries: the mirror's when a {@link #mirror()} answers the
     * requests, else the one resolved for this repository. {@link RepoCredential.Anonymous} for a
     * public repository.
     */
    public RepoCredential credential() {
        return credential;
    }

    /** Where a request for a relative path opens: the mirror's URL, else {@link #baseUrl()}. */
    public URI fetchBase() {
        return fetchBase;
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
                true,
                null,
                false);
    }

    /** As above, with the release/snapshot policy — Maven's default is both on — and the mirror, if any. */
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
            boolean servesSnapshots,
            @Nullable Mirror mirror,
            boolean optional) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.mirror = mirror;
        this.fetchBase = mirror == null ? this.baseUrl : normalize(mirror.url());
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        // Full store for every repo: Maven-layout artifact + {@code .jk} memo under the store
        // keyed by this repo's ORIGIN — the name is a label, and two projects calling different
        // origins by one name must never read each other's bytes.
        this.repoStore = RepoArtifactStore.forRepository(cas.root(), name, this.baseUrl, m2integration);
        this.credential = Objects.requireNonNull(credential, "credential");
        this.m2integration = m2integration;
        this.allowUnverified = allowUnverified;
        this.allowInsecure = allowInsecure;
        this.servesReleases = servesReleases;
        this.servesSnapshots = servesSnapshots;
        this.optional = optional;
        this.http = httpOrNull;
        // The metadata cache speaks HTTP directly (conditional GET), so it only
        // applies to http(s) repos — a file:// (or other) baseUrl can be paired
        // with an Http client but must keep enumerating via the transport. A loopback port names
        // whatever process holds it now, so its list is never trusted for the day: every ask is a
        // conditional GET, answered 304 when the server still holds the body it stored.
        Duration ttl =
                RepositorySpec.loopback(this.fetchBase.getHost()) ? Duration.ZERO : MavenMetadataCache.DEFAULT_TTL;
        this.metadataCache = (httpOrNull != null && isHttp(this.fetchBase))
                ? new MavenMetadataCache(httpOrNull, metadataDir(), ttl)
                : null;
        this.download = new DownloadLeg(name, this.fetchBase, transport, credential, storeDir(), allowUnverified);
    }

    /**
     * A repository a dependency's POM declares, over this repository's store and client: anonymous,
     * with neither {@code allow-unverified} nor {@code allow-insecure} — a POM has no table to opt in
     * with, so its repository is held to the rule a project-declared one meets by default — with
     * the {@code <releases>} / {@code <snapshots>} policy the POM wrote, and {@linkplain #passable
     * passable}, as Maven falls through a POM's repository that answers nothing.
     */
    public MavenRepo declaredByPom(String name, URI url, boolean releases, boolean snapshots) {
        Http client = (http != null ? http : new Http()).withoutSilenceRetries();
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
                snapshots,
                null,
                true);
    }

    /**
     * True when the resolve passes this repository over once nothing answers at its address or it
     * refuses access (401, 403), as it does a blocked one, instead of stopping: one declared {@code
     * optional}, and any at a loopback address — a developer's local Nexus no other machine runs.
     */
    public boolean passable() {
        return passable(optional, baseUrl);
    }

    /**
     * {@link #passable()} for a repository declared {@code optional} or not at {@code url}; such a
     * repository is built over {@link Http#withoutSilenceRetries()}, so it is passed over after one
     * silent attempt rather than the whole ladder.
     */
    public static boolean passable(boolean optional, URI url) {
        return optional || RepositorySpec.loopback(url.getHost());
    }

    /** The fault that put this repository out of the rest of the job, or null. */
    public @Nullable String unreachableFault() {
        return unreachable.get();
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

    /** The directory the metadata cache keeps its bodies in, and the resolver its per-POM answers. */
    public Path metadataDir() {
        return cas.root().resolve("metadata");
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

    /**
     * The repository's own URL — the lock's {@code source} and the store's origin are built from
     * it — whether or not a {@link #mirror()} answers the requests.
     */
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
     * The store's copy of {@code coord}'s artifact when it may answer for it, before any remote is
     * asked ({@link RepoGroup} probes every repository's store first). Under a pin, a copy whose
     * digest is the pin; a copy with another digest is evicted. Without one — a lock about to be
     * written — a copy the repository's published checksum confirms ({@link #confirmed}), which
     * costs the sidecar reads and never the artifact. Empty on a miss, under {@code --force}, and
     * once a disowned copy is evicted.
     */
    public Optional<Fetched> tryLocalArtifact(Coordinate coord, @Nullable String expectedSha256, BooleanSupplier abort)
            throws IOException, InterruptedException {
        boolean force = SessionContext.current().config().forceOr(false);
        if (force) return Optional.empty();
        return mirrored(coord, MavenLayout.artifactPath(coord), Leg.ARTIFACT, expectedSha256, abort);
    }

    /** The store's copy of {@code coord}'s POM, no HTTP: a POM is not pinned, so nothing confirms it. */
    public Optional<Fetched> tryLocalPom(Coordinate coord) {
        boolean force = SessionContext.current().config().forceOr(false);
        if (force) return Optional.empty();
        return tryLocalMirror(MavenLayout.pomPath(coord), null);
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
        refuseIfUnreachable();
        try {
            byte[] xml = metadataCache != null
                    ? metadataCache.fetch(fetchBase.resolve(MavenLayout.metadataPath(coord)), credential)
                    : Files.readAllBytes(fetchMetadata(coord).cachePath());
            return MavenMetadata.parse(xml, coord.group(), coord.artifact()).versions();
        } catch (ArtifactNotFoundException notFound) {
            return List.of();
        } catch (IOException transport) {
            throw unreachableOr(transport);
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
            return fetchOffline(coord, relativePath, expectedSha256);
        }
        // warm re-lock — serve immutable GAV paths from repos/<name>/ without re-HTTP.
        // --force always revalidates from the network (checksums re-checked).
        boolean force = SessionContext.current().config().forceOr(false);
        if (mirror && !force) {
            Optional<Fetched> local = mirrored(coord, relativePath, leg, expectedSha256, abort);
            if (local.isPresent()) return local.get();
        }
        URI uri = fetchBase.resolve(relativePath);
        refuseIfUnreachable();
        // A miss this repository already answered is answered again without a request; a catalog's
        // for an hour, a POM's or artifact's for the metadata cache's day.
        RepoMisses misses = relativePath.endsWith("maven-metadata.xml") ? RepoMisses.CATALOGS : RepoMisses.FETCHES;
        if (!force && misses.known(uri)) {
            throw new ArtifactNotFoundException("not found in " + name + ": " + uri, coord);
        }
        // Pinned bytes prefer the mirror; enumeration stays on Central (see Leg).
        URI primary = leg == Leg.ARTIFACT ? CENTRAL_MIRROR.routeForDownload(uri) : uri;
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
            misses.forget(uri);
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
            misses.record(uri);
            throw missing;
        } catch (FetchAbortedException aborted) {
            throw aborted;
        } catch (IOException transport) {
            throw unreachableOr(transport);
        }
    }

    /** The fault an earlier request met, thrown again without a request, once this repository is unreachable. */
    private void refuseIfUnreachable() throws RepositoryUnreachableException {
        String fault = unreachable.get();
        if (fault != null) throw new RepositoryUnreachableException(name, baseUrl, fault, null);
    }

    /**
     * {@code transport} as this repository's unreachable failure when its cause is connect-level,
     * or — for a {@linkplain #passable passable} one — when it got no HTTP answer at all (reset,
     * dropped, timed out) or an answer refusing access (401, 403), which Maven passes over as
     * well: remembered, so the repository is asked nothing more. Else {@code transport} itself.
     */
    private IOException unreachableOr(IOException transport) {
        String fault = ConnectFaults.describe(transport);
        if (fault == null && passable()) fault = silence(transport);
        if (fault == null && passable()) fault = refusal(transport);
        if (fault == null) return transport;
        unreachable.compareAndSet(null, fault);
        return new RepositoryUnreachableException(name, baseUrl, fault, transport);
    }

    /** The no-answer failure in {@code failure}'s cause chain as one line naming what it met, or null. */
    private static @Nullable String silence(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof Http.NoAnswerException noAnswer) {
                Throwable met = noAnswer.getCause() == null ? noAnswer : noAnswer.getCause();
                String detail = met.getMessage();
                return met.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
            }
        }
        return null;
    }

    /** The 401 or 403 in {@code failure}'s cause chain as one line naming it, or null. */
    private static @Nullable String refusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpStatusException answer && answer.refusesAccess()) {
                return "HTTP " + answer.status()
                        + (answer.status() == 401 ? ": it asks for credentials" : ": access refused");
            }
        }
        return null;
    }

    /**
     * Put verified bytes on disk where this repository keeps them — the Maven local repository for
     * Central and the repositories {@code settings.xml} declares, its own store tree for the rest —
     * and write the memo that says jk downloaded them. A slot in the local repository holding other
     * bytes that jk did not put there is left alone, and the placement fails rather than replace it.
     */
    private Path placeArtifact(Coordinate coord, String relativePath, Path source, String sha256) throws IOException {
        repoStore.materialize(relativePath, source, sha256, true);
        // Fail loudly rather than returning the transient download temp as the "stored" path: a
        // swallowed store-write error (disk full, permissions) otherwise makes sync report success
        // and the later requirePresent gate throws a misleading "run jk sync -F" loop.
        Path placed = repoStore
                .locate(relativePath)
                .orElseThrow(() -> new IOException(
                        "failed to store " + coord.group() + ":" + coord.artifact() + ":" + coord.version() + " at "
                                + relativePath + " (store write failed — check disk space and permissions)"));
        if (repoStore.verify(relativePath, sha256) != RepoArtifactStore.IndexState.VERIFIED) {
            throw new IOException(placed + " holds other bytes than the " + sha256 + " " + coord.group() + ":"
                    + coord.artifact() + ":" + coord.version() + " is pinned to, and jk did not put it there: "
                    + "remove the file, or point JK_M2_LOCAL elsewhere");
        }
        if (repoStore.m2Backed() && repoStore.fetchedByJk(relativePath)) writeMavenSidecars(placed);
        return placed;
    }

    /**
     * What Maven expects beside a file in its local repository: the {@code .sha1} and {@code .md5}
     * sidecars and the {@code _remote.repositories} line naming the repository that served it. Only
     * for a file jk just downloaded; best-effort, since a Maven that wants them re-fetches
     * and jk's own build has no use for them.
     */
    private void writeMavenSidecars(Path placed) {
        try {
            if (!Files.exists(placed.resolveSibling(placed.getFileName() + ".sha1"))) {
                M2CompatWriter.MavenHashes hashes = M2CompatWriter.hashesOf(placed);
                M2CompatWriter.writeMavenSidecars(placed, hashes.sha1(), hashes.md5());
            }
            M2CompatWriter.writeRemoteRepositories(
                    Objects.requireNonNull(placed.getParent()),
                    name,
                    placed.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            Log.debug("writeMavenSidecars: the local repository's checksums are a courtesy to Maven", e);
        }
    }

    /**
     * The store's copy of {@code relativePath} when it may answer for {@code coord}: a copy whose
     * bytes verify as the pin, or — without one — as what its memo recorded ({@link
     * #tryLocalMirror}); an unpinned artifact is then confirmed by the checksum the repository
     * publishes ({@link #confirmed}). A POM or metadata file is served as stored.
     */
    private Optional<Fetched> mirrored(
            Coordinate coord, String relativePath, Leg leg, @Nullable String expectedSha256, BooleanSupplier abort)
            throws IOException, InterruptedException {
        // A file in the Maven local repository that jk has never verified is only a candidate: the
        // repository's published checksum is the authority, and none published means no vouching.
        boolean unvouched = repoStore.m2Backed()
                && expectedSha256 == null
                && repoStore.readSha256Sidecar(relativePath).isEmpty();
        Optional<Fetched> local = tryLocalMirror(relativePath, expectedSha256);
        if (local.isEmpty() || expectedSha256 != null || (leg != Leg.ARTIFACT && !unvouched)) return local;
        return confirmed(coord, relativePath, local.get(), leg, abort, unvouched);
    }

    /**
     * {@code local} once the checksum this repository publishes agrees with it, the same sidecar
     * reads a download gets, so a store that holds the wrong bytes for a coordinate — a killed
     * write, a jar copied in by hand, an artifact upstream republished — is not what a fresh lock
     * pins. A disowned copy is evicted and the fetch goes on to the network with a note; a copy the
     * repository publishes no checksum for follows the download's {@code allow-unverified} rule, so
     * a refused one falls through to the download that refuses it by name. Offline, under an abort
     * (the lock has already failed and pins nothing) or with the repository unreachable the store
     * answers as it stands.
     */
    private Optional<Fetched> confirmed(
            Coordinate coord,
            String relativePath,
            Fetched local,
            Leg leg,
            BooleanSupplier abort,
            boolean requirePublished)
            throws IOException, InterruptedException {
        if (SessionContext.current().config().offlineOr(false) || abort.getAsBoolean() || unreachable.get() != null) {
            return Optional.of(local);
        }
        URI uri = CENTRAL_MIRROR.routeForDownload(fetchBase.resolve(relativePath));
        try {
            if (download.confirmStored(
                    coord, uri, relativePath, local.cachePath(), local.sha256(), leg, requirePublished)) {
                return Optional.of(local);
            }
            repoStore.evict(relativePath);
            return Optional.empty();
        } catch (MissingChecksumException refused) {
            return Optional.empty();
        } catch (IOException transport) {
            // Nothing answered for the sidecar: the store answers, as it does offline. A dead
            // address is remembered so the rest of the job stops dialing it.
            Log.debug("confirmed: " + coord + " from " + name + " served from the store unconfirmed", transport);
            unreachableOr(transport);
            return Optional.of(local);
        }
    }

    /**
     * This repository's store copy of {@code relativePath} once its bytes are known, without
     * network I/O: under a pin, a copy that verifies as the pin; without one, a copy that is still
     * what its {@code .jk} memo recorded. A memo that still describes the file (size and mtime)
     * answers in one stat; otherwise the file is re-hashed and the memo refreshed. A copy that
     * verifies as neither — a torn or truncated file beside an intact memo, a copy whose pin moved
     * — is evicted so the network leg replaces it. Empty on a miss and after an eviction.
     */
    private Optional<Fetched> tryLocalMirror(String relativePath, @Nullable String expectedSha256) {
        Optional<Path> located = repoStore.locate(relativePath);
        if (located.isEmpty()) return Optional.empty();
        Path path = located.get();
        try {
            String sha = expectedSha256 != null
                    ? expectedSha256
                    : repoStore.readSha256Sidecar(relativePath).orElseGet(() -> hashQuietly(path));
            if (sha.isBlank() || repoStore.verify(relativePath, sha) != RepoArtifactStore.IndexState.VERIFIED) {
                repoStore.evict(relativePath);
                return Optional.empty();
            }
            URI uri = baseUrl.resolve(relativePath);
            return Optional.of(new Fetched(uri, path, sha.toLowerCase(Locale.ROOT), Files.size(path)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** {@code path}'s SHA-256 for a store copy that has no memo to speak for it; blank when unreadable. */
    private static String hashQuietly(Path path) {
        try {
            return Hashing.sha256Hex(path);
        } catch (IOException e) {
            return "";
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
     * One sentence per artifact whose checksum deserved a line this run, sorted: verified against an
     * {@code .md5} sidecar alone, or a store copy this repository disowned and the lock replaced.
     */
    public List<String> checksumNotes() {
        return download.checksumNotes();
    }

    /**
     * Serve a fetch from the named repo's full store under {@code repos/<name>/}; a coordinate
     * that was never fetched (or mirrored) here is treated as not-found and the resolver falls
     * through cleanly.
     */
    private Fetched fetchOffline(Coordinate coord, String relativePath, @Nullable String expectedSha256)
            throws IOException {
        Optional<Fetched> local = tryLocalMirror(relativePath, expectedSha256);
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

    /**
     * An artifact on disk: the URL it answers for, where it lies, and its SHA-256 and size. The
     * digest is the digest of the bytes at {@code cachePath} as this process established it — hashed
     * as a download landed or an adopted copy was read, or, for a store copy, verified against the
     * file by its memo or a re-hash ({@link #tryLocalMirror}) — so a caller may name the file by it
     * (a CAS put) without hashing it again.
     */
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
     * Thrown when nothing answers at a repository's address — the connection refused, the host
     * unknown, the connect timed out — named so the resolve can stop on it rather than lock without
     * a configured repository, while the artifact leg falls through it and the lock's sha256 says
     * what is accepted.
     */
    public static final class RepositoryUnreachableException extends IOException {
        public RepositoryUnreachableException(String repository, URI url, String fault, @Nullable Throwable cause) {
            super(
                    "repository " + repository + " at " + SafeUri.forMessage(url) + " is unreachable (" + fault
                            + "); the resolve stops rather than lock without a configured repository — fix its url,"
                            + " start it, or remove it from [repositories]",
                    cause);
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
