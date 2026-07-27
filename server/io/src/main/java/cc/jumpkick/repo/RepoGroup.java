// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordered {@link MavenRepo}s with try-each / first-hit-wins semantics, plus optional exclusive
 * group bindings (JK-1064): when a coordinate's group is claimed by one or more repos, only those
 * repos participate in version discovery and fetch.
 */
public final class RepoGroup {

    private final List<MavenRepo> repos;
    /** Parallel to {@link #repos}: exclusive group patterns per repo (empty = no exclusive claim). */
    private final List<List<String>> exclusiveGroups;

    public RepoGroup(List<MavenRepo> repos) {
        this(repos, null);
    }

    /**
     * @param exclusiveGroups parallel list of exclusive group patterns per repo; {@code null} or
     *     shorter lists are treated as no bindings for those entries
     */
    public RepoGroup(List<MavenRepo> repos, List<List<String>> exclusiveGroups) {
        Objects.requireNonNull(repos, "repos");
        if (repos.isEmpty()) {
            throw new IllegalArgumentException("RepoGroup must contain at least one repo");
        }
        this.repos = List.copyOf(repos);
        this.exclusiveGroups = normalizeExclusive(this.repos.size(), exclusiveGroups);
    }

    public static RepoGroup of(MavenRepo single) {
        return new RepoGroup(List.of(single));
    }

    /**
     * Prepend {@code leading} repos (no exclusive claims) ahead of this group, keeping this
     * group's exclusive bindings aligned with the trailing repos. Used for path/git materialize
     * repos that must answer before remotes without stripping JumpKick exclusive groups (which
     * would make every Central GAV HTTP-404 on jumpkick first).
     */
    public RepoGroup withReposPrepended(List<MavenRepo> leading) {
        if (leading == null || leading.isEmpty()) return this;
        List<MavenRepo> merged = new ArrayList<>(leading.size() + repos.size());
        merged.addAll(leading);
        merged.addAll(repos);
        List<List<String>> excl = new ArrayList<>(merged.size());
        for (int i = 0; i < leading.size(); i++) excl.add(List.of());
        excl.addAll(exclusiveGroups);
        return new RepoGroup(merged, excl);
    }

    public List<MavenRepo> repos() {
        return repos;
    }

    /** Exclusive group patterns aligned with {@link #repos()}. */
    public List<List<String>> exclusiveGroups() {
        return exclusiveGroups;
    }

    public boolean hasExclusiveBindings() {
        return ExclusiveGroups.anyBinding(exclusiveGroups);
    }

    public Optional<RepoFetched> tryFetchPom(Coordinate coord) throws IOException, InterruptedException {
        // Local-first across eligible repos so JumpKick/Google do not HTTP-404 every Central GAV
        // on a warm re-lock (JK-1202).
        Optional<RepoFetched> local = tryLocalPom(coord);
        if (local.isPresent()) return local;
        return tryFetch(coord, MavenRepo::fetchPom);
    }

    public Optional<RepoFetched> tryFetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        Optional<RepoFetched> local = tryLocalArtifact(coord);
        if (local.isPresent()) return local;
        return tryFetch(coord, MavenRepo::fetchArtifact);
    }

    public Optional<RepoFetched> tryFetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        return tryFetch(coord, MavenRepo::fetchMetadata);
    }

    /** Any eligible repo's local mirror of the artifact, without network. */
    public Optional<RepoFetched> tryLocalArtifact(Coordinate coord) {
        for (MavenRepo repo : eligibleRepos(coord)) {
            Optional<MavenRepo.Fetched> f = repo.tryLocalArtifact(coord);
            if (f.isPresent()) return Optional.of(new RepoFetched(repo, f.get()));
        }
        return Optional.empty();
    }

    /** Any eligible repo's local mirror of the POM, without network. */
    public Optional<RepoFetched> tryLocalPom(Coordinate coord) {
        for (MavenRepo repo : eligibleRepos(coord)) {
            Optional<MavenRepo.Fetched> f = repo.tryLocalPom(coord);
            if (f.isPresent()) return Optional.of(new RepoFetched(repo, f.get()));
        }
        return Optional.empty();
    }

    /**
     * Union of the versions of {@code coord}'s {@code group:artifact} available across eligible
     * repos (exclusive bindings applied), de-duplicated, preserving first-seen order.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
        for (MavenRepo repo : eligibleRepos(coord)) {
            union.addAll(repo.availableVersions(coord));
        }
        return List.copyOf(union);
    }

    /**
     * Repos that may discover/fetch {@code coord}:
     *
     * <ul>
     *   <li>When the group is exclusively claimed — only the claiming repos (dependency-confusion
     *       defense).
     *   <li>Otherwise — general (no exclusive binding) repos only. Exclusive-bound specialists
     *       (e.g. JumpKick first-party) are skipped so warm multi-repo re-locks do not HTTP-404
     *       every Maven Central GAV against them (JK-1202).
     * </ul>
     */
    List<MavenRepo> eligibleRepos(Coordinate coord) {
        List<Integer> claimants = ExclusiveGroups.claimantIndices(exclusiveGroups, coord.group());
        if (!claimants.isEmpty()) {
            List<MavenRepo> out = new ArrayList<>(claimants.size());
            for (int i : claimants) out.add(repos.get(i));
            return out;
        }
        List<MavenRepo> general = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            if (exclusiveGroups.get(i).isEmpty()) {
                general.add(repos.get(i));
            }
        }
        // Safety: if every repo is exclusive and none claimed this group, fall back to all
        // (otherwise unbound coords would be unresolvable).
        return general.isEmpty() ? repos : general;
    }

    private Optional<RepoFetched> tryFetch(Coordinate coord, Fetcher fetcher) throws IOException, InterruptedException {
        for (MavenRepo repo : eligibleRepos(coord)) {
            try {
                MavenRepo.Fetched f = fetcher.fetch(repo, coord);
                return Optional.of(new RepoFetched(repo, f));
            } catch (MavenRepo.ArtifactNotFoundException ignored) {
                // try next eligible repo
            }
        }
        return Optional.empty();
    }

    private static List<List<String>> normalizeExclusive(int n, List<List<String>> raw) {
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
}
