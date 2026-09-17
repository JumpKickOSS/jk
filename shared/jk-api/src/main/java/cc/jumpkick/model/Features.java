// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code features} block of a {@code jk.toml}: named feature definitions plus the {@code
 * default} list applied when no {@code --features} flag is given.
 */
public record Features(Map<String, Feature> byName, List<String> defaults) {

    public Features {
        Objects.requireNonNull(byName, "byName");
        Objects.requireNonNull(defaults, "defaults");
        byName = Map.copyOf(byName);
        defaults = List.copyOf(defaults);
    }

    public static Features empty() {
        return new Features(Map.of(), List.of());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * True when some feature names the dependency handle {@code library}: the dependency is off until
     * that feature is active. An optional dependency no feature names is the module's own, like a
     * POM's {@code <optional>} dependency under Maven.
     */
    public boolean names(String library) {
        Objects.requireNonNull(library, "library");
        for (Feature feature : byName.values()) {
            if (feature.deps().contains(library)) return true;
        }
        return false;
    }

    /** The handles of the optional dependencies the default features activate, in activation order. */
    public List<String> defaultDepNames() {
        return requestedDepNames(activate(Set.of(), true));
    }

    /** Transitive activation set; {@code withDefaults} includes the default list. */
    public Set<String> activate(Set<String> requested, boolean withDefaults) {
        Objects.requireNonNull(requested, "requested");
        Set<String> seeds = new LinkedHashSet<>();
        if (withDefaults) seeds.addAll(defaults);
        seeds.addAll(requested);

        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!result.add(name)) continue;
            Feature feature = byName.get(name);
            if (feature == null) {
                throw new IllegalArgumentException("unknown feature: " + name);
            }
            queue.addAll(feature.features());
        }
        return result;
    }

    /** Short dependency names requested by {@code activated} features, in activation order. */
    public List<String> requestedDepNames(Set<String> activated) {
        List<String> names = new ArrayList<>();
        for (String name : activated) {
            Feature feature = byName.get(name);
            if (feature == null) {
                throw new IllegalArgumentException("unknown feature: " + name);
            }
            names.addAll(feature.deps());
        }
        return names;
    }
}
