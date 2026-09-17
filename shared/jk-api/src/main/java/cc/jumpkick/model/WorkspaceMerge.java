// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

        Map<String, JkBuild> siblingByArtifact = siblingIndex(root, allModules);
        Set<String> internal = new HashSet<>();
        for (JkBuild m : allModules)
            internal.add(m.project().group() + ":" + m.project().name());
        internal.add(root.project().group() + ":" + root.project().name());
        Map<String, Workspace.WorkspaceDependency> wsDeps =
                root.workspace() != null ? root.workspace().dependencies() : Map.of();

        // First pass: resolve this module's own deps, strip sibling refs, and track which
        // siblings are direct dependencies (for export propagation) by their group:name — two
        // siblings may share a name.
        Set<String> dependedSiblings = new LinkedHashSet<>();
        Map<Scope, List<Dependency>> resolvedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            List<Dependency> resolved = new ArrayList<>();
            for (Dependency d : module.dependencies().of(scope)) {
                Dependency r = resolve(d, siblingByArtifact, wsDeps);
                if (internal.contains(r.module())) {
                    dependedSiblings.add(r.module());
                    continue;
                }
                resolved.add(r);
            }
            if (!resolved.isEmpty()) resolvedByScope.put(scope, resolved);
        }

        // The platform table a member's own graph is solved under: the root's BOMs first, then the
        // member's own, then those of the siblings it depends on — a BOM constrains the member that
        // declares it and the members that depend on that member, never an unrelated one.
        List<Dependency> platform = new ArrayList<>();
        for (Dependency d : root.dependencies().of(Scope.PLATFORM)) addPlatform(platform, d);
        for (Dependency d : resolvedByScope.getOrDefault(Scope.PLATFORM, List.of())) addPlatform(platform, d);
        // The managed table folds the same way: the root's entries first, then the member's own.
        List<Dependency> managed = new ArrayList<>();
        for (Dependency d : root.dependencies().of(Scope.MANAGED)) addPlatform(managed, d);
        for (Dependency d : resolvedByScope.getOrDefault(Scope.MANAGED, List.of())) addPlatform(managed, d);

        // Transitive MAIN+EXPORT externals from reachable siblings into this module's main scope.
        Set<String> visited = new LinkedHashSet<>(dependedSiblings);
        ArrayDeque<String> queue = new ArrayDeque<>(dependedSiblings);
        while (!queue.isEmpty()) {
            JkBuild sibling = siblingByArtifact.get(queue.poll());
            if (sibling == null) continue;
            for (Dependency d : sibling.dependencies().of(Scope.PLATFORM)) addPlatform(platform, d);
            for (Dependency d : sibling.dependencies().of(Scope.MANAGED)) addPlatform(managed, d);
            for (Scope scope : List.of(Scope.MAIN, Scope.EXPORT)) {
                for (Dependency d : sibling.dependencies().of(scope)) {
                    Dependency r = resolve(d, siblingByArtifact, wsDeps);
                    if (internal.contains(r.module())) {
                        if (visited.add(r.module())) queue.add(r.module());
                        continue;
                    }
                    List<Dependency> mainList = resolvedByScope.computeIfAbsent(Scope.MAIN, k -> new ArrayList<>());
                    if (mainList.stream().noneMatch(e -> e.packageKey().equals(r.packageKey()))) {
                        mainList.add(r);
                    }
                }
            }
        }
        if (platform.isEmpty()) {
            resolvedByScope.remove(Scope.PLATFORM);
        } else {
            resolvedByScope.put(Scope.PLATFORM, platform);
        }
        if (managed.isEmpty()) {
            resolvedByScope.remove(Scope.MANAGED);
        } else {
            resolvedByScope.put(Scope.MANAGED, managed);
        }

        JkBuild.Builder out = JkBuild.builder(module.project())
                .dependencies(new JkBuild.Dependencies(resolvedByScope))
                .repositories(module.repositories())
                .profiles(module.profiles())
                .features(module.features())
                .workspace(module.workspace())
                .manifest(module.manifest())
                .plugins(module.plugins())
                .application(module.applicationOpt().orElse(null))
                .nativeConfig(module.nativeConfigOpt().orElse(null))
                .build(module.build())
                .format(module.format())
                .variants(module.variants())
                .install(module.install());
        for (PluginConfig config : module.pluginConfigs().values()) {
            out.pluginConfig(config);
        }
        return out.build();
    }

    /** Add a BOM or managed entry to a member's table unless an earlier entry already manages that module. */
    private static void addPlatform(List<Dependency> platform, Dependency bom) {
        if (platform.stream().noneMatch(e -> e.module().equals(bom.module()))) platform.add(bom);
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
        Map<String, JkBuild> siblingByArtifact = siblingIndex(root, allModules);
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
                .application(module.applicationOpt().orElse(null))
                .nativeConfig(module.nativeConfigOpt().orElse(null))
                .build(module.build())
                .format(module.format())
                .variants(module.variants())
                .install(module.install());
        for (PluginConfig config : module.pluginConfigs().values()) {
            out.pluginConfig(config);
        }
        return out.build();
    }

    /**
     * The one manifest a workspace lock resolves: the root's dependencies, then every member's, in
     * {@code [workspace] modules} order and each in declaration order, one row per package (the
     * first declaration wins). Its repositories are the root's followed by every member's, keyed by
     * id ({@link #joinRepositories}). The root's {@code [resolve]} table travels with it — a
     * workspace's pin and platform policies are the root's.
     */
    public static JkBuild merge(JkBuild root, Collection<JkBuild> modules) {
        if (modules.isEmpty()) return Variants.unionDependencies(root);

        // Union variant dep overlays into every manifest before folding (see applyToModule).
        root = Variants.unionDependencies(root);
        List<JkBuild> unionModules = new ArrayList<>(modules.size());
        for (JkBuild m : modules) unionModules.add(Variants.unionDependencies(m));
        modules = unionModules;

        Map<String, JkBuild> siblingByArtifact = siblingIndex(root, modules);
        Set<String> internal = new HashSet<>();
        for (JkBuild module : modules)
            internal.add(module.project().group() + ":" + module.project().name());
        internal.add(root.project().group() + ":" + root.project().name());

        Map<String, Workspace.WorkspaceDependency> wsDeps =
                root.workspace() != null ? root.workspace().dependencies() : Map.of();

        Map<Scope, List<Dependency>> mergedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            // Dedup on packageKey(), not bare module(): LockOrchestrator roots per package
            // (e65323f6), so the main jar and the test-jar of one GA are distinct rows here too.
            Map<String, Dependency> dedup = new LinkedHashMap<>();
            for (Dependency d : root.dependencies().of(scope)) {
                Dependency resolved = resolve(d, siblingByArtifact, wsDeps);
                if (internal.contains(resolved.module())) continue;
                dedup.putIfAbsent(resolved.packageKey(), resolved);
            }
            for (JkBuild module : modules) {
                for (Dependency d : module.dependencies().of(scope)) {
                    Dependency resolved = resolve(d, siblingByArtifact, wsDeps);
                    if (internal.contains(resolved.module())) continue;
                    dedup.putIfAbsent(resolved.packageKey(), resolved);
                }
            }
            if (!dedup.isEmpty()) {
                mergedByScope.put(scope, new ArrayList<>(dedup.values()));
            }
        }
        return JkBuild.builder(root.project())
                .dependencies(new JkBuild.Dependencies(mergedByScope))
                .repositories(joinRepositories(root, modules))
                .profiles(root.profiles())
                .features(root.features())
                .workspace(root.workspace())
                .manifest(root.manifest())
                .plugins(root.plugins())
                .application(root.applicationOpt().orElse(null))
                .nativeConfig(root.nativeConfigOpt().orElse(null))
                .build(root.build())
                .build();
    }

    /**
     * The workspace's one repository set: the root's {@code [repositories]} entries, then each
     * member's in {@code [workspace] modules} order, keyed by id — an id already declared adds
     * nothing. One id at two URLs is refused naming both modules: the lock resolves every member
     * against one set, so the two repositories need two ids or one URL.
     */
    static List<RepositorySpec> joinRepositories(JkBuild root, Collection<JkBuild> modules) {
        Map<String, RepositorySpec> byId = new LinkedHashMap<>();
        Map<String, String> declaredIn = new HashMap<>();
        String rootCoord = coordinate(root);
        for (RepositorySpec spec : root.repositories()) {
            if (byId.putIfAbsent(spec.name(), spec) == null) declaredIn.put(spec.name(), rootCoord);
        }
        for (JkBuild module : modules) {
            String coord = coordinate(module);
            for (RepositorySpec spec : module.repositories()) {
                RepositorySpec first = byId.putIfAbsent(spec.name(), spec);
                if (first == null) {
                    declaredIn.put(spec.name(), coord);
                } else if (!first.url().equals(spec.url())) {
                    throw new IllegalStateException("[repositories] " + spec.name() + " is " + first.url() + " in "
                            + declaredIn.get(spec.name()) + " and " + spec.url() + " in " + coord
                            + "; a workspace resolves against one repository set — give the two repositories two ids,"
                            + " or one URL");
                }
            }
        }
        return List.copyOf(byId.values());
    }

    private static String coordinate(JkBuild build) {
        return build.project().group() + ":" + build.project().name();
    }

    /**
     * Every unit of the workspace under two keys: its bare name, which a {@code workspace = true}
     * edge spells, and its {@code group:name}, which a group-qualified edge spells. The root is a
     * unit too (Cargo/uv style: a member can {@code <root> = { workspace = true }}).
     */
    private static Map<String, JkBuild> siblingIndex(JkBuild root, Collection<JkBuild> allModules) {
        Map<String, JkBuild> index = new LinkedHashMap<>();
        for (JkBuild m : allModules) {
            index.put(m.project().name(), m);
            index.put(m.project().group() + ":" + m.project().name(), m);
        }
        index.put(root.project().name(), root);
        index.put(root.project().group() + ":" + root.project().name(), root);
        return index;
    }

    /**
     * Rewrites a placeholder {@code workspace:<name>} dep into the real coord, using the sibling list
     * first and then the workspace's shared-dep table. A group-qualified placeholder names the
     * sibling by {@code group:name} and never reaches the shared-dep table. Non-placeholder deps
     * pass through unchanged.
     */
    private static Dependency resolve(
            Dependency d, Map<String, JkBuild> siblingByArtifact, Map<String, Workspace.WorkspaceDependency> wsDeps) {
        if (!d.isWorkspace()) return d;
        String name = Objects.requireNonNull(d.workspaceName());
        String coordinate = Dependency.workspaceCoordinate(d.module());
        JkBuild sibling = siblingByArtifact.get(coordinate != null ? coordinate : name);
        if (sibling == null && coordinate != null) {
            throw new IllegalStateException("no workspace member `" + coordinate + "`");
        }
        if (sibling != null) {
            Project p = sibling.project();
            String module = p.group() + ":" + p.name();
            // Preserve kind and fixtures so classpath resolution still sees both flags. For lock,
            // siblings are dropped.
            return Dependency.of(d.library(), module, VersionSelector.parse("=" + p.version()))
                    .withKind(d.kind())
                    .withFixtures(d.fixtures())
                    .withExclusions(d.exclusions());
        }
        Workspace.WorkspaceDependency ws = wsDeps.get(name);
        if (ws != null) {
            if (ws.gitSource() != null) {
                return Dependency.git(d.library(), ws.module(), ws.gitSource())
                        .withKind(d.kind())
                        .withFixtures(d.fixtures())
                        .withExclusions(d.exclusions());
            }
            return Dependency.of(d.library(), ws.module(), Objects.requireNonNull(ws.version()))
                    .withKind(d.kind())
                    .withFixtures(d.fixtures())
                    .withExclusions(d.exclusions());
        }
        throw new IllegalStateException("no workspace dependency or sibling named `" + name + "`");
    }
}
