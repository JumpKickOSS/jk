// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Coordinate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Resolution result: module → picked version (and direct deps). */
public record Resolution(Map<String, ResolvedModule> modules) {

    public Resolution {
        Objects.requireNonNull(modules, "modules");
        // Deterministic ordering for stable lockfile output downstream.
        modules = Map.copyOf(new TreeMap<>(modules));
    }

    public record ResolvedModule(String module, String version, List<String> deps) {
        public ResolvedModule {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(deps, "deps");
            deps = List.copyOf(deps);
        }

        /** Lockfile-style key: {@code group:artifact@version}. */
        public String coord() {
            return module + "@" + version;
        }

        /** This module's {@code group:artifact} plus its picked version as a {@link Coordinate}. */
        public Coordinate coordinate() {
            return Coordinate.ofModule(module, version);
        }
    }
}
