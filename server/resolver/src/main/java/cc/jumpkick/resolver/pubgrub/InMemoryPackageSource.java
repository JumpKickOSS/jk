// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.version.Versions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * In-memory {@link PackageSource} for solver tests. Pure data; no I/O.
 *
 * <pre>{@code
 * PackageSource src = InMemoryPackageSource.builder()
 *     .version("root", "1.0", deps -> deps
 *         .require("widget", VersionSet.exact("2.0")))
 *     .version("widget", "2.0")
 *     .version("widget", "1.0")
 *     .build();
 * }</pre>
 */
public final class InMemoryPackageSource implements PackageSource {

    private final Map<String, List<String>> versionsByPackage;
    private final Map<String, List<Term>> depsByCoord;
    private final Map<String, Set<String>> declaredByPackage;
    private final Map<String, String> floorByPackage;

    private InMemoryPackageSource(
            Map<String, List<String>> versionsByPackage,
            Map<String, List<Term>> depsByCoord,
            Map<String, Set<String>> declaredByPackage,
            Map<String, String> floorByPackage) {
        this.versionsByPackage = Map.copyOf(versionsByPackage);
        this.depsByCoord = Map.copyOf(depsByCoord);
        this.declaredByPackage = Map.copyOf(declaredByPackage);
        this.floorByPackage = Map.copyOf(floorByPackage);
    }

    @Override
    public List<String> versions(String pkg) {
        return versionsByPackage.getOrDefault(pkg, List.of());
    }

    @Override
    public List<Term> dependencies(String pkg, String version) throws VersionUnavailableException {
        // Mirror Maven: a version absent from the advertised list is unavailable (not an empty
        // dep graph). Lets exact-seeded universes still fail closed without inventing packages.
        List<String> advertised = versionsByPackage.getOrDefault(pkg, List.of());
        if (!advertised.contains(version)) {
            throw new VersionUnavailableException(pkg + "@" + version + " not advertised");
        }
        return depsByCoord.getOrDefault(coord(pkg, version), List.of());
    }

    /** Every plain version any edge in the graph names for {@code pkg}, known up front. */
    @Override
    public Set<String> declaredVersions(String pkg) {
        return declaredByPackage.getOrDefault(pkg, Set.of());
    }

    @Override
    public Optional<String> floorVersion(String pkg) {
        return Optional.ofNullable(floorByPackage.get(pkg));
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String coord(String pkg, String version) {
        return pkg + "@" + version;
    }

    public static final class Builder {
        private final Map<String, List<String>> versionsByPackage = new HashMap<>();
        private final Map<String, List<Term>> depsByCoord = new HashMap<>();
        private final Map<String, Set<String>> declaredByPackage = new HashMap<>();
        private final Map<String, String> floorByPackage = new HashMap<>();

        public Builder version(String pkg, String version) {
            return version(pkg, version, deps -> {});
        }

        public Builder version(String pkg, String version, Consumer<Deps> deps) {
            Objects.requireNonNull(pkg, "pkg");
            Objects.requireNonNull(version, "version");
            versionsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(version);
            Deps captured = new Deps(declaredByPackage);
            deps.accept(captured);
            depsByCoord.put(coord(pkg, version), List.copyOf(captured.entries));
            return this;
        }

        /** The version a prior lock held for {@code pkg}; see {@link PackageSource#floorVersion}. */
        public Builder floor(String pkg, String version) {
            floorByPackage.put(Objects.requireNonNull(pkg, "pkg"), Objects.requireNonNull(version, "version"));
            return this;
        }

        public InMemoryPackageSource build() {
            // Sort each version list highest-first to match the PackageSource contract.
            Map<String, List<String>> sorted = new HashMap<>();
            versionsByPackage.forEach((pkg, list) -> {
                List<String> copy = new ArrayList<>(list);
                copy.sort((a, b) -> Versions.compare(b, a)); // descending
                sorted.put(pkg, List.copyOf(copy));
            });
            Map<String, Set<String>> declared = new HashMap<>();
            declaredByPackage.forEach((pkg, set) -> declared.put(pkg, Set.copyOf(set)));
            return new InMemoryPackageSource(sorted, depsByCoord, declared, floorByPackage);
        }
    }

    public static final class Deps {
        private final List<Term> entries = new ArrayList<>();
        private final Map<String, Set<String>> declaredByPackage;

        private Deps(Map<String, Set<String>> declaredByPackage) {
            this.declaredByPackage = declaredByPackage;
        }

        public Deps require(String pkg, VersionSet versions) {
            entries.add(Term.positive(pkg, versions));
            return this;
        }

        /** A POM-style plain version: a floor for the solver, and a declared version it steers to. */
        public Deps requirePlain(String pkg, String version) {
            declaredByPackage.computeIfAbsent(pkg, k -> new LinkedHashSet<>()).add(version);
            return require(pkg, VersionSet.atLeast(version, true));
        }

        /**
         * A Gradle-style constraint: when some edge brings {@code pkg} in it sits within {@code
         * versions}; the constraint alone never adds it. The negative term the solver reads as
         * "absent or within".
         */
        public Deps constrain(String pkg, VersionSet versions) {
            entries.add(Term.negative(pkg, versions.complement()));
            return this;
        }

        /** A constraint with a plain version: a floor, and a declared version the solver steers to. */
        public Deps constrainPlain(String pkg, String version) {
            declaredByPackage.computeIfAbsent(pkg, k -> new LinkedHashSet<>()).add(version);
            return constrain(pkg, VersionSet.atLeast(version, true));
        }
    }
}
