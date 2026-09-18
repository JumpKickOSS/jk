// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which features a command activates — Cargo's {@code --features} and {@code --no-default-features}
 * — and so which of a manifest's optional dependencies are on. {@link #apply} reads a manifest as
 * the lock does: an optional dependency a feature names is present only while that feature is
 * active, one no feature names is the module's own root, and a requested name the manifest's
 * {@code [features]} does not declare is not its to activate and is left out for it.
 *
 * @param requested feature names beyond the defaults
 * @param defaults whether the manifest's {@code features.default} list is active
 */
public record FeatureSelection(List<String> requested, boolean defaults) {

    /** No names requested, the defaults on: what a bare {@code jk lock}, {@code jk tree} or {@code jk why} reads. */
    public static final FeatureSelection DEFAULTS = new FeatureSelection(List.of(), true);

    public FeatureSelection {
        requested = List.copyOf(Objects.requireNonNull(requested, "requested"));
    }

    /** {@code build} with the optional dependencies its inactive features name removed. */
    public JkBuild apply(JkBuild build) {
        Features features = build.features();
        if (features.isEmpty()) return build;
        Set<String> mine = new LinkedHashSet<>();
        for (String name : requested) {
            if (features.byName().containsKey(name)) mine.add(name);
        }
        Set<String> active = new HashSet<>(features.requestedDepNames(features.activate(mine, defaults)));
        Map<Scope, List<Dependency>> out = new EnumMap<>(Scope.class);
        boolean changed = false;
        for (Map.Entry<Scope, List<Dependency>> e :
                build.dependencies().byScope().entrySet()) {
            List<Dependency> kept = e.getValue().stream()
                    .filter(d -> !d.optional() || !features.names(d.library()) || active.contains(d.library()))
                    .toList();
            changed |= kept.size() != e.getValue().size();
            out.put(e.getKey(), kept);
        }
        return changed ? build.withDependencies(new JkBuild.Dependencies(out)) : build;
    }
}
