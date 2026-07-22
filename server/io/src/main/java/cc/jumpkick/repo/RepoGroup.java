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
        return tryFetch(coord, MavenRepo::fetchPom);
    }

    public Optional<RepoFetched> tryFetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return tryFetch(coord, MavenRepo::fetchArtifact);
    }

    public Optional<RepoFetched> tryFetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        return tryFetch(coord, MavenRepo::fetchMetadata);
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
     * Repos that may discover/fetch {@code coord}: claimants when the group is exclusively bound,
     * otherwise the full ordered list.
     */
    List<MavenRepo> eligibleRepos(Coordinate coord) {
        List<Integer> claimants = ExclusiveGroups.claimantIndices(exclusiveGroups, coord.group());
        if (claimants.isEmpty()) return repos;
        List<MavenRepo> out = new ArrayList<>(claimants.size());
        for (int i : claimants) out.add(repos.get(i));
        return out;
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
            if (raw != null && i < raw.size() && raw.get(i) != null && !raw.get(i).isEmpty()) {
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
