// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Map changed paths onto workspace modules and close over reverse dependency edges (dependents).
 * Used by {@code jk build --affected-since=&lt;ref&gt;}.
 */
public final class AffectedModules {

    private AffectedModules() {}

    /**
     * Given absolute module dirs and edges (module → prereqs), return the reverse-dep closure of
     * modules that contain any of {@code changedPaths} (relative to {@code workspaceRoot} or
     * absolute).
     */
    public static Set<Path> fromChangedPaths(
            Path workspaceRoot, Collection<Path> moduleDirs, Map<Path, Set<Path>> edges, List<String> changedPaths) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<Path> mods = new ArrayList<>();
        for (Path m : moduleDirs) mods.add(m.toAbsolutePath().normalize());

        Set<Path> directly = new LinkedHashSet<>();
        for (String raw : changedPaths) {
            if (raw == null || raw.isBlank()) continue;
            Path p = Path.of(raw);
            if (!p.isAbsolute()) p = root.resolve(p);
            p = p.normalize();
            Path best = null;
            int bestLen = -1;
            for (Path mod : mods) {
                if (p.startsWith(mod) && mod.getNameCount() > bestLen) {
                    best = mod;
                    bestLen = mod.getNameCount();
                }
            }
            if (best != null) {
                directly.add(best);
            } else if (p.startsWith(root)) {
                // Workspace-level change (root jk.toml, etc.) — all modules.
                directly.addAll(mods);
            }
        }
        return reverseClosure(edges, directly);
    }

    /**
     * Build prereq edges for a loaded workspace (same edge rules as {@link ModuleOrder}).
     */
    public static Map<Path, Set<Path>> edgesFor(Map<Path, JkBuild> modulesByDir) {
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<String, Path> dirByName = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            JkBuild m = e.getValue();
            dirByCoord.put(m.project().group() + ":" + m.project().name(), e.getKey());
            dirByName.put(m.project().name(), e.getKey());
        }
        Map<Path, Set<Path>> edges = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            edges.put(
                    e.getKey().toAbsolutePath().normalize(),
                    ModuleOrder.modulePrereqs(e.getKey(), e.getValue(), dirByCoord, dirByName).stream()
                            .map(p -> p.toAbsolutePath().normalize())
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        return edges;
    }

    /** Reverse-dep closure of {@code seed} over prereq edges (module → prereqs). */
    public static Set<Path> reverseClosure(Map<Path, Set<Path>> edges, Set<Path> seed) {
        Map<Path, Set<Path>> reverse = new LinkedHashMap<>();
        for (var e : edges.entrySet()) {
            Path dependent = e.getKey().toAbsolutePath().normalize();
            for (Path prereq : e.getValue()) {
                reverse.computeIfAbsent(prereq.toAbsolutePath().normalize(), k -> new LinkedHashSet<>())
                        .add(dependent);
            }
        }
        Set<Path> out = new LinkedHashSet<>();
        List<Path> stack = new ArrayList<>();
        for (Path s : seed) {
            Path n = s.toAbsolutePath().normalize();
            if (out.add(n)) stack.add(n);
        }
        while (!stack.isEmpty()) {
            Path cur = stack.removeLast();
            for (Path dep : reverse.getOrDefault(cur, Set.of())) {
                if (out.add(dep)) stack.add(dep);
            }
        }
        return out;
    }
}
