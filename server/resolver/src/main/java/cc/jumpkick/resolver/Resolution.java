// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.PackageId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Resolution result: package key → picked version (and direct deps). */
public record Resolution(Map<String, ResolvedModule> modules) {

    public Resolution {
        Objects.requireNonNull(modules, "modules");
        // Deterministic ordering for stable lockfile output downstream.
        modules = Map.copyOf(new TreeMap<>(modules));
    }

    /**
     * @param deps direct edges as {@code packageKey@pickedVersion}
     * @param declared the version selector each edge's POM declared, keyed by the edge ref; an edge
     *     absent here declared nothing the resolver saw
     * @param excluded the edges an exclusion pruned from this module's expansion, one
     *     {@code group:artifact <- origin} line each; what the lock row's {@code excluded-by} carries
     */
    public record ResolvedModule(
            String module, String version, List<String> deps, Map<String, String> declared, List<String> excluded) {
        public ResolvedModule {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(deps, "deps");
            Objects.requireNonNull(declared, "declared");
            Objects.requireNonNull(excluded, "excluded");
            deps = List.copyOf(deps);
            declared = Map.copyOf(declared);
            excluded = List.copyOf(excluded);
        }

        /** Edges with their declared selectors and nothing pruned. */
        public ResolvedModule(String module, String version, List<String> deps, Map<String, String> declared) {
            this(module, version, deps, declared, List.of());
        }

        /** Edges without their declared selectors. */
        public ResolvedModule(String module, String version, List<String> deps) {
            this(module, version, deps, Map.of(), List.of());
        }

        /** Lockfile-style key: {@code packageId@version}. */
        public String coord() {
            return module + "@" + version;
        }

        /** This package's identity plus its picked version as a {@link Coordinate}. */
        public Coordinate coordinate() {
            return PackageId.parse(module).withVersion(version);
        }
    }
}
