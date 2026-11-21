// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.KeyedLocks;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.CentralMirror;
import cc.jumpkick.http.HostRateLimiter;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.transport.RepoTransports;
import cc.jumpkick.util.StoreWriteGate;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One Maven-style repository: fetch into where its store keeps artifacts (Maven layout + {@code .jk}
 * memo): {@code repos/<id>/}, or the Maven local repository for Central and the repositories
 * {@code settings.xml} declares when the machine shares its downloads with Maven ({@link
 * M2Backing}). For those repositories a file already in the local repository is used where it is, with no download, once its bytes verify as the lock's pin or,
 * unpinned, as the checksum the repository publishes; one the repository disowns is evicted
 * (removed only if jk put it there) and downloaded again. Offline means no network, not no
 * repositories: a repository whose requests open on this machine's disk ({@code file:}, itself or
 * through its mirror) is read as it is online; any other serves from the store ({@link
 * ArtifactNotFoundException} on miss).
 */
public final class MavenRepo {

    /**
     * Central failover + the standing download preference: {@link CentralMirror#standard()}, the
     * instance the transport holds, since the four-hour window is a fact about this machine's IP, not
     * about a repository. A field only so a test can hand in an isolated window.
     */
    private final CentralMirror centralMirror;

    /**
     * One network leg per file at a time, process-wide: two graphs of one lock, or two runs, that
     * want the same POM, sidecar or jar share its download. A leader's abort is its own, so a
     * waiter fetches for itself then.
     */
    private static final SingleFlight<Fetched> FLIGHTS =
            new SingleFlight<>(failure -> failure instanceof FetchAbortedException);

    private final String name;
    private final URI baseUrl;

    /**
     * The mirror this repository's requests go to — a {@code mirror-of} table of {@code
     * ~/.jk/config.toml} or a {@code settings.xml} {@code <mirror>} — or null when they go to
     * {@link #baseUrl}. A transport fact only: name, {@link #baseUrl} and the store's origin key are
     * the repository's own. A mirrored repository is never asked at {@link #baseUrl}.
     */
    private final @Nullable Mirror mirror;

    /** Where a request for a relative path is opened: the mirror's URL, else {@link #baseUrl}. */
    private final URI fetchBase;

    private final RepoTransport transport;
    private final Cas cas;
    private final RepoArtifactStore repoStore;
    private final LocalMirrorVerifier localMirror;

    /** Replaced at most once, by {@link #refreshedAfter}, when it came from a credential helper. */
    private volatile RepoCredential credential;

    /**
     * What re-runs the credential helper this repository's credential came from and resolves again;
     * null for every other credential. See {@link #refreshingCredentialWith}.
     */
    private volatile @Nullable Supplier<RepoCredential> credentialRefresh;

    /** Guards the one refresh; {@link #credentialRefreshed} says it has happened. */
    private final Object refreshLock = new Object();

    private boolean credentialRefreshed;
    /** When true, Central and the repositories {@code settings.xml} declares keep their artifacts in the Maven local repository. */
    private final boolean m2integration;

    /** TTL + conditional-GET cache for maven-metadata.xml; null for non-HTTP transports. */
    private final @Nullable MavenMetadataCache metadataCache;

    /**
     * The HTTP client behind the metadata cache, and the one a POM-declared repository is built
     * over. Null for non-HTTP transports.
     */
    private final @Nullable Http http;

    /** The client made once, when {@link #http} is null, for the repositories derived from this one. */
    private volatile @Nullable Http derivedHttp;

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

    /** Whether this repository is still asked in this job, and what a request throws once it is not. */
    private final RepoReachability reachability;

    /**
     * The sha256 of each store file verified in this job, by relative path, so a copy still holding
     * those bytes is not confirmed again (a caller can reach the store before the group's memo).
     */
    private final ConcurrentHashMap<String, String> verified = new ConcurrentHashMap<>();

    /** {@code optional = true} on the declaring table, or a repository a dependency's POM declares. */
    private final boolean optional;

    /**
     * A mirror standing in for a repository: the URL its requests open, the mirror's credential, its
     * label, and — when jk will not use it — why: such a mirror is asked nothing, and neither is
     * the repository at its own URL, so every request fails naming the mirror.
     */
    public record Mirror(
            String id,
            URI url,
            RepoCredential credential,
            String label,
            @Nullable String refusal) {

        /** A mirror jk uses. */
        public Mirror(String id, URI url, RepoCredential credential, String label) {
            this(id, url, credential, label, null);
        }
    }

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
                optional,
                centralMirror);
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
                optional,
                centralMirror);
    }

    /**
     * This repository with every request opened at {@code mirror}'s URL and authenticated with the
     * mirror's credential, sharing its store, policy and client. The transport is re-selected for
     * the mirror's scheme. Name, {@link #baseUrl()} and the store are unchanged: the lock still
     * records this repository, and a lock written through a mirror is byte-identical to one
     * written without.
     */
    public MavenRepo mirroredThrough(Mirror mirror) {
        Http client = clientForDerived();
        // A refused mirror is asked nothing, so its URL selects no transport (it may name none jk has).
        RepoTransport over = mirror.refusal() == null ? RepoTransports.forUrl(mirror.url(), client) : transport;
        return new MavenRepo(
                name,
                baseUrl,
                over,
                cas,
                mirror.credential(),
                client,
                m2integration,
                allowUnverified,
                allowInsecure,
                servesReleases,
                servesSnapshots,
                mirror,
                optional,
                centralMirror);
    }

    /** This repository over {@code centralMirror}'s window instead of the machine's; for tests. */
    MavenRepo withCentralMirror(CentralMirror centralMirror) {
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
                optional,
                centralMirror);
    }

    /**
     * This repository, re-resolving its credential through {@code refresh} the first time a request
     * is refused (401, 403): the request is then sent once more with the fresh credential, and a
     * second refusal is the answer. For a credential a {@code ${cmd:...}} helper printed, whose
     * token may simply have expired. Set before the repository is shared; a copy made by a {@code
     * with…} method or {@link #mirroredThrough} does not carry it, since a mirror has its own
     * credential.
     */
    /**
     * This repository, refusing every request with {@code why} instead of sending it: its
     * credential could not be resolved, and an anonymous request is never the fallback. What its
     * store already holds still answers, and a resolve that never asks it never fails on it. Set
     * before the repository is shared.
     */
    public MavenRepo refusingRequests(String why) {
        reachability.refuseRequests(why);
        return this;
    }

    public MavenRepo refreshingCredentialWith(Supplier<RepoCredential> refresh) {
        this.credentialRefresh = Objects.requireNonNull(refresh, "refresh");
        return this;
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
     * True when this repository's requests open on this machine's disk: a {@code file:} URL, its own
     * or its mirror's. Such a repository needs no network, so an offline session reads it as it
     * reads the store.
     */
    public boolean local() {
        return "file".equalsIgnoreCase(fetchBase.getScheme());
    }

    /** True when the session is offline and this repository needs the network to answer. */
    private boolean offlineRemote() {
        return SessionContext.current().config().offlineOr(false) && !local();
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
                false,
                CentralMirror.standard());
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
            boolean optional,
            CentralMirror centralMirror) {
        this.name = Objects.requireNonNull(name, "name");
        this.centralMirror = Objects.requireNonNull(centralMirror, "centralMirror");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.mirror = mirror;
        this.fetchBase = mirror == null ? this.baseUrl : normalize(mirror.url());
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        // Full store for every repo: Maven-layout artifact + {@code .jk} memo under the store
        // keyed by this repo's ORIGIN — the name is a label, and two projects calling different
        // origins by one name must never read each other's bytes.
        this.repoStore = RepoArtifactStore.forRepository(cas.root(), name, this.baseUrl, m2integration);
        this.localMirror = new LocalMirrorVerifier(repoStore, this.baseUrl, name);
        this.credential = Objects.requireNonNull(credential, "credential");
        this.m2integration = m2integration;
        this.allowUnverified = allowUnverified;
        this.allowInsecure = allowInsecure;
        this.servesReleases = servesReleases;
        this.servesSnapshots = servesSnapshots;
        this.optional = optional;
        this.http = httpOrNull;
        this.reachability = new RepoReachability(name, this.baseUrl, mirror, passable());
        RequestGate gate = reachability.gate();
        // The metadata cache speaks HTTP directly (conditional GET), so it only
        // applies to http(s) repos — a file:// (or other) baseUrl can be paired
        // with an Http client but must keep enumerating via the transport. A loopback port names
        // whatever process holds it now, so its list is never trusted for the day: every ask is a
        // conditional GET, answered 304 when the server still holds the body it stored.
        Duration ttl =
                RepositorySpec.loopback(this.fetchBase.getHost()) ? Duration.ZERO : MavenMetadataCache.DEFAULT_TTL;
        this.metadataCache = (httpOrNull != null && isHttp(this.fetchBase))
                ? new MavenMetadataCache(httpOrNull, metadataDir(), ttl, HostRateLimiter.shared(), gate)
                : null;
        this.download =
                new DownloadLeg(name, this.fetchBase, gate.over(transport), credential, storeDir(), allowUnverified);
    }

    /**
     * The client a repository derived from this one is built over: this one's, else one made on
     * first use and shared by every later derivation, since each client holds threads and a
     * connection pool of its own.
     */
    private Http clientForDerived() {
        if (http != null) return http;
        Http made = derivedHttp;
        if (made != null) return made;
        synchronized (this) {
            if (derivedHttp == null) derivedHttp = new Http();
            return Objects.requireNonNull(derivedHttp);
        }
    }

    /**
     * A repository a dependency's POM declares, over this repository's store and client: anonymous,
     * with neither {@code allow-unverified} nor {@code allow-insecure} — a POM has no table to opt in
     * with, so its repository is held to the rule a project-declared one meets by default — with
     * the {@code <releases>} / {@code <snapshots>} policy the POM wrote, and {@linkplain #passable
     * passable}, as Maven falls through a POM's repository that answers nothing. Every request and
     * redirect hop is asked of {@code guard} first; one it refuses puts the repository out of the
     * job, passed over with the guard's reason as its fault, since a name checked once may resolve
     * elsewhere later.
     */
    public MavenRepo declaredByPom(String name, URI url, boolean releases, boolean snapshots, Http.AddressGuard guard) {
        Http client = clientForDerived().withoutSilenceRetries().withAddressGuard(guard);
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
                true,
                centralMirror);
    }

    /**
     * True when the resolve passes this repository over once nothing answers at its address or it
     * refuses access (401, 403), as it does a blocked one, instead of stopping: one declared {@code
     * optional}, and any at a loopback address — a developer's local Nexus no other machine runs.
     * Never a mirrored one: the mirror is this machine's only route to it, so its failure is the
     * resolve's.
     */
    public boolean passable() {
        return mirror == null && passable(optional, baseUrl);
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
        return reachability.fault();
    }

    /**
     * True once this repository is out of the job and the resolve goes on without it, as it does
     * without a blocked one: a {@linkplain #passable passable} one, or any but a mirrored one that
     * got no answer within the timeout. Otherwise its fault stops the resolve.
     */
    public boolean passedOver() {
        return reachability.passedOver();
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
     * did not opt in is plaintext to nobody but this machine and is not reported. Behind a mirror
     * the question is the mirror's URL, which every request opens: a {@code config.toml} mirror
     * that said {@code allow-insecure} is reported for each repository it carries.
     */
    public boolean isPlaintext() {
        if (mirror != null) {
            return "http".equalsIgnoreCase(fetchBase.getScheme()) && !RepositorySpec.loopback(fetchBase.getHost());
        }
        return "http".equalsIgnoreCase(baseUrl.getScheme())
                && (allowInsecure || !RepositorySpec.loopback(baseUrl.getHost()));
    }

    public Fetched fetchPom(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.pomPath(coord), true, Leg.RESOLVE);
    }

    /**
     * {@code coord}'s Gradle module metadata ({@code .module}), read as a POM is: the resolver's
     * input, which no lock row pins, so a copy jk stored answers as stored, with no checksum read.
     */
    public Fetched fetchModuleMetadata(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.artifactPath(coord), true, Leg.RESOLVE);
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

    /**
     * The store's copy of {@code coord}'s POM. A copy jk wrote answers as stored; one in the Maven
     * local repository that jk never verified (a {@code mvn install} of a fork, a download from
     * another repository) answers only once this repository's published checksum agrees with it,
     * as a jar does.
     */
    public Optional<Fetched> tryLocalPom(Coordinate coord) throws IOException, InterruptedException {
        boolean force = SessionContext.current().config().forceOr(false);
        if (force) return Optional.empty();
        return mirrored(coord, MavenLayout.pomPath(coord), Leg.RESOLVE, null, NO_ABORT);
    }

    /** Whether the store holds a copy of {@code coord}'s POM, verified or not; no read, no network. */
    public boolean storeHoldsPom(Coordinate coord) {
        return repoStore.locate(MavenLayout.pomPath(coord)).isPresent();
    }

    public Fetched fetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        // maven-metadata.xml has no version key and is stale offline, so it's
        // never mirrored; offline version enumeration uses availableVersions.
        return fetch(coord, MavenLayout.metadataPath(coord), false, Leg.RESOLVE);
    }

    /**
     * Versions of this coordinate's {@code group:artifact} available here: online from {@code
     * maven-metadata.xml} through the {@link MavenMetadataCache} for HTTP repos, offline from what
     * the store holds. A {@linkplain #local local} repository is read offline too, and the versions
     * the store already holds from it stay candidates. A missing artifact is an empty list, so
     * {@link RepoGroup} can union across repos.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        if (!SessionContext.current().config().offlineOr(false)) return listedVersions(coord);
        List<String> stored = repoStore.versions(coord.group(), coord.artifact());
        if (!local()) return stored;
        LinkedHashSet<String> union = new LinkedHashSet<>(listedVersions(coord));
        union.addAll(stored);
        return List.copyOf(union);
    }

    /**
     * The versions this repository itself lists for {@code coord}'s {@code group:artifact}: its
     * {@code maven-metadata.xml}, or — for a {@linkplain #local local} repository that has none, such
     * as a Maven local repository or a jk store tree — its version directories ({@link
     * VersionDirectories}).
     */
    private List<String> listedVersions(Coordinate coord) throws IOException, InterruptedException {
        reachability.refuseIfUnreachable();
        URI uri = fetchBase.resolve(MavenLayout.metadataPath(coord));
        byte[] xml;
        try {
            xml = metadataCache != null
                    ? cachedMetadata(uri)
                    : Files.readAllBytes(fetchMetadata(coord).cachePath());
        } catch (ArtifactNotFoundException notFound) {
            return local() ? VersionDirectories.of(fetchBase, coord) : List.of();
        } catch (IOException transport) {
            throw reachability.unreachableOr(transport);
        }
        return CatalogVersions.of(xml, coord, name, uri, metadataCache);
    }

    /**
     * {@code uri} through the metadata cache, sent once more with a fresh credential after a refusal.
     * While a fresh credential can still be had, a refusal is not answered from a stale copy.
     */
    private byte[] cachedMetadata(URI uri) throws IOException, InterruptedException {
        MavenMetadataCache cache = Objects.requireNonNull(metadataCache, "metadataCache");
        RepoCredential used = credential;
        boolean refreshable;
        synchronized (refreshLock) {
            refreshable = credentialRefresh != null && !credentialRefreshed;
        }
        try {
            return cache.fetch(uri, used, !refreshable);
        } catch (ArtifactNotFoundException | FetchAbortedException e) {
            throw e;
        } catch (IOException e) {
            if (!refreshedAfter(e, used)) throw e;
            try {
                return cache.fetch(uri, credential);
            } catch (IOException again) {
                throw refusedAgain(again);
            }
        }
    }

    /**
     * Which leg of the work a fetch belongs to: {@link #RESOLVE} — metadata and POMs, which decide the
     * graph — or {@link #ARTIFACT}, the bytes a lock pins. Where a file is fetched does not follow the
     * leg: a release POM or artifact is immutable, so it is downloaded from Central's mirror first
     * and verified against Central's checksum ({@link #vouchFor}), while {@code maven-metadata.xml}
     * changes and stays on Central until Central refuses (see {@link CentralMirror}). The leg decides
     * what a failure means and what is counted.
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
        // A non-unique snapshot's stored file is looked at and replaced under one lock (MovingSnapshots).
        try (KeyedLocks.Held held =
                mirror ? MovingSnapshots.hold(coord, repoStore.root(), relativePath) : MovingSnapshots.NONE) {
            if (offlineRemote()) {
                return localMirror.offline(coord, relativePath, expectedSha256);
            }
            // warm re-lock — serve immutable GAV paths from repos/<name>/ without re-HTTP.
            // --force always revalidates from the network (checksums re-checked).
            boolean force = SessionContext.current().config().forceOr(false);
            // A copy this look finds and declines (unconfirmed, disowned, evicted) goes to the network;
            // only a miss lets the flight take a copy that appears meanwhile.
            boolean missed = false;
            if (mirror && !force) {
                missed = repoStore.locate(relativePath).isEmpty();
                if (!missed) {
                    Optional<Fetched> local = mirrored(coord, relativePath, leg, expectedSha256, abort);
                    if (local.isPresent()) return local.get();
                }
            }
            reachability.refuseIfUnreachable();
            URI uri = fetchBase.resolve(sourcePath(relativePath));
            storeMissed.run();
            // The same file into the same store under the same pin and credential: one download,
            // whoever asks. Two repositories at one URL with different credentials never share one.
            List<Object> flight = Arrays.asList(repoStore.root(), uri, mirror, leg, expectedSha256, force, credential);
            boolean lookAgain = missed;
            return FLIGHTS.run(flight, () -> {
                if (lookAgain) {
                    Optional<Fetched> placed = localMirror.placedSinceMiss(relativePath, expectedSha256);
                    if (placed.isPresent()) return placed.get();
                }
                return fetchRemote(coord, relativePath, uri, mirror, leg, abort, expectedSha256, force);
            });
        }
    }

    /** Where a {@code file:} repository keeps {@code relativePath} ({@link SnapshotFiles#source}); a remote, as asked. */
    private String sourcePath(String relativePath) throws InterruptedException {
        return local()
                ? SnapshotFiles.source(relativePath, p -> transport.fetch(fetchBase.resolve(p), credential))
                : relativePath;
    }

    /**
     * Runs between a fetch's look in the store and its flight: the window in which another
     * caller's flight can store the file and end. Package-visible so a test can hold a caller there.
     */
    static volatile Runnable storeMissed = () -> {};

    /** {@link #fetch}'s network leg, once the store has missed. */
    private Fetched fetchRemote(
            Coordinate coord,
            String relativePath,
            URI uri,
            boolean mirror,
            Leg leg,
            BooleanSupplier abort,
            @Nullable String expectedSha256,
            boolean force)
            throws IOException, InterruptedException {
        // A miss this repository already answered is answered again without a request; a catalog's
        // for an hour, a POM's or artifact's for the metadata cache's day.
        RepoMisses misses = relativePath.endsWith("maven-metadata.xml") ? RepoMisses.CATALOGS : RepoMisses.FETCHES;
        if (!force && misses.known(uri)) {
            throw new ArtifactNotFoundException("not found in " + name + ": " + uri, coord);
        }
        // Immutable release files prefer the mirror; metadata stays on Central (see CentralMirror).
        URI primary = mirror ? centralMirror.routeForDownload(uri) : uri;
        // The sidecars are read where the repository itself publishes them, so Central, not the
        // mirror, vouches for the bytes the mirror carries. A pinned file reads none: the pin is
        // the authority. Metadata publishes none worth reading.
        DownloadLeg.Vouch vouch = mirror && expectedSha256 == null ? vouchFor(uri) : null;
        // The network leg runs under the host's permit (DownloadLeg). Warm mirror hits short-circuit
        // above, so re-locks stay uncapped, but a cold lock's fan-out is bounded to what the host
        // tolerates.
        // Download + placement write .put- temps into repos/<name>/ and promote them in place — a
        // store wipe must not overlap either leg, or the wiped directory holds an open temp
        // (undeletable on Windows) and the promote writes the store right back (StoreWriteGate).
        try (var held = StoreWriteGate.write()) {
            DownloadLeg.Downloaded stored;
            RepoCredential used = credential;
            try {
                stored = download(coord, primary, uri, relativePath, vouch, leg, expectedSha256, abort);
            } catch (ArtifactNotFoundException | FetchAbortedException e) {
                throw e;
            } catch (IOException e) {
                if (!refreshedAfter(e, used)) throw e;
                try {
                    stored = download(coord, primary, uri, relativePath, vouch, leg, expectedSha256, abort);
                } catch (IOException again) {
                    throw refusedAgain(again);
                }
            }
            SessionContext.current().io().remoteDown(stored.size());
            misses.forget(uri);
            Path placed = stored.path();
            if (mirror) {
                // The download temp goes whether the placement copies it or refuses it (a slot in
                // the local repository holding bytes JumpKick did not write).
                try {
                    placed = placeArtifact(coord, relativePath, stored.path(), stored.sha256());
                } catch (IOException | RuntimeException refused) {
                    deleteTemp(stored.path());
                    throw refused;
                }
                if (!placed.equals(stored.path())) deleteTemp(stored.path());
                verified.put(relativePath, stored.sha256());
            }
            return new Fetched(uri, placed, stored.sha256(), stored.size());
        } catch (ArtifactNotFoundException missing) {
            // A non-unique snapshot missing now is there after the next install or deploy.
            if (misses == RepoMisses.CATALOGS || !MovingSnapshots.moving(coord)) misses.record(uri);
            throw missing;
        } catch (FetchAbortedException aborted) {
            throw aborted;
        } catch (IOException transport) {
            throw reachability.unreachableOr(transport);
        }
    }

    private static void deleteTemp(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // a temp the store sweep takes later
        }
    }

    /** One download of {@code uri}, from {@code primary} first when that is Central's mirror. */
    private DownloadLeg.Downloaded download(
            Coordinate coord,
            URI primary,
            URI uri,
            String relativePath,
            DownloadLeg.@Nullable Vouch vouch,
            Leg leg,
            @Nullable String expectedSha256,
            BooleanSupplier abort)
            throws IOException, InterruptedException {
        try {
            return download.run(coord, primary, relativePath, vouch, leg, expectedSha256, abort);
        } catch (FetchAbortedException e) {
            throw e;
        } catch (IOException e) {
            // The mirror can lag or simply not carry something Central has. Falling back keeps a
            // preference from becoming a dependency.
            if (primary.equals(uri)) throw e;
            if (e instanceof ChecksumMismatchException) {
                // Central's releases do not change, so bytes that fail the check are the
                // mirror's fault; Central's copy is what the check is for.
                download.note(coord + " from " + name + ": the mirror's " + relativePath
                        + " failed verification; downloaded from Maven Central instead");
            }
            return download.run(coord, uri, relativePath, vouch, leg, expectedSha256, abort);
        }
    }

    /**
     * Whether a request refused with {@code used} may be sent once more: true when the credential
     * is now a fresh one — this call re-ran its helper, or a concurrent request already did — and
     * false when there is nothing to refresh or the failure is not a refusal. Runs before {@link
     * RepoReachability#unreachableOr}, so a refused credential is refreshed before the refusal can put a passable
     * repository out of the job, and {@link RepoMisses} records only absences, never refusals.
     *
     * @throws CredentialNotRefreshedException when the helper could not supply another credential,
     *     or the one its re-run printed is the one refused, so the failure says so
     */
    private boolean refreshedAfter(IOException failure, RepoCredential used) throws IOException {
        Supplier<RepoCredential> refresh = credentialRefresh;
        if (refresh == null) return false;
        String refused = RepoReachability.refusal(failure);
        if (refused == null) return false;
        synchronized (refreshLock) {
            if (credential != used) return true;
            // The refused credential is the one the helper's re-run printed.
            if (credentialRefreshed) throw refusedAgain(failure);
            credentialRefreshed = true;
            RepoCredential fresh;
            try {
                fresh = refresh.get();
            } catch (RuntimeException e) {
                throw new CredentialNotRefreshedException(
                        name, refused, "its credential helper failed: " + e.getMessage(), failure);
            }
            credential = fresh;
            download.credential(fresh);
            return true;
        }
    }

    /**
     * {@code failure} of a request sent with the credential a helper's re-run printed: when it is a
     * refusal, the failure says the fresh value was refused too, since the helper ran again for it.
     */
    private IOException refusedAgain(IOException failure) {
        String refused = RepoReachability.refusal(failure);
        if (refused == null || failure instanceof CredentialNotRefreshedException) return failure;
        return new CredentialNotRefreshedException(
                name, refused, "the credential helper's new value was refused too", failure);
    }

    /**
     * Where the checksum for the file at {@code uri} — this repository's own URL for it — is read.
     * For Central, Central's {@code .sha1} alone: one request, and Central publishes one for every
     * file. It is addressed to Central even while Central is refusing this host, so the transport
     * routes it as it routes the file: to the mirror, and back to Central for a checksum the
     * mirror lacks when {@link CentralMirror} allows. Each checksum the mirror answers with is the
     * mirror vouching for its own bytes, counted for the run's note. Any other repository is read
     * beside the file, strongest first.
     */
    private DownloadLeg.Vouch vouchFor(URI uri) {
        if (!centralMirror.matches(uri)) return DownloadLeg.Vouch.beside(uri);
        return new DownloadLeg.Vouch(uri, true, centralMirror);
    }

    /**
     * Put verified bytes on disk where this repository keeps its downloads — its own store tree, or
     * under {@code [m2] integration = "share"} the Maven local repository for Central and the
     * repositories {@code settings.xml} declares, with Maven's sidecars beside them — and write the
     * memo that says jk downloaded them. A slot in the local repository holding other
     * bytes that jk did not put there is left alone, and the placement fails rather than replace it.
     */
    private Path placeArtifact(Coordinate coord, String relativePath, Path source, String sha256) throws IOException {
        repoStore.materialize(relativePath, source, sha256, true);
        // Fail loudly rather than returning the transient download temp as the "stored" path: a
        // swallowed store-write error (disk full, permissions) otherwise makes sync report success
        // and the later requirePresent gate throws a misleading "run jk sync -F" loop.
        Path placed = repoStore
                .locate(relativePath)
                .orElseThrow(() -> new IOException("failed to store " + coord.group() + ":" + coord.artifact() + ":"
                        + coord.version() + " at " + relativePath + "; check disk space and permissions"));
        if (repoStore.verify(relativePath, sha256) != RepoArtifactStore.IndexState.VERIFIED) {
            throw new IOException(placed + " does not match the locked sha256 of " + coord.group() + ":"
                    + coord.artifact() + ":" + coord.version() + " and JumpKick did not write it; remove the file, or"
                    + " point JK_M2_LOCAL elsewhere");
        }
        if (repoStore.inM2(relativePath) && repoStore.fetchedByJk(relativePath)) writeMavenSidecars(placed);
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
     * LocalMirrorVerifier#stored}); an unpinned artifact is then confirmed by the checksum the repository
     * publishes ({@link #confirmed}), and so is a POM in the Maven local repository that jk never
     * verified. A POM or metadata file jk wrote is served as stored.
     *
     * <p>A non-unique snapshot's unpinned copy, POM or artifact, answers only while its source still
     * holds those bytes: a file repository's file is compared with it ({@link #sourceHolds}), a
     * remote's published checksum must agree with it, and a remote that publishes none is asked
     * for the file again. Its first copy is never the answer for good, since a {@code mvn install}
     * or the repository's next deploy replaces it in place. Under a pin the pin decides, as for
     * any file: a lock is law until the next lock.
     */
    private Optional<Fetched> mirrored(
            Coordinate coord, String relativePath, Leg leg, @Nullable String expectedSha256, BooleanSupplier abort)
            throws IOException, InterruptedException {
        try (KeyedLocks.Held held = MovingSnapshots.hold(coord, repoStore.root(), relativePath)) {
            boolean unvouched = localMirror.unvouched(relativePath, expectedSha256);
            // An unvouched copy gets no memo until it is confirmed: a memo is what makes it vouched.
            Optional<Fetched> local =
                    unvouched ? localMirror.candidate(relativePath) : localMirror.stored(relativePath, expectedSha256);
            boolean moving = expectedSha256 == null && MovingSnapshots.moving(coord);
            if (local.isEmpty() || expectedSha256 != null || (leg != Leg.ARTIFACT && !unvouched && !moving))
                return local;
            if (local.get().sha256().equalsIgnoreCase(verified.get(relativePath))) return local;
            if (moving && local()) return sourceHolds(relativePath, local.get());
            Optional<Fetched> answer =
                    confirmed(coord, relativePath, local.get(), leg, abort, unvouched || moving, unvouched);
            if (unvouched && answer.isPresent() && answer.get().sha256().equalsIgnoreCase(verified.get(relativePath))) {
                repoStore.verify(relativePath, answer.get().sha256());
            }
            return answer;
        }
    }

    /**
     * {@code stored} while the file this repository's directory holds at {@code relativePath} has
     * its bytes; else the copy is evicted and empty, so the fetch reads the file again. The file's
     * digest is remembered under its stat identity, so an unchanged file costs one stat.
     */
    private Optional<Fetched> sourceHolds(String relativePath, Fetched stored) throws InterruptedException {
        if (MovingSnapshots.sourceDigest(fetchBase.resolve(sourcePath(relativePath)))
                .filter(stored.sha256()::equalsIgnoreCase)
                .isPresent()) {
            verified.put(relativePath, stored.sha256());
            return Optional.of(stored);
        }
        repoStore.evict(relativePath);
        return Optional.empty();
    }

    /**
     * {@code local} once the checksum this repository publishes agrees with it, the same sidecar
     * reads a download gets, so a store that holds the wrong bytes for a coordinate — a killed
     * write, a jar copied in by hand, an artifact upstream republished — is not what a fresh lock
     * pins. A disowned copy is evicted and the fetch goes on to the network with a note; a copy the
     * repository publishes no checksum for follows the download's {@code allow-unverified} rule, so
     * a refused one falls through to the download that refuses it by name.
     *
     * <p>When the checksum cannot be read (offline for a repository that is not {@linkplain #local
     * local}, unreachable, a transport error) or the lock has already failed ({@code abort}), a copy
     * a memo vouches for answers as it stands, and an {@code unvouched} one is not used.
     */
    private Optional<Fetched> confirmed(
            Coordinate coord,
            String relativePath,
            Fetched local,
            Leg leg,
            BooleanSupplier abort,
            boolean requirePublished,
            boolean unvouched)
            throws IOException, InterruptedException {
        if (offlineRemote()) {
            if (!unvouched) return Optional.of(local);
            LocalMirrorVerifier.noteUnverifiedOffline(coord);
            return Optional.empty();
        }
        if (abort.getAsBoolean() || reachability.out()) {
            return unvouched ? Optional.empty() : Optional.of(local);
        }
        RepoCredential used = credential;
        try {
            try {
                return confirmedOnce(coord, relativePath, local, leg, requirePublished);
            } catch (MissingChecksumException refused) {
                throw refused;
            } catch (IOException e) {
                // A refused sidecar is asked once more with a fresh credential before the refusal
                // can put the repository out of the job.
                if (!refreshedAfter(e, used)) throw e;
                try {
                    return confirmedOnce(coord, relativePath, local, leg, requirePublished);
                } catch (MissingChecksumException refused) {
                    throw refused;
                } catch (IOException again) {
                    throw refusedAgain(again);
                }
            }
        } catch (MissingChecksumException refused) {
            return Optional.empty();
        } catch (IOException transport) {
            // Nothing answered for the sidecar: a vouched copy answers, as it does offline. A dead
            // address is remembered so the rest of the job stops dialing it.
            Log.debug("confirmed: " + coord + " from " + name + " unconfirmed", transport);
            reachability.unreachableOr(transport);
            return unvouched ? Optional.empty() : Optional.of(local);
        }
    }

    /** One check of {@code local} against the checksum this repository publishes; empty when it differs. */
    private Optional<Fetched> confirmedOnce(
            Coordinate coord, String relativePath, Fetched local, Leg leg, boolean requirePublished)
            throws IOException, InterruptedException {
        if (download.confirmStored(
                coord,
                vouchFor(fetchBase.resolve(relativePath)),
                relativePath,
                local.cachePath(),
                local.sha256(),
                leg,
                requirePublished)) {
            verified.put(relativePath, local.sha256());
            return Optional.of(local);
        }
        repoStore.evict(relativePath);
        return Optional.empty();
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
     * file by its memo or a re-hash ({@link LocalMirrorVerifier#stored}) — so a caller may name the file by it
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
            this(
                    "repository " + repository + " at " + SafeUri.forMessage(url) + " is unreachable (" + fault
                            + "); fix its url, start it, or remove it from [repositories]",
                    cause);
        }

        private RepositoryUnreachableException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }

        /**
         * The failure of a repository a mirror stands in for: the mirror is named, and so is the
         * repository's own URL, which is not asked in its place.
         */
        static RepositoryUnreachableException throughMirror(
                String repository, URI url, Mirror mirror, String fault, @Nullable Throwable cause) {
            return new RepositoryUnreachableException(
                    "repository " + repository + ": its " + mirror.label() + " at " + SafeUri.forMessage(mirror.url())
                            + " is unreachable (" + fault + "); fix or start the mirror, or remove it",
                    cause);
        }
    }

    /**
     * Thrown when a repository refused its credential and the credential helper behind it could
     * not supply another: the helper failed, or the value it printed on its one re-run was refused
     * too. {@link #why} is the helper's side of it, which a failure naming only the refusal leaves
     * out.
     */
    public static final class CredentialNotRefreshedException extends IOException {
        private final String why;

        CredentialNotRefreshedException(String repository, String refused, String why, Throwable cause) {
            super(repository + " refused its credential (" + refused + "), and " + why, cause);
            this.why = why;
        }

        /** Why no other credential was sent, as a clause: what the helper did or said. */
        public String why() {
            return why;
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
