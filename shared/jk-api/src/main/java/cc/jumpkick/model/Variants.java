// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code [variants]} block: product dimensions (axes) with named values, each an overlay
 * ({@code extra-src}, dependency additions, plugin config keys). Built-in {@link #BUILD_TYPE}
 * defaults to {@code debug}; a custom dimension without a default makes selection mandatory.
 * Overlays fold at parse time — custom dimensions first, then {@code build-type}.
 * Project identity, repos, profiles, features, and toolchain flags are not overlayable.
 */
public record Variants(List<Dimension> dimensions) {

    /** The built-in dimension name selected by {@code --release} / defaulted to {@code debug}. */
    public static final String BUILD_TYPE = "build-type";

    /** Built-in {@code build-type} values, valid even when undeclared. */
    public static final List<String> BUILT_IN_BUILD_TYPES = List.of("debug", "release");

    public static final Variants EMPTY = new Variants(List.of());

    /**
     * One value per dimension. Wire form: {@code ""} / {@code "release"} /
     * {@code "release|contentType=demo"} (bare token = build type; {@code dim=value} for customs).
     * Unknown dimensions are ignored per module.
     */
    public record Selection(String buildType, Map<String, String> values) {

        public static final Selection DEFAULTS = new Selection(null, Map.of());

        public Selection {
            values = values == null || values.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public static Selection parse(String raw) {
            if (raw == null || raw.isBlank()) return DEFAULTS;
            String buildType = null;
            Map<String, String> values = new LinkedHashMap<>();
            for (String part : raw.split("\\|")) {
                if (part.isBlank()) continue;
                int eq = part.indexOf('=');
                if (eq > 0) values.put(part.substring(0, eq), part.substring(eq + 1));
                else buildType = part;
            }
            return new Selection(buildType, values);
        }

        public String encode() {
            StringBuilder b = new StringBuilder(buildType == null ? "" : buildType);
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (b.length() > 0) b.append('|');
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            return b.toString();
        }
    }

    public Variants {
        Objects.requireNonNull(dimensions, "dimensions");
        dimensions = List.copyOf(dimensions);
    }

    public boolean isEmpty() {
        return dimensions.isEmpty();
    }

    public Optional<Dimension> dimension(String name) {
        return dimensions.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    /** Declared dimensions minus {@code build-type}, in declaration order. */
    public List<Dimension> custom() {
        return dimensions.stream().filter(d -> !BUILD_TYPE.equals(d.name())).toList();
    }

    /**
     * Folds every value's dependency overlays into one union graph for {@code jk lock}.
     * Same module with different selectors across values is a hard error (not highest-wins).
     */
    public static JkBuild unionDependencies(JkBuild build) {
        if (build.variants().isEmpty()) return build;
        // Same-module different-selector across the union is a hard error: highest-wins would
        // silently build the "losing" value against the other value's version.
        Map<String, String[]> seen = new HashMap<>(); // scope|module → {selector, origin}
        Map<Scope, LinkedHashSet<Dependency>> merged = new EnumMap<>(Scope.class);
        build.dependencies().byScope().forEach((scope, deps) -> {
            for (Dependency d : deps) note(seen, scope, d, "[" + scope.tomlSection() + "]");
            merged.computeIfAbsent(scope, s -> new LinkedHashSet<>()).addAll(deps);
        });
        for (Dimension dimension : build.variants().dimensions()) {
            for (Map.Entry<String, Value> e : dimension.values().entrySet()) {
                String origin = "[variants." + dimension.name() + "." + e.getKey() + "]";
                e.getValue().dependencies().forEach((scope, deps) -> {
                    for (Dependency d : deps) note(seen, scope, d, origin);
                    merged.computeIfAbsent(scope, s -> new LinkedHashSet<>()).addAll(deps);
                });
            }
        }
        Map<Scope, List<Dependency>> out = new EnumMap<>(Scope.class);
        merged.forEach((scope, deps) -> out.put(scope, List.copyOf(deps)));
        return build.withDependencies(new JkBuild.Dependencies(out));
    }

    private static void note(Map<String, String[]> seen, Scope scope, Dependency d, String origin) {
        String selector = d.version() == null ? "" : d.version().raw();
        String[] prior = seen.putIfAbsent(scope.name() + "|" + d.module(), new String[] {selector, origin});
        if (prior != null && !prior[0].equals(selector)) {
            throw new IllegalStateException(d.name() + " (" + d.module() + ") is declared " + prior[0] + " by "
                    + prior[1] + " but " + selector + " by " + origin
                    + " — one lockfile resolves the UNION of every variant value's dependencies, so align"
                    + " the version across declarations (docs/variants.md → Locking)");
        }
    }

    /** Per-value dependency overlay lines for conflict hints ({@code "contentType=demo → ads"}). */
    public static List<String> describeDependencyOverlays(JkBuild build) {
        List<String> out = new ArrayList<>();
        for (Dimension dimension : build.variants().dimensions()) {
            for (Map.Entry<String, Value> e : dimension.values().entrySet()) {
                List<String> names = new ArrayList<>();
                e.getValue().dependencies().values().forEach(deps -> deps.forEach(d -> names.add(d.name())));
                if (!names.isEmpty()) {
                    out.add(dimension.name() + "=" + e.getKey() + " → " + String.join(", ", names));
                }
            }
        }
        return out;
    }

    /** One axis: values (name → overlay) and optional default. No default → selection mandatory. */
    public record Dimension(String name, String defaultValue, Map<String, Value> values) {

        public Dimension {
            Objects.requireNonNull(name, "name");
            values = values == null || values.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(values));
            if (defaultValue != null && !values.containsKey(defaultValue) && !isBuiltIn(name, defaultValue)) {
                throw new IllegalArgumentException("[variants." + name + "] default = \"" + defaultValue
                        + "\" names no declared value (declared: " + values.keySet() + ")");
            }
        }

        private static boolean isBuiltIn(String dimension, String value) {
            return BUILD_TYPE.equals(dimension) && BUILT_IN_BUILD_TYPES.contains(value);
        }
    }

    /** One value's overlay: extra sources, per-scope deps, and per-plugin config-key maps. */
    public record Value(
            List<String> extraSrc,
            Map<Scope, List<Dependency>> dependencies,
            Map<String, Map<String, Object>> pluginOverlays) {

        public static final Value EMPTY = new Value(List.of(), Map.of(), Map.of());

        public Value {
            extraSrc = extraSrc == null ? List.of() : List.copyOf(extraSrc);
            dependencies = dependencies == null || dependencies.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(dependencies));
            pluginOverlays = pluginOverlays == null || pluginOverlays.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(pluginOverlays));
        }
    }
}
