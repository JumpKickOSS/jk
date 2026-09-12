// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.repo.Pom.Relocation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A POM with its parent chain merged, BOM imports flattened, and {@code <dependencyManagement>}
 * applied to fill versions of bare deps. The thing the resolver actually wants to look at.
 *
 * <p>The {@link Pom.Dep} entries here may still carry classifier/type/scope variations; the dedup
 * key is {@code groupId:artifactId} (Maven's rule — a module appears at most once per scope).
 *
 * <p>{@code importedManagedKeys} names the {@link #managedDependencies} entries (by {@link
 * EffectivePomBuilder#depKey Maven dependency key}) that an {@code import}-scoped BOM supplied
 * rather than this POM or a parent declaring them. A POM importing this one treats those as
 * imports again, so a declaration anywhere in its own chain still beats them.
 */
public record EffectivePom(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Map<String, String> properties,
        List<Pom.Dep> dependencies,
        List<Pom.Dep> managedDependencies,
        Set<String> importedManagedKeys,
        @Nullable Relocation relocation) {

    /** A POM whose managed entries are all its own and that declares no relocation. */
    public EffectivePom(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            Map<String, String> properties,
            List<Pom.Dep> dependencies,
            List<Pom.Dep> managedDependencies) {
        this(groupId, artifactId, version, packaging, properties, dependencies, managedDependencies, Set.of(), null);
    }

    public EffectivePom {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(packaging, "packaging");
        properties = Map.copyOf(properties);
        dependencies = List.copyOf(dependencies);
        managedDependencies = List.copyOf(managedDependencies);
        importedManagedKeys = Set.copyOf(importedManagedKeys);
    }
}
