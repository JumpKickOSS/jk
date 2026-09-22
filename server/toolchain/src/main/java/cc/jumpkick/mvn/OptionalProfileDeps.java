// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The optional dependencies inactive Maven profiles contribute, and the feature lists that gate
 * them. A handle already taken in a scope gets a numeric suffix, so the whole point of this class
 * is keeping the two in step: a feature has to name the handle that was actually written, or the
 * row ships on every classpath and activating the feature fails on a handle nothing declares.
 */
final class OptionalProfileDeps {

    private OptionalProfileDeps() {}

    /** One feature's mention of one handle, before the collision suffix was applied. */
    record FeatureHandle(String feature, String library) {}

    /**
     * Add {@code optional} to {@code byScope}, uniquifying handles against what the POM already
     * declares (the same rule as the importer's own handles), and report every rename.
     *
     * <p>A profile dependency with no version of its own that names a coordinate the POM already
     * declares is Maven's merge of the two — the profile's row over the declared one, the version
     * kept — so it takes the declared version rather than {@code unresolved}.
     *
     * @param owners the feature id behind each entry of {@code optional}, index for index
     * @return the new handle for each {@code (feature, original handle)} that collided
     */
    static Map<FeatureHandle, String> add(
            Map<Scope, List<Dependency>> byScope,
            Map<Scope, List<Dependency>> optional,
            Map<Scope, List<String>> owners) {
        Map<FeatureHandle, String> renamed = new LinkedHashMap<>();
        List<Dependency> declared = new ArrayList<>();
        byScope.values().forEach(declared::addAll);
        for (Map.Entry<Scope, List<Dependency>> e : optional.entrySet()) {
            List<Dependency> deps = byScope.computeIfAbsent(e.getKey(), s -> new ArrayList<>());
            List<String> owner = owners.getOrDefault(e.getKey(), List.of());
            Set<String> seen = new HashSet<>();
            for (Dependency d : deps) seen.add(d.library());
            List<Dependency> incoming = e.getValue();
            for (int i = 0; i < incoming.size(); i++) {
                Dependency d = incoming.get(i);
                String handle = d.library();
                for (int n = 2; !seen.add(handle); n++) handle = d.library() + "-" + n;
                if (!handle.equals(d.library()) && i < owner.size()) {
                    // Keyed by the feature that contributed this row, not by the handle alone:
                    // two profiles can each bring an `org.x:widget`, and they rename apart.
                    renamed.put(new FeatureHandle(owner.get(i), d.library()), handle);
                }
                deps.add(declaredVersion(d, declared).withLibrary(handle).withOptional(true));
            }
        }
        return renamed;
    }

    /** A renamed optional handle is the one its feature lists, so the feature still gates that row. */
    static Features renameHandles(Features features, Map<FeatureHandle, String> renamed) {
        if (renamed.isEmpty() || features == null) return features;
        Map<String, Feature> byName = new LinkedHashMap<>();
        for (var entry : features.byName().entrySet()) {
            Feature feature = entry.getValue();
            List<String> deps = new ArrayList<>();
            for (String dep : feature.deps()) {
                deps.add(renamed.getOrDefault(new FeatureHandle(entry.getKey(), dep), dep));
            }
            byName.put(entry.getKey(), new Feature(feature.name(), deps, feature.features()));
        }
        return new Features(byName, features.defaults());
    }

    /** {@code d} at the version of the declared dependency naming its coordinate, when {@code d} has none. */
    private static Dependency declaredVersion(Dependency d, List<Dependency> declared) {
        if (!DependencyMapping.UNRESOLVED.equals(d.version().raw())) return d;
        for (Dependency existing : declared) {
            if (existing.group().equals(d.group())
                    && existing.module().equals(d.module())
                    && existing.kind() == d.kind()
                    && Objects.equals(existing.classifier(), d.classifier())
                    && !DependencyMapping.UNRESOLVED.equals(existing.version().raw())) {
                return d.withVersion(existing.version());
            }
        }
        return d;
    }
}
