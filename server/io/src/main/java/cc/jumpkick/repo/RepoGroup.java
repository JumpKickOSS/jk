// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.ConnectFaults;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Ordered {@link MavenRepo}s with try-each / first-hit-wins semantics, plus two kinds of group
 * binding. An <em>exclusive</em> binding is a dependency-confusion defense: a claimed group is
 * discovered and fetched from the claiming repos alone, and their miss is the answer. A
 * <em>routed</em> binding is a precedence rule between public repositories that share a
 * namespace: the claiming repos are asked first and alone when they answer, and every other
 * repository is asked when they all miss ({@code com.google.firebase} holds the Firebase Android
 * SDK on Google's Maven and {@code firebase-admin} on Central).
 */
public final class RepoGroup {

    /**
     * Process-wide local POM hits. Keyed by the repositories asked <em>and</em> GAV — exclusive
     * bindings and repo order are part of the question (a Central hit must not answer for a
     * Google-only exclusive group). Misses are not cached (may appear mid-session via fetch).
     * Entries have no TTL: published release GAVs are immutable; force / {@link #clearProcessFetchCache}
     * drop the memo when the caller does not trust the view.
     */
    private static final ConcurrentHashMap<String, RepoFetched> POM_HIT_CACHE = new ConcurrentHashMap<>();

    /** Same for non-POM artifacts (jars, classified artifacts). Keyed by repositories + GAVC+type. */
    private static final ConcurrentHashMap<String, RepoFetched> ARTIFACT_HIT_CACHE = new ConcurrentHashMap<>();

    /**
     * Process-wide {@link #availableVersions} memo, keyed by the repositories asked <em>and</em> the
     * {@code group:artifact}. Two groups pointing at different repositories see different version
     * lists, so the repository set is part of the identity — leaving it out let one group answer
     * for another.
     *
     * <p>Entries expire. {@link MavenMetadataCache} is the layer that decides when a version list is
     * stale, with a TTL and a conditional GET; this memo only skips re-parsing what that layer
     * already handed over. Living for the life of the process would put it above that decision, and
     * in the resident engine "the life of the process" is days — a version published after the
     * first resolve would stay invisible. A group that asks a loopback repository is not memoized
     * at all: the port names whatever process holds it now ({@link RepoMisses#memoizes}).
     */
    private static final ConcurrentHashMap<String, VersionsEntry> VERSIONS_CACHE = new ConcurrentHashMap<>();

    /** Long enough to cover one build's resolves, short enough that a daemon re-checks. */
    private static final long VERSIONS_TTL_NANOS = Duration.ofSeconds(60).toNanos();

    private record VersionsEntry(List<String> versions, long expiresAtNanos) {
        boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }

    /** Entries a hit memo holds before it starts over, so a long session cannot keep every fetch. */
    private static final int HIT_CACHE_MAX = 16_384;

    private static final int VERSIONS_CACHE_MAX = 8_192;

    /** Drop the POM and artifact hit memos and return how many entries went; for the idle engine. */
    public static int dropHitMemos() {
        int dropped = POM_HIT_CACHE.size() + ARTIFACT_HIT_CACHE.size();
        POM_HIT_CACHE.clear();
        ARTIFACT_HIT_CACHE.clear();
        return dropped;
    }

    /** Drop the version-list memo and return how many lists went; for the idle engine. */
    public static int dropVersionsMemo() {
        int dropped = VERSIONS_CACHE.size();
        VERSIONS_CACHE.clear();
        return dropped;
    }

    /** {@code cache.put}-ready: a memo at its cap starts over rather than refusing the entry. */
    private static void admit(ConcurrentHashMap<String, ?> cache, int max) {
        if (cache.size() >= max) cache.clear();
    }

    /** Drop the process fetch memos, the hits and the misses alike (force / tests). */
    public static void clearProcessFetchCache() {
        POM_HIT_CACHE.clear();
        ARTIFACT_HIT_CACHE.clear();
        RepoMisses.clear();
        ConnectFaults.forget();
    }

    /** Drop process-wide version lists (force / tests). */
    public static void clearProcessVersionsCache() {
        VERSIONS_CACHE.clear();
    }

    private final List<MavenRepo> repos;
    /** The repositories this group asks, as a stable string — part of every process memo's key. */
    private final String repoIdentity;
    /** Parallel to {@link #repos}: exclusive group patterns per repo (empty = no exclusive claim). */
    private final List<List<String>> exclusiveGroups;
    /** Parallel to {@link #repos}: routed group patterns per repo (empty = no routing claim). */
    private final List<List<String>> routedGroups;
    /**
     * The first {@code priorityCount} repos are workspace-local materializations (path/git):
     * always eligible and always consulted first, even for exclusively-claimed groups — a
     * locally-built artifact outranks any remote binding.
     */
    private final int priorityCount;

    /**
     * The repositories the manifest declares {@code blocked = true}: known, never asked, named in
     * the failure of a package nothing here serves. Not part of {@link #repoIdentity}, since they
     * change no answer a memo holds.
     */
    private final List<RepositorySpec> blocked;

    public RepoGroup(List<MavenRepo> repos) {
        this(repos, null);
    }

    /**
     * @param exclusiveGroups parallel list of exclusive group patterns per repo; {@code null} or
     * shorter lists are treated as no bindings for those entries
     */
    public RepoGroup(List<MavenRepo> repos, @Nullable List<List<String>> exclusiveGroups) {
        this(repos, exclusiveGroups, null, 0);
    }

    /**
     * @param exclusiveGroups parallel list of exclusive group patterns per repo
     * @param routedGroups parallel list of routed group patterns per repo; a repo may carry both
     * kinds, and an exclusive claim on a group outranks any routing claim on it
     */
    public RepoGroup(
            List<MavenRepo> repos,
            @Nullable List<List<String>> exclusiveGroups,
            @Nullable List<List<String>> routedGroups) {
        this(repos, exclusiveGroups, routedGroups, 0);
    }

    private RepoGroup(
            List<MavenRepo> repos,
            @Nullable List<List<String>> exclusiveGroups,
            @Nullable List<List<String>> routedGroups,
            int priorityCount) {
        this(repos, exclusiveGroups, routedGroups, priorityCount, List.of());
    }

    private RepoGroup(
            List<MavenRepo> repos,
            @Nullable List<List<String>> exclusiveGroups,
            @Nullable List<List<String>> routedGroups,
            int priorityCount,
            List<RepositorySpec> blocked) {
        Objects.requireNonNull(repos, "repos");
        if (repos.isEmpty()) {
            throw new IllegalArgumentException("RepoGroup must contain at least one repo");
        }
        this.repos = List.copyOf(repos);
        this.exclusiveGroups = normalizePatterns(this.repos.size(), exclusiveGroups);
        this.routedGroups = normalizePatterns(this.repos.size(), routedGroups);
        this.priorityCount = priorityCount;
        this.blocked = List.copyOf(blocked);
        // Bindings and the priority prefix change which repos are eligible for a coordinate, so
        // they are part of the question every memo answers — two groups with the same URLs but
        // different bindings must never share memo entries.
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < this.repos.size(); i++) {
            if (i > 0) id.append(',');
            id.append(this.repos.get(i).baseUrl());
            List<String> excl = this.exclusiveGroups.get(i);
            if (!excl.isEmpty()) id.append('!').append(String.join(";", excl));
            List<String> routed = this.routedGroups.get(i);
            if (!routed.isEmpty()) id.append('>').append(String.join(";", routed));
        }
        this.repoIdentity = id.append("|p").append(priorityCount).toString();
    }

    public static RepoGroup of(MavenRepo single) {
        return new RepoGroup(List.of(single));
    }

    /** This group with {@code blocked} as the repositories it knows of and never asks. */
    public RepoGroup withBlocked(List<RepositorySpec> blocked) {
        if (blocked.isEmpty()) return this;
        return new RepoGroup(repos, exclusiveGroups, routedGroups, priorityCount, blocked);
    }

    /** The repositories declared {@code blocked = true}: never asked, named when nothing else serves a package. */
    public List<RepositorySpec> blocked() {
        return blocked;
    }

    /**
     * Prepend {@code leading} repos ahead of this group, keeping this group's bindings aligned
     * with the trailing repos. Used for path/git materialize repos: they answer before remotes —
     * including for exclusively-claimed groups — without stripping JumpKick's exclusive groups
     * (which would make every Central GAV HTTP-404 on jumpkick first).
     */
    public RepoGroup withReposPrepended(List<MavenRepo> leading) {
        if (leading == null || leading.isEmpty()) return this;
        List<MavenRepo> merged = new ArrayList<>(leading.size() + repos.size());
        merged.addAll(leading);
        merged.addAll(repos);
        return new RepoGroup(
                merged,
                padded(leading.size(), exclusiveGroups, 0),
                padded(leading.size(), routedGroups, 0),
                leading.size() + priorityCount,
                blocked);
    }

    /**
     * This group followed by {@code trailing}, which answer only when every repository here has
     * missed: the repositories a dependency's POM declares for its own subtree. They carry no
     * binding, and the priority prefix is unchanged.
     */
    public RepoGroup withReposAppended(List<MavenRepo> trailing) {
        if (trailing == null || trailing.isEmpty()) return this;
        List<MavenRepo> merged = new ArrayList<>(repos.size() + trailing.size());
        merged.addAll(repos);
        merged.addAll(trailing);
        return new RepoGroup(
                merged,
                padded(0, exclusiveGroups, trailing.size()),
                padded(0, routedGroups, trailing.size()),
                priorityCount,
                blocked);
    }

    /** {@code patterns} with {@code before} empty entries ahead of it and {@code after} behind it. */
    private static List<List<String>> padded(int before, List<List<String>> patterns, int after) {
        List<List<String>> out = new ArrayList<>(before + patterns.size() + after);
        for (int i = 0; i < before; i++) out.add(List.of());
        out.addAll(patterns);
        for (int i = 0; i < after; i++) out.add(List.of());
        return out;
    }

    public List<MavenRepo> repos() {
        return repos;
    }

    /**
     * What this run's downloads were checked against, summed over the repositories asked: the
     * artifacts whose bytes matched a published checksum, the ones pinned without one under
     * {@code allow-unverified}, and the names of the plaintext {@code http://} repositories. A warm
     * re-lock downloads nothing, so both counts are then zero.
     */
    public TrustSummary trust() {
        int verified = 0;
        int unverified = 0;
        List<String> insecure = new ArrayList<>();
        for (MavenRepo repo : repos) {
            verified += repo.verifiedUpstream();
            unverified += repo.unverifiedAllowed();
            if (repo.isPlaintext()) insecure.add(repo.name());
        }
        return new TrustSummary(verified, unverified, List.copyOf(insecure));
    }

    /**
     * One sentence per repository a settings.xml mirror answers for: which mirror, at which URL,
     * and that the lock keeps recording the repository itself.
     */
    public List<String> mirrorNotes() {
        List<String> out = new ArrayList<>();
        for (MavenRepo repo : repos) {
            repo.mirror()
                    .ifPresent(m -> out.add("repository `" + repo.name() + "` is reached through " + m.label() + " at "
                            + SafeUri.forMessage(m.url()) + "; the lock records `" + repo.name() + "` at "
                            + SafeUri.forMessage(repo.baseUrl())));
        }
        return List.copyOf(out);
    }

    /**
     * One sentence per artifact this run verified against an {@code .md5} sidecar alone, over the
     * repositories asked; see {@link MavenRepo#weakChecksumNotes()}.
     */
    public List<String> weakChecksumNotes() {
        List<String> out = new ArrayList<>();
        for (MavenRepo repo : repos) out.addAll(repo.weakChecksumNotes());
        out.sort(null);
        return List.copyOf(out);
    }

    /** See {@link #trust()}. {@link #NONE} is the summary of a lock that downloaded nothing. */
    public record TrustSummary(int verified, int unverifiedAllowed, List<String> insecureRepos) {
        public static final TrustSummary NONE = new TrustSummary(0, 0, List.of());

        public TrustSummary {
            insecureRepos = List.copyOf(insecureRepos);
        }
    }

    /**
     * Stable identity of the repositories this group asks. Part of every process-wide fetch /
     * versions / effective-POM memo key so one group's answer cannot stand in for another's.
     */
    public String processIdentity() {
        return repoIdentity;
    }

    /** Exclusive group patterns aligned with {@link #repos()}. */
    public List<List<String>> exclusiveGroups() {
        return exclusiveGroups;
    }

    /** Routed group patterns aligned with {@link #repos()}. */
    public List<List<String>> routedGroups() {
        return routedGroups;
    }

    /** {@code true} when any repository carries a binding of either kind. */
    public boolean hasExclusiveBindings() {
        return ExclusiveGroups.anyBinding(exclusiveGroups) || ExclusiveGroups.anyBinding(routedGroups);
    }

    public Optional<RepoFetched> tryFetchPom(Coordinate coord) throws IOException, InterruptedException {
        String key = repoIdentity + "|" + coord.toGav();
        RepoFetched hit = liveHit(POM_HIT_CACHE, key);
        if (hit != null) return Optional.of(hit);
        Optional<RepoFetched> found = tryFetch(coord, MavenRepo::tryLocalPom, MavenRepo::fetchPom);
        if (found.isPresent()) {
            admit(POM_HIT_CACHE, HIT_CACHE_MAX);
            POM_HIT_CACHE.putIfAbsent(key, found.get());
        }
        return found;
    }

    public Optional<RepoFetched> tryFetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return tryFetchArtifact(coord, NO_ABORT);
    }

    /**
     * The repository that served {@code coord}'s POM through this group — the process memo, a
     * local copy or a fetch — or empty when none did. What a row with no file of its own records as
     * its source: the repository that answered for the coordinate, never one that served nothing.
     */
    public Optional<MavenRepo> pomRepository(Coordinate coord) throws InterruptedException {
        try {
            return tryFetchPom(coord).map(RepoFetched::repo);
        } catch (IOException unreachable) {
            return Optional.empty();
        }
    }

    /**
     * As {@link #tryFetchArtifact(Coordinate)} with a cooperative abort signal: once
     * {@code abort} turns true the fetch stops at the next leg boundary — after a local probe,
     * before the network leg, before/after host-permit acquisition — with a
     * {@link MavenRepo.FetchAbortedException}. Legs in progress always complete cleanly.
     */
    public Optional<RepoFetched> tryFetchArtifact(Coordinate coord, BooleanSupplier abort)
            throws IOException, InterruptedException {
        String key = repoIdentity
                + "|"
                + coord.toGav()
                + "\0"
                + (coord.type() == null ? "" : coord.type())
                + "\0"
                + (coord.classifier() == null ? "" : coord.classifier());
        RepoFetched hit = liveHit(ARTIFACT_HIT_CACHE, key);
        if (hit != null) return Optional.of(hit);
        Optional<RepoFetched> found = tryFetch(
                coord,
                MavenRepo::tryLocalArtifact,
                (repo, c) -> repo.fetchArtifact(c, abort),
                abort,
                pomHolder(coord),
                false);
        if (found.isPresent()) {
            admit(ARTIFACT_HIT_CACHE, HIT_CACHE_MAX);
            ARTIFACT_HIT_CACHE.putIfAbsent(key, found.get());
        }
        return found;
    }

    /**
     * The repository that served {@code coord}'s POM through this group, when the process memo still
     * holds it; null when no POM was asked here. Its answer on the artifact leg settles the coordinate:
     * a GAV's POM and its files are published together, so a jar it says is not there is not there.
     */
    private @Nullable MavenRepo pomHolder(Coordinate coord) {
        RepoFetched pom = liveHit(POM_HIT_CACHE, repoIdentity + "|" + coord.toGav());
        return pom == null ? null : pom.repo();
    }

    /**
     * As {@link #tryFetchArtifact(Coordinate)} but pinned: a warm hit — process memo, local
     * mirror — is used only when its digest matches {@code expectedSha256Hex}, and the network
     * leg goes through {@link MavenRepo#fetchArtifact(Coordinate, String, BooleanSupplier)}, which
     * evicts a stale mirror copy instead of returning it and refuses bytes whose digest is not the
     * pin with a {@link MavenRepo.ChecksumMismatchException} before anything places them. A present
     * answer therefore always carries the pinned digest; a remote that serves different bytes than
     * the lock pins is an exception here, never a value.
     */
    public Optional<RepoFetched> tryFetchArtifact(Coordinate coord, @Nullable String expectedSha256Hex)
            throws IOException, InterruptedException {
        if (expectedSha256Hex == null) return tryFetchArtifact(coord);
        String key = repoIdentity
                + "|"
                + coord.toGav()
                + "\0"
                + (coord.type() == null ? "" : coord.type())
                + "\0"
                + (coord.classifier() == null ? "" : coord.classifier());
        RepoFetched hit = liveHit(ARTIFACT_HIT_CACHE, key);
        if (hit != null) {
            if (expectedSha256Hex.equalsIgnoreCase(hit.fetched().sha256())) return Optional.of(hit);
            // The memo describes bytes the pin no longer accepts. The re-fetch below evicts and
            // replaces the file at the same path, and a memo left in place would then answer the
            // next unpinned fetch with the old digest for the new bytes — which the lock records,
            // and every later sync fails against.
            ARTIFACT_HIT_CACHE.remove(key, hit);
        }
        Optional<RepoFetched> found = tryFetch(
                coord,
                (repo, c) -> repo.tryLocalArtifact(c).filter(f -> expectedSha256Hex.equalsIgnoreCase(f.sha256())),
                (repo, c) -> repo.fetchArtifact(c, expectedSha256Hex, NO_ABORT),
                NO_ABORT,
                pomHolder(coord),
                false);
        if (found.isPresent()) {
            admit(ARTIFACT_HIT_CACHE, HIT_CACHE_MAX);
            // put, not putIfAbsent: a pinned fetch is the authority on what is on disk now.
            ARTIFACT_HIT_CACHE.put(key, found.get());
        }
        return found;
    }

    /**
     * Return a process-memo hit only when its on-disk payload is still present; drop stale paths
     * (cache GC / manual wipe mid-process).
     */
    private static @Nullable RepoFetched liveHit(ConcurrentHashMap<String, RepoFetched> cache, String key) {
        RepoFetched hit = cache.get(key);
        if (hit == null) return null;
        Path path = hit.fetched().cachePath();
        if (path != null && Files.isRegularFile(path)) return hit;
        cache.remove(key, hit);
        return null;
    }

    public Optional<RepoFetched> tryFetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        return tryFetch(coord, (repo, c) -> Optional.empty(), MavenRepo::fetchMetadata);
    }

    /** {@link #availableVersions(Coordinate, Set, boolean)} wanting nothing in particular: releases only. */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        return availableVersions(coord, Set.of(), false);
    }

    /**
     * Release-version discovery across eligible remotes, the way Maven finds an exact version:
     * repos are asked in order (repo order is the precedence contract — Central before Google for
     * unbound GAs, exclusive claimants alone for claimed groups) and the walk stops at the first
     * repo that advertises a release, unless a version in {@code wanted} — an exact pin, a version
     * a POM edge wrote — is still missing, in which case the remaining repos are asked and their
     * catalogs unioned. A repo whose policy leaves out releases is not asked for them, and its
     * {@code -SNAPSHOT} entries are dropped from every repo whose policy leaves out snapshots.
     *
     * <p>{@code snapshots} adds the {@code -SNAPSHOT} entries of every snapshot-serving repo: the
     * {@code snapshot} selector, or a wanted version that is one. Without it a snapshot is never a
     * candidate, so a floating selector cannot land on one.
     *
     * <p>One {@code maven-metadata.xml} read per GAV is the common case: the first repo answers
     * and nothing is wanted beyond what it lists. When a version is wanted, every catalog is read
     * at once and the union is folded in repository order, so a catalog split across remotes costs
     * the slowest read rather than one read per remote.
     */
    public List<String> availableVersions(Coordinate coord, Set<String> wanted, boolean snapshots)
            throws IOException, InterruptedException {
        // The session's offline flag changes what MavenRepo.availableVersions even measures
        // (local store listing vs remote metadata), so it is part of the question: an --offline
        // session's [] must not poison an online session for the TTL, nor may network-derived
        // lists leak into offline resolves.
        boolean offline = SessionContext.current().config().offlineOr(false);
        String key = (offline ? "offline|" : "online|") + (snapshots ? "snapshots|" : "") + repoIdentity + "|"
                + coord.group() + ":" + coord.artifact();
        // Force means the caller does not trust any cached view of what exists; a loopback
        // repository's answer speaks for the process that holds the port right now.
        boolean memoable = !MavenMetadataCache.forceRevalidate() && memoizesEveryRepo();
        if (memoable) {
            VersionsEntry cached = VERSIONS_CACHE.get(key);
            if (cached != null && !cached.expired() && cached.versions().containsAll(wanted)) return cached.versions();
            if (cached != null) VERSIONS_CACHE.remove(key, cached);
        }
        List<MavenRepo> eligible = eligibleRepos(coord);
        List<MavenRepo> asked = new ArrayList<>();
        for (MavenRepo repo : eligible) {
            if (repo.servesReleases() || (snapshots && repo.servesSnapshots())) asked.add(repo);
        }
        for (MavenRepo repo : lastResortRepos(coord, eligible)) {
            if (repo.servesReleases() || (snapshots && repo.servesSnapshots())) asked.add(repo);
        }
        // With nothing asked for by name the first catalog that answers ends the walk, so it alone
        // is read. When a version is wanted, every catalog is read at once and folded in repository
        // order: the answer a repo-by-repo walk gives, at the wall of the slowest remote rather than
        // the sum of them.
        boolean fanOut = asked.size() > 1 && (!wanted.isEmpty() || snapshots);
        List<RepoLegs.Leg<List<String>>> catalogs =
                fanOut ? RepoLegs.legs(asked, repo -> repo.availableVersions(coord)) : List.of();
        LinkedHashSet<String> union = new LinkedHashSet<>();
        boolean releaseFound = false;
        IOException firstFailure = null;
        try {
            for (int i = 0; i < asked.size(); i++) {
                MavenRepo repo = asked.get(i);
                if (releaseFound && union.containsAll(wanted) && !(snapshots && repo.servesSnapshots())) continue;
                List<String> found;
                try {
                    found = fanOut ? RepoLegs.await(catalogs.get(i).future()) : repo.availableVersions(coord);
                } catch (MavenRepo.RepositoryUnreachableException dead) {
                    throw dead; // nothing answers there: the resolve stops, as on the POM leg
                } catch (IOException transport) {
                    // One remote's 429, 5xx or reset is that remote's problem, not an answer about the
                    // coordinate: the remaining candidates are still asked. Said once per run per
                    // repository, because a warm multi-repo lock asks this hundreds of times.
                    if (firstFailure == null) firstFailure = transport;
                    RunNotices.warnOnce(
                            "repo-unreachable:" + repo.name(),
                            () -> "jk: warning: repository " + repo.name() + " is unreachable (" + describe(transport)
                                    + "); trying the remaining repositories");
                    continue;
                }
                fold(found, repo, snapshots, union);
                releaseFound |= union.stream().anyMatch(v -> !Versions.isSnapshot(v));
            }
        } finally {
            RepoLegs.settle(catalogs);
        }
        // A catalog can end below a release its directory serves (Central's jfree:jfreechart lists
        // up to 1.0.1 and serves 1.0.13). Maven never reads the catalog for an exact version, so a
        // wanted version no catalog lists is probed by its POM before it is refused.
        for (String v : wanted) {
            if (union.contains(v) || (Versions.isSnapshot(v) && !snapshots)) continue;
            if (servesUnlisted(Coordinate.of(coord.group(), coord.artifact(), v))) union.add(v);
        }
        // Every candidate failed to answer: that is the failure, not an empty catalog, and an
        // empty answer must not be memoised over it.
        if (union.isEmpty() && firstFailure != null) throw firstFailure;
        // An empty answer is cached too, after a full miss — rare; avoids re-statting empty GAs
        // every expand. Expires like any other entry: an artifact that does not exist yet may exist
        // later.
        List<String> immutable = List.copyOf(union);
        if (memoable) {
            admit(VERSIONS_CACHE, VERSIONS_CACHE_MAX);
            VERSIONS_CACHE.put(key, new VersionsEntry(immutable, Clock.SYSTEM.nanos() + VERSIONS_TTL_NANOS));
        }
        return immutable;
    }

    /** Whether some repository here serves {@code coord}'s POM although no catalog listed its version. */
    private boolean servesUnlisted(Coordinate coord) throws InterruptedException {
        try {
            return tryFetchPom(coord).isPresent();
        } catch (IOException e) {
            return false; // unreachable or refusing: the catalogs' answer stands
        }
    }

    /** True when every repository here is one whose answers the process memos may keep; see {@link RepoMisses#memoizes}. */
    private boolean memoizesEveryRepo() {
        for (MavenRepo repo : repos) {
            if (!RepoMisses.memoizes(repo.baseUrl())) return false;
        }
        return true;
    }

    /** Add {@code found} to {@code union} under {@code repo}'s policy: releases always, snapshots only when asked. */
    private static void fold(List<String> found, MavenRepo repo, boolean snapshots, Set<String> union) {
        for (String v : found) {
            boolean snapshot = Versions.isSnapshot(v);
            if (snapshot ? snapshots && repo.servesSnapshots() : repo.servesReleases()) union.add(v);
        }
    }

    /**
     * The repositories {@code coord}'s group is asked of, in order: the eligible ones (exclusive
     * bindings applied) and then the last-resort specialists. For a diagnostic that names them.
     */
    public List<MavenRepo> repositoriesFor(Coordinate coord) {
        List<MavenRepo> eligible = eligibleRepos(coord);
        List<MavenRepo> out = new ArrayList<>(eligible);
        out.addAll(lastResortRepos(coord, eligible));
        return List.copyOf(out);
    }

    /**
     * Repos that may discover/fetch {@code coord}:
     *
     * <ul>
     * <li>When the group is exclusively claimed — only the claiming repos (dependency-confusion
     * defense).
     * <li>When the group is routed — only the routing repos; the rest are last resort.
     * <li>Otherwise — general (unbound) repos only. Bound specialists (e.g. JumpKick
     * first-party) are skipped so warm multi-repo re-locks do not HTTP-404 every Maven Central
     * GAV against them.
     * </ul>
     */
    List<MavenRepo> eligibleRepos(Coordinate coord) {
        // Priority (path/git) repos always answer first — even for claimed groups
        // the workspace build outranks whatever a remote binding would serve.
        List<MavenRepo> out = new ArrayList<>(repos.subList(0, priorityCount));
        List<Integer> claimants = ExclusiveGroups.claimantIndices(exclusiveGroups, coord.group());
        if (claimants.isEmpty()) claimants = ExclusiveGroups.claimantIndices(routedGroups, coord.group());
        if (!claimants.isEmpty()) {
            for (int i : claimants) {
                if (i >= priorityCount) out.add(repos.get(i));
            }
            return out;
        }
        int before = out.size();
        for (int i = priorityCount; i < repos.size(); i++) {
            if (unbound(i)) out.add(repos.get(i));
        }
        // Safety: if every trailing repo is bound and none claimed this group, fall back to
        // all of them (otherwise unbound coords would be unresolvable).
        if (out.size() == before) {
            out.addAll(repos.subList(priorityCount, repos.size()));
        }
        return out;
    }

    /** {@code true} when repository {@code i} carries no binding of either kind. */
    private boolean unbound(int i) {
        return exclusiveGroups.get(i).isEmpty() && routedGroups.get(i).isEmpty();
    }

    /**
     * Repos asked after every eligible repo missed. For an <em>exclusively claimed</em> group
     * this is empty: the claimants' miss stays a miss (dependency-confusion defense). For a
     * <em>routed</em> group it is every other repository, the unbound ones first: the routing
     * repos serve part of the namespace and the rest lives elsewhere. For an <em>unclaimed</em>
     * group it is the bound specialists that did not claim it: Google Maven hosts plenty of groups
     * outside the built-in routing list ({@code com.google.gms}, {@code com.google.ar}, {@code
     * org.chromium.net}, ...) — skipping specialists on the fast path is a perf choice and must
     * not make those coordinates unresolvable.
     */
    private List<MavenRepo> lastResortRepos(Coordinate coord, List<MavenRepo> alreadyAsked) {
        if (!ExclusiveGroups.claimantIndices(exclusiveGroups, coord.group()).isEmpty()) {
            return List.of();
        }
        List<MavenRepo> out = new ArrayList<>();
        for (int i = priorityCount; i < repos.size(); i++) {
            MavenRepo r = repos.get(i);
            if (unbound(i) && !alreadyAsked.contains(r)) out.add(r);
        }
        for (int i = priorityCount; i < repos.size(); i++) {
            MavenRepo r = repos.get(i);
            if (!unbound(i) && !alreadyAsked.contains(r)) out.add(r);
        }
        return out;
    }

    /**
     * Per-repo local-then-remote, in repo order — each eligible repo's warm mirror is
     * probed before its remote leg, but a LATER repo's warm mirror can never shadow an EARLIER
     * repo — order is the precedence contract. (The no-HTTP-404 property still holds:
     * bound specialists are already skipped by {@link #eligibleRepos}, and the first
     * eligible repo's warm mirror short-circuits without network.)
     */
    /** The resolve leg — a POM or a metadata file: which versions exist and what they need. */
    private Optional<RepoFetched> tryFetch(Coordinate coord, LocalProbe localProbe, Fetcher fetcher)
            throws IOException, InterruptedException {
        return tryFetch(coord, localProbe, fetcher, NO_ABORT, null, true);
    }

    /**
     * @param holder the repository whose answer settles {@code coord} — the one that served its POM —
     *     or null when none is known, in which case every candidate's answer counts
     * @param resolve true on the resolve leg, where a repository nothing answers at stops the work;
     *     false for pinned bytes, which fall through it
     */
    private Optional<RepoFetched> tryFetch(
            Coordinate coord,
            LocalProbe localProbe,
            Fetcher fetcher,
            BooleanSupplier abort,
            @Nullable MavenRepo holder,
            boolean resolve)
            throws IOException, InterruptedException {
        List<MavenRepo> eligible = eligibleRepos(coord);
        FetchFanOut fanOut = new FetchFanOut(coord, holder);
        Optional<RepoFetched> found =
                tryFetchFrom(serving(eligible, coord), coord, localProbe, fetcher, abort, fanOut, resolve);
        if (found.isPresent()) return found;
        // Full miss on the fast path: consult non-claiming specialists before giving up.
        found = tryFetchFrom(
                serving(lastResortRepos(coord, eligible), coord), coord, localProbe, fetcher, abort, fanOut, resolve);
        if (found.isPresent()) return found;
        fanOut.settle();
        return Optional.empty();
    }

    /** {@code repos} whose policy covers {@code coord}'s version: a snapshot is never asked of a releases-only repo. */
    private static List<MavenRepo> serving(List<MavenRepo> repos, Coordinate coord) {
        List<MavenRepo> out = new ArrayList<>(repos.size());
        for (MavenRepo repo : repos) {
            if (repo.serves(coord.version())) out.add(repo);
        }
        return out;
    }

    /**
     * The first candidate, in repository order, that has {@code coord}: a local mirror copy or a
     * network answer. Every candidate ahead of the first local copy is asked over the network at
     * once, and the answers are read in order, so a coordinate a later repository holds costs the
     * slowest earlier miss, never the sum of them; each repository mirrors into its own directory,
     * so a fetch that loses to an earlier answer leaves nothing another repository's copy could
     * collide with.
     */
    private Optional<RepoFetched> tryFetchFrom(
            List<MavenRepo> candidates,
            Coordinate coord,
            LocalProbe localProbe,
            Fetcher fetcher,
            BooleanSupplier abort,
            FetchFanOut fanOut,
            boolean resolve)
            throws IOException, InterruptedException {
        int networkLegs = candidates.size();
        Optional<RepoFetched> local = Optional.empty();
        for (int i = 0; i < candidates.size(); i++) {
            MavenRepo repo = candidates.get(i);
            Optional<MavenRepo.Fetched> hit = localProbe.probe(repo, coord);
            if (hit.isPresent()) {
                local = Optional.of(new RepoFetched(repo, hit.get()));
                networkLegs = i;
                break;
            }
        }
        if (networkLegs == 0) return local;
        // boundary between the local-probe leg and the network leg — a lock that
        // already failed must not start another download; the probes above still completed.
        if (abort.getAsBoolean()) {
            throw new MavenRepo.FetchAbortedException(
                    "fetch aborted before network leg for " + coord + " (lock already failed)");
        }
        List<RepoLegs.Leg<MavenRepo.Fetched>> legs =
                RepoLegs.legs(candidates.subList(0, networkLegs), repo -> fetcher.fetch(repo, coord));
        try {
            for (int i = 0; i < networkLegs; i++) {
                try {
                    return Optional.of(new RepoFetched(
                            candidates.get(i), RepoLegs.await(legs.get(i).future())));
                } catch (MavenRepo.ArtifactNotFoundException notFound) {
                    fanOut.notFound(candidates.get(i)); // the next candidate in order
                } catch (MavenRepo.FetchAbortedException aborted) {
                    throw aborted;
                } catch (MavenRepo.RepositoryUnreachableException dead) {
                    // Nothing answers at this repository's address. On the resolve leg the next
                    // candidate is not asked: a lock computed without a configured repository is not
                    // the lock that was asked for, so the resolve stops here naming it. Pinned bytes
                    // still fall through — the lock's sha256 says what is accepted.
                    if (resolve) throw dead;
                    fanOut.failed(candidates.get(i), dead);
                } catch (IOException transport) {
                    // A failed remote falls through to the next candidate silently: the artifact that
                    // arrives is still checked against the lock's sha256, so where it came from does
                    // not change what is accepted. If nobody answers, the fan-out settles on the failure.
                    fanOut.failed(candidates.get(i), transport);
                }
            }
        } finally {
            RepoLegs.settle(legs);
        }
        return local;
    }

    /** Abort supplier for fetch paths with no abort semantics (POM / metadata). */
    private static final BooleanSupplier NO_ABORT = () -> false;

    private static List<List<String>> normalizePatterns(int n, @Nullable List<List<String>> raw) {
        List<List<String>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (raw != null
                    && i < raw.size()
                    && raw.get(i) != null
                    && !raw.get(i).isEmpty()) {
                out.add(List.copyOf(raw.get(i)));
            } else {
                out.add(List.of());
            }
        }
        return List.copyOf(out);
    }

    public record RepoFetched(MavenRepo repo, MavenRepo.Fetched fetched) {}

    @FunctionalInterface
    private interface Fetcher {
        MavenRepo.Fetched fetch(MavenRepo repo, Coordinate coord) throws IOException, InterruptedException;
    }

    private interface LocalProbe {
        Optional<MavenRepo.Fetched> probe(MavenRepo repo, Coordinate coord);
    }
    /** The failure and the root cause it wraps, because "failed after 6 attempts" alone names no fault. */
    static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String head = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return root == failure ? head : head + " — " + root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
