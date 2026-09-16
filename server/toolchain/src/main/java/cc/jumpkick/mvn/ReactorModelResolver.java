// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.WorkspaceModelResolver;
import org.jspecify.annotations.Nullable;

/**
 * One Maven reactor for Maven's model builder: the root pom.xml and every module pom.xml, keyed
 * by coordinates. Maven asks here before {@code <relativePath>} or any repository, for a parent
 * (the raw model) and for an {@code import}-scope BOM (the effective model), so a sibling is never
 * fetched from the network, whether it is a parent, a BOM, or both.
 *
 * <p>A lookup matches the coordinates as written or with the version's CI-friendly placeholders
 * ({@code ${revision}} and friends) filled from the POM chain's properties, because Maven asks for
 * a parent with the raw version and for a BOM with the interpolated one. Effective models are built
 * once per pom.xml and shared with the importer.
 */
final class ReactorModelResolver implements WorkspaceModelResolver {

    /** One pom.xml of the reactor: its bytes, its own declarations and where it lives. */
    private record Entry(Path pomFile, byte[] xml, Model raw) {}

    private final ModelResolver repositories;
    private final Map<String, Entry> byRawGav = new LinkedHashMap<>();
    private final Map<Path, Entry> byFile = new HashMap<>();
    private final Map<Path, EffectiveModel> effective = new HashMap<>();
    private final Set<Path> building = new HashSet<>();

    /** {@code repositories} answers what the reactor does not: published parents and BOMs. */
    ReactorModelResolver(ModelResolver repositories) {
        this.repositories = repositories;
    }

    /** Register one pom.xml of the reactor. */
    void add(Path pomFile, byte[] xml, Model raw) {
        Entry entry = new Entry(pomFile.toAbsolutePath(), xml, raw.clone());
        byRawGav.put(rawGav(raw), entry);
        byFile.put(entry.pomFile(), entry);
    }

    boolean contains(String gav) {
        return find(gav) != null;
    }

    /**
     * The effective model of a registered pom.xml, built once. Its parents and BOM imports resolve
     * through this reactor first, then {@code repositories}.
     */
    EffectiveModel effective(Path pomFile) {
        Entry entry =
                Objects.requireNonNull(byFile.get(pomFile.toAbsolutePath()), () -> pomFile + " is not in the reactor");
        EffectiveModel built = build(entry);
        if (built == null) {
            throw new IllegalStateException(pomFile + " is being built already: the reactor's imports form a cycle");
        }
        return built;
    }

    /** {@code null} while the entry's own build is in progress higher up the stack (a cycle). */
    private @Nullable EffectiveModel build(Entry entry) {
        EffectiveModel hit = effective.get(entry.pomFile());
        if (hit != null) return hit;
        if (!building.add(entry.pomFile())) return null;
        try {
            EffectiveModel built = EffectiveModel.build(entry.xml(), entry.pomFile(), repositories.newCopy(), this);
            effective.put(entry.pomFile(), built);
            return built;
        } finally {
            building.remove(entry.pomFile());
        }
    }

    @Override
    public @Nullable Model resolveRawModel(String groupId, String artifactId, String versionConstraint) {
        Entry entry = find(groupId + ":" + artifactId + ":" + versionConstraint);
        if (entry == null) return null;
        Model model = entry.raw().clone();
        model.setPomFile(entry.pomFile().toFile());
        return model;
    }

    /** A sibling BOM: Maven takes its {@code dependencyManagement} from here instead of a repository. */
    @Override
    public @Nullable Model resolveEffectiveModel(String groupId, String artifactId, String versionConstraint) {
        Entry entry = find(groupId + ":" + artifactId + ":" + versionConstraint);
        if (entry == null) return null;
        EffectiveModel built = build(entry);
        return built == null || built.failure() != null ? null : built.model().clone();
    }

    /** The entry whose coordinates match {@code gav} as written, else with its version interpolated. */
    private @Nullable Entry find(String gav) {
        Entry hit = byRawGav.get(gav);
        if (hit != null) return hit;
        String[] parts = gav.split(":", 3);
        if (parts.length < 3) return null;
        for (Entry entry : byRawGav.values()) {
            Model raw = entry.raw();
            if (!parts[0].equals(groupOf(raw)) || !parts[1].equals(raw.getArtifactId())) continue;
            if (parts[2].equals(interpolatedVersion(raw))) return entry;
        }
        return null;
    }

    /** The version as Maven would spell it: placeholders filled from this POM and its reactor parents. */
    String interpolatedVersion(Model raw) {
        return CiFriendlyVersions.interpolate(versionOf(raw), name -> property(raw, name, 0));
    }

    private @Nullable String property(Model raw, String name, int depth) {
        String own = raw.getProperties().getProperty(name);
        if (own != null) return own;
        Parent parent = raw.getParent();
        if (parent == null || depth > 32) return null;
        Entry above = byRawGav.get(parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion());
        return above == null ? null : property(above.raw(), name, depth + 1);
    }

    /** {@code g:a:v} with {@code <parent>} filling a missing groupId or version, as Maven does. */
    static String rawGav(Model raw) {
        return groupOf(raw) + ":" + raw.getArtifactId() + ":" + versionOf(raw);
    }

    private static @Nullable String groupOf(Model raw) {
        Parent parent = raw.getParent();
        return raw.getGroupId() != null ? raw.getGroupId() : parent != null ? parent.getGroupId() : null;
    }

    private static String versionOf(Model raw) {
        Parent parent = raw.getParent();
        String version = raw.getVersion() != null ? raw.getVersion() : parent != null ? parent.getVersion() : null;
        return version == null ? "" : version;
    }
}
