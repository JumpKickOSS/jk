// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Ordered {@link MavenRepo}s with try-each / first-hit-wins semantics, plus optional exclusive
 * group bindingswhen a coordinate's group is claimed by one or more repos, only those
 * repos participate in version discovery and fetch.
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

    /**
     * Same for non-POM artifacts (Gradle {@code .module}, jars). Keyed by repositories + GAVC+type.
     */
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
     * first resolve would stay invisible.
     */
    private static final ConcurrentHashMap<String, VersionsEntry> VERSIONS_CACHE = new ConcurrentHashMap<>();

    /** Long enough to cover one build's resolves, short enough that a daemon re-checks. */
    private static final long VERSIONS_TTL_NANOS = Duration.ofSeconds(60).toNanos();

    private record VersionsEntry(List<String> versions, long expiresAtNanos) {
        boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }

    private static final int HIT_CACHE_MAX = 16_384;
    private static final int VERSIONS_CACHE_MAX = 8_192;

    /** Test seam — drop process fetch memos. */
    public static void clearProcessFetchCache() {
        POM_HIT_CACHE.clear();
        ARTIFACT_HIT_CACHE.clear();
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
    /**
     * The first {@code priorityCount} repos are workspace-local materializations (path/git):
     * always eligible and always consulted first, even for exclusively-claimed groups — a
     * locally-built artifact outranks any remote binding.
     */
    private final int priorityCount;

    public RepoGroup(List<MavenRepo> repos) {
        this(repos, null);
    }

    /**
     * @param exclusiveGroups parallel list of exclusive group patterns per repo; {@code null} or
     * shorter lists are treated as no bindings for those entries
     */
    public RepoGroup(List<MavenRepo> repos, @Nullable List<List<String>> exclusiveGroups) {
        this(repos, exclusiveGroups, 0);
    }

    private RepoGroup(List<MavenRepo> repos, @Nullable List<List<String>> exclusiveGroups, int priorityCount) {
        Objects.requireNonNull(repos, "repos");
        if (repos.isEmpty()) {
            throw new IllegalArgumentException("RepoGroup must contain at least one repo");
        }
        this.repos = List.copyOf(repos);
        this.exclusiveGroups = normalizeExclusive(this.repos.size(), exclusiveGroups);
        this.priorityCount = priorityCount;
        // Exclusive bindings and the priority prefix change which repos are eligible for a
        // coordinate, so they are part of the question every memo answers — two groups with the
        // same URLs but different bindings must never share memo entries.
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < this.repos.size(); i++) {
            if (i > 0) id.append(',');
            id.append(this.repos.get(i).baseUrl());
            List<String> excl = this.exclusiveGroups.get(i);
            if (!excl.isEmpty()) id.append('!').append(String.join(";", excl));
        }
        this.repoIdentity = id.append("|p").append(priorityCount).toString();
    }

    public static RepoGroup of(MavenRepo single) {
        return new RepoGroup(List.of(single));
    }

    /**
     * Prepend {@code leading} repos ahead of this group, keeping this group's exclusive bindings
     * aligned with the trailing repos. Used for path/git materialize repos: they answer before
     * remotes — including for exclusively-claimed groupswithout stripping JumpKick
     * exclusive groups (which would make every Central GAV HTTP-404 on jumpkick first).
     */
    public RepoGroup withReposPrepended(List<MavenRepo> leading) {
        if (leading == null || leading.isEmpty()) return this;
        List<MavenRepo> merged = new ArrayList<>(leading.size() + repos.size());
        merged.addAll(leading);
        merged.addAll(repos);
        List<List<String>> excl = new ArrayList<>(merged.size());
        for (int i = 0; i < leading.size(); i++) excl.add(List.of());
        excl.addAll(exclusiveGroups);
        return new RepoGroup(merged, excl, leading.size() + priorityCount);
    }

    /**
     * This group followed by {@code trailing}, which answer only when every repository here has
     * missed: the repositories a dependency's POM declares for its own subtree. They carry no
     * exclusive binding, and the priority prefix is unchanged.
     */
    public RepoGroup withReposAppended(List<MavenRepo> trailing) {
        if (trailing == null || trailing.isEmpty()) return this;
        List<MavenRepo> merged = new ArrayList<>(repos.size() + trailing.size());
        merged.addAll(repos);
        merged.addAll(trailing);
        List<List<String>> excl = new ArrayList<>(exclusiveGroups);
        for (int i = 0; i < trailing.size(); i++) excl.add(List.of());
        return new RepoGroup(merged, excl, priorityCount);
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

    public boolean hasExclusiveBindings() {
        return ExclusiveGroups.anyBinding(exclusiveGroups);
    }

    public Optional<RepoFetched> tryFetchPom(Coordinate coord) throws IOException, InterruptedException {
        String key = repoIdentity + "|" + coord.toGav();
        RepoFetched hit = liveHit(POM_HIT_CACHE, key);
        if (hit != null) return Optional.of(hit);
        Optional<RepoFetched> found = tryFetch(coord, MavenRepo::tryLocalPom, MavenRepo::fetchPom);
        if (found.isPresent() && POM_HIT_CACHE.size() < HIT_CACHE_MAX) {
            POM_HIT_CACHE.putIfAbsent(key, found.get());
        }
        return found;
    }

    public Optional<RepoFetched> tryFetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return tryFetchArtifact(coord, NO_ABORT);
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
        Optional<RepoFetched> found =
                tryFetch(coord, MavenRepo::tryLocalArtifact, (repo, c) -> repo.fetchArtifact(c, abort), abort);
        if (found.isPresent() && ARTIFACT_HIT_CACHE.size() < HIT_CACHE_MAX) {
            ARTIFACT_HIT_CACHE.putIfAbsent(key, found.get());
        }
        return found;
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
                (repo, c) -> repo.fetchArtifact(c, expectedSha256Hex, NO_ABORT));
        if (found.isPresent() && ARTIFACT_HIT_CACHE.size() < HIT_CACHE_MAX) {
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

    /**
     * Union of the versions of {@code coord}'s {@code group:artifact} available across eligible
     * repos (exclusive bindings applied), de-duplicated, preserving first-seen order.
     */
    /**
     * Version discovery across eligible remotes. Stops at the first repo that advertises any
     * versions (repo order is the precedence contract — Central before Google for unbound GAs,
     * exclusive claimants alone for claimed groups).
     *
     * <p>Previously this <em>unioned</em> every eligible remote's metadata, which forced a
     * second {@code maven-metadata.xml} read (often a 404) on every AndroidX GAV when both
     * Central and Google were declared — dominant on warm multi-repo locks (NIA). Split version
     * catalogs across remotes are vanishingly rare for our remotes; exclusive bindings still
     * restrict which remotes are eligible at all.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        // The session's offline flag changes what MavenRepo.availableVersions even measures
        // (local store listing vs remote metadata), so it is part of the question: an --offline
        // session's [] must not poison an online session for the TTL, nor may network-derived
        // lists leak into offline resolves.
        boolean offline = SessionContext.current().config().offlineOr(false);
        String key = (offline ? "offline|" : "online|") + repoIdentity + "|" + coord.group() + ":" + coord.artifact();
        // Force means the caller does not trust any cached view of what exists.
        boolean memoable = !MavenMetadataCache.forceRevalidate();
        if (memoable) {
            VersionsEntry cached = VERSIONS_CACHE.get(key);
            if (cached != null && !cached.expired()) return cached.versions();
            if (cached != null) VERSIONS_CACHE.remove(key, cached);
        }
        List<MavenRepo> eligible = eligibleRepos(coord);
        List<MavenRepo> asked = new ArrayList<>(eligible);
        asked.addAll(lastResortRepos(coord, eligible));
        IOException firstFailure = null;
        for (MavenRepo repo : asked) {
            List<String> found;
            try {
                found = repo.availableVersions(coord);
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
            if (!found.isEmpty()) {
                List<String> immutable = List.copyOf(found);
                if (memoable && VERSIONS_CACHE.size() < VERSIONS_CACHE_MAX) {
                    VERSIONS_CACHE.put(key, new VersionsEntry(immutable, System.nanoTime() + VERSIONS_TTL_NANOS));
                }
                return immutable;
            }
        }
        // Every candidate failed to answer: that is the failure, not an empty catalog, and an
        // empty answer must not be memoised over it.
        if (firstFailure != null) throw firstFailure;
        // Cache empty only after a full miss — rare; avoids re-statting empty GAs every expand.
        // Expires like any other entry: an artifact that does not exist yet may exist later.
        List<String> empty = List.of();
        if (memoable && VERSIONS_CACHE.size() < VERSIONS_CACHE_MAX) {
            VERSIONS_CACHE.put(key, new VersionsEntry(empty, System.nanoTime() + VERSIONS_TTL_NANOS));
        }
        return empty;
    }

    /**
     * Repos that may discover/fetch {@code coord}:
     *
     * <ul>
     * <li>When the group is exclusively claimed — only the claiming repos (dependency-confusion
     * defense).
     * <li>Otherwise — general (no exclusive binding) repos only. Exclusive-bound specialists
     * (e.g. JumpKick first-party) are skipped so warm multi-repo re-locks do not HTTP-404
     * every Maven Central GAV against them.
     * </ul>
     */
    List<MavenRepo> eligibleRepos(Coordinate coord) {
        // Priority (path/git) repos always answer first — even for claimed groups
        // the workspace build outranks whatever an exclusive remote binding would serve.
        List<MavenRepo> out = new ArrayList<>(repos.subList(0, priorityCount));
        List<Integer> claimants = ExclusiveGroups.claimantIndices(exclusiveGroups, coord.group());
        if (!claimants.isEmpty()) {
            for (int i : claimants) {
                if (i >= priorityCount) out.add(repos.get(i));
            }
            return out;
        }
        int before = out.size();
        for (int i = priorityCount; i < repos.size(); i++) {
            if (exclusiveGroups.get(i).isEmpty()) {
                out.add(repos.get(i));
            }
        }
        // Safety: if every trailing repo is exclusive and none claimed this group, fall back to
        // all of them (otherwise unbound coords would be unresolvable).
        if (out.size() == before) {
            out.addAll(repos.subList(priorityCount, repos.size()));
        }
        return out;
    }

    /**
     * Last-resort repos for an <em>unclaimed</em> group after every eligible repo missed:
     * exclusive specialists that did not claim it. Google Maven hosts plenty of groups outside
     * the built-in binding list ({@code com.google.gms}, {@code com.google.ar}, {@code
     * org.chromium.net}, ...) — skipping specialists on the fast path is a perf choice and must
     * not make those coordinates unresolvable. For a <em>claimed</em> group this is empty: a
     * miss in the claiming repos stays a miss (dependency-confusion defense).
     */
    private List<MavenRepo> lastResortRepos(Coordinate coord, List<MavenRepo> alreadyAsked) {
        if (!ExclusiveGroups.claimantIndices(exclusiveGroups, coord.group()).isEmpty()) {
            return List.of();
        }
        List<MavenRepo> out = new ArrayList<>();
        for (int i = priorityCount; i < repos.size(); i++) {
            MavenRepo r = repos.get(i);
            if (!exclusiveGroups.get(i).isEmpty() && !alreadyAsked.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Per-repo local-then-remote, in repo ordereach eligible repo's warm mirror is
     * probed before its remote leg, but a LATER repo's warm mirror can never shadow an EARLIER
     * repo — order is the precedence contract. (The no-HTTP-404 property still holds:
     * exclusive specialists are already skipped by {@link #eligibleRepos}, and the first
     * eligible repo's warm mirror short-circuits without network.)
     */
    private Optional<RepoFetched> tryFetch(Coordinate coord, LocalProbe localProbe, Fetcher fetcher)
            throws IOException, InterruptedException {
        return tryFetch(coord, localProbe, fetcher, NO_ABORT);
    }

    private Optional<RepoFetched> tryFetch(
            Coordinate coord, LocalProbe localProbe, Fetcher fetcher, BooleanSupplier abort)
            throws IOException, InterruptedException {
        List<MavenRepo> eligible = eligibleRepos(coord);
        Optional<RepoFetched> found = tryFetchFrom(eligible, coord, localProbe, fetcher, abort);
        if (found.isPresent()) return found;
        // Full miss on the fast path: consult non-claiming specialists before giving up.
        return tryFetchFrom(lastResortRepos(coord, eligible), coord, localProbe, fetcher, abort);
    }

    private Optional<RepoFetched> tryFetchFrom(
            List<MavenRepo> candidates, Coordinate coord, LocalProbe localProbe, Fetcher fetcher, BooleanSupplier abort)
            throws IOException, InterruptedException {
        IOException firstFailure = null;
        for (MavenRepo repo : candidates) {
            Optional<MavenRepo.Fetched> local = localProbe.probe(repo, coord);
            if (local.isPresent()) {
                return Optional.of(new RepoFetched(repo, local.get()));
            }
            // boundary between the local-probe leg and the network leg — a lock that
            // already failed must not start another download; the probe above still completed.
            if (abort.getAsBoolean()) {
                throw new MavenRepo.FetchAbortedException(
                        "fetch aborted before network leg for " + coord + " (lock already failed)");
            }
            try {
                MavenRepo.Fetched f = fetcher.fetch(repo, coord);
                return Optional.of(new RepoFetched(repo, f));
            } catch (MavenRepo.ArtifactNotFoundException ignored) {
                // try next eligible repo
            } catch (MavenRepo.FetchAbortedException aborted) {
                throw aborted;
            } catch (IOException transport) {
                // A failed remote falls through to the next candidate silently: the artifact that
                // arrives is still checked against the lock's sha256, so where it came from does
                // not change what is accepted. If nobody answers, the first failure is the reason.
                if (firstFailure == null) firstFailure = transport;
            }
        }
        if (firstFailure != null) throw firstFailure;
        return Optional.empty();
    }

    /** Abort supplier for fetch paths with no abort semantics (POM / metadata). */
    private static final BooleanSupplier NO_ABORT = () -> false;

    private static List<List<String>> normalizeExclusive(int n, @Nullable List<List<String>> raw) {
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
