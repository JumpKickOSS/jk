// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [workspace]} block of a root {@code jk.toml}: the literal module paths plus the
 * optional {@code [workspace.dependencies]} table of shared external dep declarations inherited by
 * modules via {@code <name>.workspace = true}.
 */
public record Workspace(List<String> modules, Map<String, WorkspaceDependency> dependencies) {

    public Workspace {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(dependencies, "dependencies");
        modules = List.copyOf(modules);
        dependencies = Map.copyOf(new LinkedHashMap<>(dependencies));
    }

    /** Modules-only constructor; no shared workspace dependencies. */
    public Workspace(List<String> modules) {
        this(modules, Map.of());
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /**
     * Shared external dep in {@code [workspace.dependencies]} (version or git; not a sibling).
     * {@code exclusions} is the entry's own {@code exclude} list — {@code group:artifact} or
     * {@code group:*} each — which every member edge to the entry carries beside its own.
     */
    public record WorkspaceDependency(
            String group,
            String artifact,
            @Nullable VersionSelector version,
            @Nullable GitSource gitSource,
            List<String> exclusions) {

        public WorkspaceDependency(
                String group, String artifact, @Nullable VersionSelector version, @Nullable GitSource gitSource) {
            this(group, artifact, version, gitSource, List.of());
        }

        public WorkspaceDependency {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            exclusions = List.copyOf(exclusions);
            if (group.isBlank()) {
                throw new IllegalArgumentException("workspace dependency group must not be blank");
            }
            if (artifact.isBlank()) {
                throw new IllegalArgumentException("workspace dependency artifact must not be blank");
            }
            int sourceCount = (version != null ? 1 : 0) + (gitSource != null ? 1 : 0);
            if (sourceCount != 1) {
                throw new IllegalArgumentException("workspace dependency must set exactly one of `version` or `git`");
            }
        }

        public String module() {
            return group + ":" + artifact;
        }
    }
}
