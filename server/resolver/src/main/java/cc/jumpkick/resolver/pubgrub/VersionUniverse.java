// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.version.Versions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Discrete versions a {@link PackageSource} advertises for one package (highest-first; soft-prefer
 * pin first). Continuous {@link VersionSet} terms are {@linkplain #project projected} onto this
 * list so intersects/subsets are bit ops.
 *
 * <p>Projections are memoized per constraint: a projection compares every advertised version
 * against the constraint's bounds, and the solver asks for the same handful of constraints on every
 * relation check of every propagation round — on a hundred-version universe that comparison work,
 * not the bit ops, is what a solve spends its time on. {@link AllowedSet} is immutable, so a cached
 * projection is shared freely.
 */
public final class VersionUniverse {

    private final String pkg;
    private final List<String> versions;
    /** First index of each version string (duplicates keep the earliest / preferred slot). */
    private final Map<String, Integer> indexOf;

    private final Map<VersionSet, AllowedSet> projections = new HashMap<>();
    private final AllowedSet all;
    private final AllowedSet none;
    private @Nullable Boolean softPreferFront;

    private VersionUniverse(String pkg, List<String> versions, Map<String, Integer> indexOf) {
        this.pkg = pkg;
        this.versions = versions;
        this.indexOf = indexOf;
        this.all = AllowedSet.allOf(this);
        this.none = AllowedSet.noneOf(this);
    }

    public static VersionUniverse of(String pkg, List<String> versions) {
        Objects.requireNonNull(pkg, "pkg");
        Objects.requireNonNull(versions, "versions");
        List<String> copy = List.copyOf(versions);
        Map<String, Integer> index = new HashMap<>(Math.max(16, copy.size() * 2));
        for (int i = 0; i < copy.size(); i++) {
            index.putIfAbsent(copy.get(i), i);
        }
        return new VersionUniverse(pkg, copy, Map.copyOf(index));
    }

    public String pkg() {
        return pkg;
    }

    public int size() {
        return versions.size();
    }

    public List<String> versions() {
        return versions;
    }

    public String version(int index) {
        return versions.get(index);
    }

    /** Index of {@code version} in this universe, or {@code -1} if absent. */
    public int indexOf(String version) {
        Integer i = indexOf.get(version);
        return i == null ? -1 : i;
    }

    /** Every advertised version is allowed. */
    public AllowedSet all() {
        return all;
    }

    /** No advertised version is allowed. */
    public AllowedSet none() {
        return none;
    }

    /**
     * True when index 0 is a soft-prefer pin rather than the natural highest version: some later
     * advertised version compares greater under Maven order. Decided once per universe.
     */
    public boolean softPreferFront() {
        Boolean known = softPreferFront;
        if (known == null) {
            known = computeSoftPreferFront();
            softPreferFront = known;
        }
        return known;
    }

    private boolean computeSoftPreferFront() {
        if (versions.size() <= 1) return false;
        String front = versions.getFirst();
        for (int i = 1; i < versions.size(); i++) {
            if (Versions.compare(versions.get(i), front) > 0) return true;
        }
        return false;
    }

    /**
     * Versions in this universe that satisfy {@code constraint}. Unknown version strings (outside
     * the advertised list) never appear — a pin to a missing version projects to empty.
     */
    public AllowedSet project(VersionSet constraint) {
        Objects.requireNonNull(constraint, "constraint");
        if (constraint.isEmpty()) return none;
        if (versions.isEmpty()) return none;
        if (constraint.isAll()) return all;
        AllowedSet cached = projections.get(constraint);
        if (cached != null) return cached;
        AllowedSet projected = AllowedSet.projecting(this, constraint);
        projections.put(constraint, projected);
        return projected;
    }
}
