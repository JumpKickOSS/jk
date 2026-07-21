// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Discrete versions a {@link PackageSource} advertises for one package (highest-first; soft-prefer
 * pin first). Continuous {@link VersionSet} terms are {@linkplain #project projected} onto this
 * list so intersects/subsets are bit ops.
 */
public final class VersionUniverse {

    private final String pkg;
    private final List<String> versions;
    /** First index of each version string (duplicates keep the earliest / preferred slot). */
    private final Map<String, Integer> indexOf;

    private VersionUniverse(String pkg, List<String> versions, Map<String, Integer> indexOf) {
        this.pkg = pkg;
        this.versions = versions;
        this.indexOf = indexOf;
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
        return AllowedSet.allOf(this);
    }

    /** No advertised version is allowed. */
    public AllowedSet none() {
        return AllowedSet.noneOf(this);
    }

    /**
     * Versions in this universe that satisfy {@code constraint}. Unknown version strings (outside
     * the advertised list) never appear — a pin to a missing version projects to empty.
     */
    public AllowedSet project(VersionSet constraint) {
        Objects.requireNonNull(constraint, "constraint");
        if (constraint.isEmpty()) return none();
        if (versions.isEmpty()) return none();
        if (constraint.isAll()) return all();
        return AllowedSet.projecting(this, constraint);
    }
}
