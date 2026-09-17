// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code jk.toml} <em>declares</em>, as the tree needs it: the direct modules of a scope, the
 * version literal a selector pins, and which modules are pin sources (BOMs and managed entries).
 *
 * <p>Its own owner because both renders ask the same three questions — the nested walk in
 * {@link DependencyTree} and the closure accumulation in {@link DependencyFlatten} — and a platform
 * BOM answered "declared" by one and "missing" by the other is exactly the bug the {@code platform}
 * marker exists to prevent.
 */
final class DeclaredDeps {

    private DeclaredDeps() {}

    /** A project's direct dep modules in one scope, distinct + sorted. */
    static List<String> modulesOf(JkBuild project, Scope scope) {
        return project.dependencies().of(scope).stream()
                .map(Dependency::module)
                .distinct()
                .sorted()
                .toList();
    }

    /** As {@link #modulesOf} without the optional dependencies: what a consumer of {@code sibling} inherits. */
    static List<String> inheritedModulesOf(JkBuild sibling, Scope scope) {
        return sibling.dependencies().of(scope).stream()
                .filter(d -> !d.optional())
                .map(Dependency::module)
                .distinct()
                .sorted()
                .toList();
    }

    /** Concrete version literals from declared deps in {@code scopes} (Exact / caret-tilde anchors). */
    static Map<String, String> versions(JkBuild project, List<Scope> scopes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Scope s : scopes) {
            for (Dependency d : project.dependencies().of(s)) {
                String v = versionLiteral(d.version());
                if (v != null) out.putIfAbsent(d.module(), v);
            }
        }
        return out;
    }

    /** The tag a {@code [platform-dependencies]} BOM carries: it pins versions and is no lock jar row. */
    static final String PLATFORM_TAG = " (platform)";

    /** The tag a {@code [managed-dependencies]} entry carries under its own section. */
    static final String MANAGED_TAG = " (managed)";

    /**
     * The pin sources a manifest declares, by module, with the tag each one prints: every BOM of
     * {@code [platform-dependencies]} is {@value #PLATFORM_TAG} wherever it is listed, and an entry
     * of {@code [managed-dependencies]} is {@value #MANAGED_TAG} when the managed section is among
     * {@code scopes}. Neither is a lock jar row in its own right.
     */
    static Map<String, String> pinTags(JkBuild project, List<Scope> scopes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Dependency d : project.dependencies().of(Scope.PLATFORM)) out.putIfAbsent(d.module(), PLATFORM_TAG);
        if (scopes.contains(Scope.MANAGED)) {
            for (Dependency d : project.dependencies().of(Scope.MANAGED)) out.putIfAbsent(d.module(), MANAGED_TAG);
        }
        return out;
    }

    /**
     * Concrete version from a selector when one is known (platform BOMs must pin). {@code null} for
     * {@code latest} / empty.
     */
    private static @Nullable String versionLiteral(VersionSelector sel) {
        if (sel == null) return null;
        return switch (sel) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            default -> null;
        };
    }
}
