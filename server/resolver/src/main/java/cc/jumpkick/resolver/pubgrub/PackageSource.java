// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * What the solver sees of the dependency universe: a list of available versions per package and the
 * dependency edges of each (package, version) pair. Decoupled from {@code MavenRepo} so the solver
 * can be tested in-process with {@link InMemoryPackageSource}; production uses {@code
 * MavenPackageSource}.
 */
public interface PackageSource {

    /**
     * @return all known versions of {@code pkg}, ordered from <b>highest</b> (preferred) to lowest.
     * PubGrub picks the first version that satisfies the active constraints.
     */
    List<String> versions(String pkg) throws IOException, InterruptedException;

    /**
     * The full advertised candidate list for widen-on-failure. Sources whose
     * {@link #versions} is compacted for the happy path return the un-capped history here;
     * the solver caps it. Default: same as {@link #versions}.
     */
    default List<String> expandedVersions(String pkg) throws IOException, InterruptedException {
        return versions(pkg);
    }

    /**
     * Soft-prefer pin for {@code pkg} when known (BOM or prior lock), without consulting
     * maven-metadata. Empty when the source has no preference. The solver may seed a singleton
     * universe from this and only call {@link #versions} if that pin fails or cannot satisfy
     * constraints lazy universe).
     */
    default Optional<String> preferredVersion(String pkg) {
        return Optional.empty();
    }

    /**
     * @return dependency edges of {@code (pkg, version)} as {@link Term}s. Each Term gives a
     * downstream package and the version range the parent requires of it.
     * @throws VersionUnavailableException when this exact version is <em>definitively</em> absent
     * (its metadata advertised it but its POM 404s everywhere — a half-published release
     * mid-propagation). The solver retreats to the next candidate instead of failing. Plain
     * {@link IOException}s (network failures) stay fatal — retreating on a flake would
     * silently resolve older versions.
     */
    List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException;

    /**
     * A version the metadata advertises but whose backing data is definitively gone/not-yet-there
     * (all-repos 404, not a transient failure). Thrown by {@link #dependencies}; the solver
     * excludes exactly that version and picks again.
     */
    final class VersionUnavailableException extends IOException {
        public VersionUnavailableException(String message) {
            super(message);
        }
    }
}
