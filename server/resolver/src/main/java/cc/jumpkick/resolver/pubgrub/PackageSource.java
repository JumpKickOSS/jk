// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
     * Versions that dependency edges expanded so far have named for {@code pkg} as a plain version
     * (a POM's {@code <version>1.2</version>}, not a range). The solver resolves a package whose
     * constraints are all such floors to the highest of these rather than to the newest release
     * the repository advertises. Default: none known.
     */
    default Set<String> declaredVersions(String pkg) {
        return Set.of();
    }

    /**
     * Why the candidate list of {@code pkg} lacks what was asked of it, when the source knows: one
     * sentence per reason, rendered under the refusal. A snapshot asked of repositories that serve
     * releases only is the shape this exists for. Default: nothing to add.
     */
    default List<String> refusalNotes(String pkg) {
        return List.of();
    }

    /**
     * Optional pre-solve warm (BOM pins, lock prefs, root exact pins). Default: no-op. Production
     * Maven sources parallel-load known pins so the first decide frontier is not cold on disk.
     */
    default void warmUp() {}

    /**
     * Read the full candidate lists of {@code pkgs} at once, returning when they are memoized (or
     * the source's bound on waiting has passed), ahead of a widening pass that asks {@link
     * #expandedVersions} for each in turn. Default: no-op.
     */
    default void warmExpandedVersions(List<String> pkgs) {}

    /**
     * End every speculative read this source started in the background: what has not begun is
     * dropped, what is running is cancelled, and the call returns only once nothing is in flight.
     * Called when a solve finishes, so a caller that then deletes the cache directory is not racing a
     * prefetch still writing into it. Default: nothing to end.
     */
    default void quiesce() {}

    /**
     * How many version catalog and POM reads this source has completed so far, speculative ones
     * included. The solver's stall watch samples it: a solve parked on a read is alive while this
     * moves. Default: nothing counted.
     */
    default long readsCompleted() {
        return 0L;
    }

    /**
     * @return dependency edges of {@code (pkg, version)} as {@link Term}s. A positive Term gives a
     * downstream package and the version range the parent requires of it. A negative Term is a
     * constraint: the package is absent or within the term's complement — it bounds a package
     * some positive edge brings in and never adds one (Gradle's {@code dependencyConstraints}).
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
        public VersionUnavailableException(@Nullable String message) {
            super(message);
        }
    }
}
