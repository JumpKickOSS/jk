// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges workspace root + modules into a synthetic {@link JkBuild} for single-pass locking.
 * Resolves {@code workspace:*} placeholders (sibling coord, then {@code [workspace.dependencies]}).
 */
public final class WorkspaceMerge {

    private WorkspaceMerge() {}

    /**
     * Resolve workspace placeholders on one module and drop sibling coords (classpath injects them).
     * Returns external Maven deps only for lock orchestration.
     */
    public static JkBuild applyToModule(JkBuild root, JkBuild module, Collection<JkBuild> allModules) {
        if (allModules.isEmpty()) return Variants.unionDependencies(module);

        // Lock scopes see the UNION of every variant value's dependency overlays — one lockfile
        // covers every variant (Variants.unionDependencies; the build folds only the selected
        // value's deps). Siblings union too: their variant-only externals fold transitively.
        module = Variants.unionDependencies(module);
        List<JkBuild> unionModules = new ArrayList<>(allModules.size());
        for (JkBuild m : allModules) unionModules.add(Variants.unionDependencies(m));
        allModules = unionModules;

        Map<String, JkBuild> siblingByArtifact = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        for (JkBuild m : allModules) {
            siblingByArtifact.put(m.project().name(), m);
            internal.add(m.project().group() + ":" + m.project().name());
        }
        // The workspace root is itself a unit members may depend on (Cargo/uv style:
        // a member can `<root> = { workspace = true }`), so it joins the sibling set.
        siblingByArtifact.put(root.project().name(), root);
        internal.add(root.project().group() + ":" + root.project().name());
        Map<String, Workspace.WorkspaceDependency> wsDeps =
                root.workspace() != null ? root.workspace().dependencies() : Map.of();

        // First pass: resolve this module's own deps, strip sibling refs, and
        // track which siblings are direct dependencies (for export propagation).
        Set<String> dependedSiblingNames = new LinkedHashSet<>();
        Map<Scope, List<Dependency>> resolvedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            List<Dependency> resolved = new ArrayList<>();
            for (Dependency d : module.dependencies().of(scope)) {
                Dependency r = resolve(d, siblingByArtifact, wsDeps);
                if (internal.contains(r.module())) {
                    dependedSiblingNames.add(r.name());
                    continue;
                }
                resolved.add(r);
            }
            if (!resolved.isEmpty()) resolvedByScope.put(scope, resolved);
        }

        // Transitive MAIN+EXPORT externals from reachable siblings into this module's main scope.
        Set<String> visited = new LinkedHashSet<>(dependedSiblingNames);
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(dependedSiblingNames);
        while (!queue.isEmpty()) {
            JkBuild sibling = siblingByArtifact.get(queue.poll());
            if (sibling == null) continue;
            for (Scope scope : List.of(Scope.MAIN, Scope.EXPORT)) {
                for (Dependency d : sibling.dependencies().of(scope)) {
                    Dependency r = resolve(d, siblingByArtifact, wsDeps);
                    if (internal.contains(r.module())) {
                        if (visited.add(r.name())) queue.add(r.name());
                        continue;
                    }
                    List<Dependency> mainList = resolvedByScope.computeIfAbsent(Scope.MAIN, k -> new ArrayList<>());
                    if (mainList.stream().noneMatch(e -> e.module().equals(r.module()))) {
                        mainList.add(r);
                    }
                }
            }
        }

        JkBuild.Builder out = JkBuild.builder(module.project())
                .dependencies(new JkBuild.Dependencies(resolvedByScope))
                .repositories(module.repositories())
                .profiles(module.profiles())
                .features(module.features())
                .workspace(module.workspace())
                .manifest(module.manifest())
                .plugins(module.plugins())
                .application(module.application().orElse(null))
                .nativeConfig(module.nativeConfig().orElse(null))
                .build(module.build())
                .format(module.format())
                .variants(module.variants());
        for (PluginConfig config : module.pluginConfigs().values()) {
            out.pluginConfig(config);
        }
        return out.build();
    }

    /**
     * {@code module} with every {@code workspace:<name>} placeholder rewritten to the sibling's real
     * {@code group:artifact} at an exact version — sibling deps <em>kept</em>, not stripped.
     *
     * <p>This is the counterpart to {@link #applyToModule}, which exists for lock orchestration and
     * therefore drops sibling edges (they are not resolvable Maven coordinates) after folding their
     * externals into MAIN. That is right for locking and wrong for anything describing the module to
     * the outside world: a published POM must still declare the sibling, just by its real
     * coordinate. Emitting the raw placeholder produced {@code <groupId>workspace</groupId>} /
     * {@code <version>LATEST</version>} POMs; dropping the edge instead would silently
     * lose a real dependency, which is worse.
     *
     * <p>Unresolvable placeholders are left untouched rather than throwing — the caller is usually
     * rendering, and a partial answer beats an exception at that point.
     */
    public static JkBuild resolveSiblingCoordinates(JkBuild root, JkBuild module, Collection<JkBuild> allModules) {
        Map<String, JkBuild> siblingByArtifact = new LinkedHashMap<>();
        for (JkBuild m : allModules) siblingByArtifact.put(m.project().name(), m);
        siblingByArtifact.put(root.project().name(), root);
        Map<String, Workspace.WorkspaceDependency> wsDeps =
                root.workspace() != null ? root.workspace().dependencies() : Map.of();

        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        boolean rewroteAny = false;
        for (Scope scope : Scope.values()) {
            List<Dependency> deps = module.dependencies().of(scope);
            if (deps.isEmpty()) continue;
            List<Dependency> out = new ArrayList<>(deps.size());
            for (Dependency d : deps) {
                Dependency resolved = d;
                if (d.isWorkspace()) {
                    try {
                        resolved = resolve(d, siblingByArtifact, wsDeps);
                        rewroteAny = true;
                    } catch (IllegalStateException e) {
                        resolved = d; // leave the placeholder; the build surfaces the real error
                    }
                }
                out.add(resolved);
            }
            byScope.put(scope, out);
        }
        if (!rewroteAny) return module;

        JkBuild.Builder out = JkBuild.builder(module.project())
                .dependencies(new JkBuild.Dependencies(byScope))
                .repositories(module.repositories())
                .profiles(module.profiles())
                .features(module.features())
                .workspace(module.workspace())
                .manifest(module.manifest())
                .plugins(module.plugins())
                .application(module.application().orElse(null))
                .nativeConfig(module.nativeConfig().orElse(null))
                .build(module.build())
                .format(module.format())
                .variants(module.variants());
        for (PluginConfig config : module.pluginConfigs().values()) {
            out.pluginConfig(config);
        }
        return out.build();
    }

    public static JkBuild merge(JkBuild root, Collection<JkBuild> modules) {
        if (modules.isEmpty()) return Variants.unionDependencies(root);

        // Union variant dep overlays into every manifest before folding (see applyToModule).
        root = Variants.unionDependencies(root);
        List<JkBuild> unionModules = new ArrayList<>(modules.size());
        for (JkBuild m : modules) unionModules.add(Variants.unionDependencies(m));
        modules = unionModules;

        // Build the sibling lookup: artifact → JkBuild (full manifest).
        Map<String, JkBuild> siblingByArtifact = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        for (JkBuild module : modules) {
            String coord = module.project().group() + ":" + module.project().name();
            siblingByArtifact.put(module.project().name(), module);
            internal.add(coord);
        }
        // The root is itself a workspace unit members may depend on.
        siblingByArtifact.put(root.project().name(), root);
        internal.add(root.project().group() + ":" + root.project().name());

        Map<String, Workspace.WorkspaceDependency> wsDeps =
                root.workspace() != null ? root.workspace().dependencies() : Map.of();

        Map<Scope, List<Dependency>> mergedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            Map<String, Dependency> dedup = new LinkedHashMap<>();
            for (Dependency d : root.dependencies().of(scope)) {
                Dependency resolved = resolve(d, siblingByArtifact, wsDeps);
                if (internal.contains(resolved.module())) continue;
                dedup.putIfAbsent(resolved.module(), resolved);
            }
            for (JkBuild module : modules) {
                for (Dependency d : module.dependencies().of(scope)) {
                    Dependency resolved = resolve(d, siblingByArtifact, wsDeps);
                    if (internal.contains(resolved.module())) continue;
                    dedup.putIfAbsent(resolved.module(), resolved);
                }
            }
            if (!dedup.isEmpty()) {
                mergedByScope.put(scope, new ArrayList<>(dedup.values()));
            }
        }
        return JkBuild.builder(root.project())
                .dependencies(new JkBuild.Dependencies(mergedByScope))
                .repositories(root.repositories())
                .profiles(root.profiles())
                .features(root.features())
                .workspace(root.workspace())
                .manifest(root.manifest())
                .plugins(root.plugins())
                .application(root.application().orElse(null))
                .nativeConfig(root.nativeConfig().orElse(null))
                .build();
    }

    /**
     * Rewrites a placeholder {@code workspace:<name>} dep into the real coord, using the sibling list
     * first and then the workspace's shared-dep table. Non-placeholder deps pass through unchanged.
     */
    private static Dependency resolve(
            Dependency d, Map<String, JkBuild> siblingByArtifact, Map<String, Workspace.WorkspaceDependency> wsDeps) {
        if (!d.isWorkspace()) return d;
        String name = d.library();
        // Sibling lookup first. Modules typically name siblings as
        // jk-core, jk-cli, etc. — the dep handle is expected to match the
        // sibling's name directly.
        JkBuild sibling = siblingByArtifact.get(name);
        if (sibling != null) {
            JkBuild.Project p = sibling.project();
            String module = p.group() + ":" + p.name();
            // Preserve kind so a tests-kind edge stays distinguishable until classpath
            // resolution (WorkspaceClasspath keys off kind). For lock, siblings are dropped.
            return Dependency.of(name, module, VersionSelector.parse("=" + p.version()))
                    .withKind(d.kind());
        }
        Workspace.WorkspaceDependency ws = wsDeps.get(name);
        if (ws != null) {
            if (ws.gitSource() != null) {
                return Dependency.git(name, ws.module(), ws.gitSource()).withKind(d.kind());
            }
            return Dependency.of(name, ws.module(), ws.version()).withKind(d.kind());
        }
        throw new IllegalStateException("no workspace dependency or sibling named `" + name + "`");
    }
}
