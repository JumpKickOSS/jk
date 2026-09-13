// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.RepoGroup;
import java.util.ArrayList;
import java.util.List;

/**
 * Populate {@link Lockfile.Artifact#sourcesChecksum} for the Maven rows whose {@code -sources.jar}
 * a repository serves. Not every package publishes sources: a 404 leaves the row as it was, and
 * so does a non-Maven source or a row that already carries a sources checksum.
 */
final class SourcesAttacher {

    private final RepoGroup repos;

    SourcesAttacher(RepoGroup repos) {
        this.repos = repos;
    }

    /**
     * Try to fetch {@code -sources.jar} for every Maven package in {@code lock} and return a copy
     * with {@link Lockfile.Artifact#sourcesChecksum} populated where sources exist. Packages that
     * return 404, have a non-maven source, or already have a sources checksum are left unchanged.
     */
    Lockfile attach(Lockfile lock) throws InterruptedException {
        List<Lockfile.Artifact> updated = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (!pkg.source().contains("maven") && !pkg.source().startsWith(RepositorySpec.CENTRAL)
                    || pkg.sourcesChecksum() != null) {
                updated.add(pkg);
                continue;
            }
            if (pkg.name().indexOf(':') < 0) {
                updated.add(pkg);
                continue;
            }
            Coordinate sourcesCoord =
                    new Coordinate(pkg.moduleGroup(), pkg.moduleArtifact(), pkg.version(), "sources", "jar");
            try {
                RepoGroup.RepoFetched hit = repos.tryFetchArtifact(sourcesCoord).orElse(null);
                if (hit != null) {
                    updated.add(new Lockfile.Artifact(
                            pkg.name(),
                            pkg.version(),
                            pkg.source(),
                            pkg.checksum(),
                            pkg.path(),
                            pkg.scopes(),
                            pkg.deps(),
                            pkg.pinnedBy(),
                            pkg.git(),
                            "sha256:" + hit.fetched().sha256(),
                            pkg.declared()));
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
}
