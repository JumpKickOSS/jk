// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.repo.RepoMirrors;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Populate {@link Lockfile.Artifact#sourcesChecksum} for the Maven rows whose {@code -sources.jar}
 * a repository serves. Not every package publishes sources: a 404 leaves the row as it was, and
 * so does a non-Maven source or a row that already carries a sources checksum. A row's sources are
 * asked of the repositories that could have served the row: the project's, and when its {@code
 * source} names a repository outside them — one a dependency POM declared — that repository too,
 * rebuilt with the releases/snapshots policy the POM wrote, so a releases-only repository is not
 * asked for a snapshot's sources.
 */
final class SourcesAttacher {

    private final RepoGroup repos;

    /** URL → the POM declaration of a repository outside the project's set, carrying its policy. */
    private final Map<String, Pom.Repository> declaredByUrl;

    /** Source URL → the project's group with the row's own repository appended. */
    private final Map<String, RepoGroup> withDeclared = new HashMap<>();

    /**
     * @param declaredByUrl the repositories dependency POMs declared during the lock, by URL; a
     *     source URL absent here is rebuilt serving releases and snapshots alike
     */
    SourcesAttacher(RepoGroup repos, Map<String, Pom.Repository> declaredByUrl) {
        this.repos = repos;
        this.declaredByUrl = Map.copyOf(declaredByUrl);
    }

    /**
     * Try to fetch {@code -sources.jar} for every Maven package in {@code lock} and return a copy
     * with {@link Lockfile.Artifact#sourcesChecksum} populated where sources exist. Packages that
     * return 404, have a non-maven source, or already have a sources checksum are left unchanged.
     */
    Lockfile attach(Lockfile lock) throws InterruptedException {
        List<Lockfile.Artifact> updated = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            // A file dependency, a git checkout and a row with a sources checksum already have
            // nothing to fetch; every other row came from a repository spelled `id+url`.
            if (RepoArtifactResolver.JK_LOCAL.equals(pkg.source())
                    || pkg.git() != null
                    || pkg.sourcesChecksum() != null
                    || pkg.source().indexOf('+') <= 0
                    || pkg.name().indexOf(':') < 0) {
                updated.add(pkg);
                continue;
            }
            Coordinate sourcesCoord =
                    new Coordinate(pkg.moduleGroup(), pkg.moduleArtifact(), pkg.version(), "sources", "jar");
            try {
                RepoGroup.RepoFetched hit =
                        reposFor(pkg).tryFetchArtifact(sourcesCoord).orElse(null);
                if (hit != null) {
                    updated.add(
                            pkg.withSourcesChecksum("sha256:" + hit.fetched().sha256()));
                    continue;
                }
            } catch (Exception e) {
                /* sources not available for this package */
                Log.debug("attach: sources not available for this package", e);
            }
            updated.add(pkg);
        }
        return lock.withArtifacts(updated);
    }

    /**
     * The group a row's sources are asked of: the project's repositories, with the repository the
     * row's {@code source} ({@code id+url}) names appended when none of them is it, under the policy
     * its POM declared and behind the mirror {@code settings.xml} routes it through, as the resolver
     * built it.
     */
    private RepoGroup reposFor(Lockfile.Artifact pkg) {
        int plus = pkg.source().indexOf('+');
        if (plus <= 0 || plus == pkg.source().length() - 1) return repos;
        String id = pkg.source().substring(0, plus);
        String url = pkg.source().substring(plus + 1);
        for (MavenRepo repo : repos.repos()) {
            if (repo.baseUrl().toString().equals(url)) return repos;
        }
        return withDeclared.computeIfAbsent(url, u -> {
            try {
                Pom.Repository policy = declaredByUrl.get(u);
                boolean releases = policy == null || policy.releases();
                boolean snapshots = policy == null || policy.snapshots();
                MavenRepo declared =
                        RepoMirrors.apply(repos.repos().getFirst().declaredByPom(id, new URI(u), releases, snapshots));
                return repos.withReposAppended(List.of(declared));
            } catch (URISyntaxException e) {
                return repos;
            }
        });
    }
}
