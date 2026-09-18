// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.DependencyTreeStyle.Styling;
import cc.jumpkick.resolver.WorkspaceGraph.LoadedModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk tree --flatten}: each scope's full transitive closure as one deduplicated, sorted list.
 *
 * <p>A separate owner from {@link DependencyTree} because it is a separate traversal with a
 * separate invariant. The nested render's correctness rests on revisit-<em>marking</em> — a node
 * seen higher up prints as a {@code ⎋} back-reference so a diamond does not expand twice — and that
 * only works because the walk that marks and the render that reads the mark are the same recursion.
 * Flattening has no rails, no depth and no back-references: its {@code visited} set is cycle
 * protection for an accumulation into a {@link TreeMap}, and {@code maxDepth} is meaningless here.
 * Two different meanings for "already seen" is what made this 230 lines of near-miss lookalike
 * inside the nested walk.
 */
final class DependencyFlatten {

    private DependencyFlatten() {}

    /**
     * One flattened dependency: {@code group:artifact}, optional resolved version, and a tag — a pin
     * source's kind, or the members a partition row is read by.
     */
    private record FlatDep(String module, @Nullable String version, String tag) {
        /** The list key: a member's partition of a coordinate lists beside the workspace's row. */
        String key() {
            return module + tag;
        }
    }

    /** Single-project flatten: each scope lists its full transitive dep closure, flat + sorted. */
    static void renderScopes(
            JkBuild project,
            Lockfile lock,
            Styling styling,
            List<Scope> scopeOrder,
            boolean stack,
            WorkspaceGraph ws,
            StringBuilder out) {

        List<Scope> sections = new ArrayList<>();
        for (Scope s : DependencyTreeStyle.sectionOrder(scopeOrder)) {
            if (!project.dependencies().of(s).isEmpty()) sections.add(s);
        }
        if (sections.isEmpty()) return;

        Map<String, String> declared = DeclaredDeps.versions(project, sections);
        if (stack) {
            LockGraph graph = LockGraph.forLock(lock, sections);
            Map<String, String> pinTags = DeclaredDeps.pinTags(project, sections);
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (Scope s : sections) {
                for (String m : DeclaredDeps.modulesOf(project, s)) {
                    collect(m, graph, ws, visited, collected, declared.get(m), pinTags.get(m));
                }
            }
            renderSection(DependencyTreeStyle.badgeRow(sections, styling), true, collected, styling, out);
            return;
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            // The section's own graph: a scope-split coordinate reads at this scope's version.
            LockGraph graph = LockGraph.forLock(lock, List.of(s));
            Map<String, String> pinTags = DeclaredDeps.pinTags(project, List.of(s));
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (String m : DeclaredDeps.modulesOf(project, s)) {
                collect(m, graph, ws, visited, collected, declared.get(m), pinTags.get(m));
            }
            renderSection(
                    styling.scopeBadge().apply(DependencyTreeStyle.scopeLabel(s)),
                    si == sections.size() - 1,
                    collected,
                    styling,
                    out);
        }
    }

    /**
     * Workspace-root flatten: each scope is the union of every unit's closure for that scope, the
     * root's own tables included.
     */
    static void renderWorkspaceScopes(
            JkBuild root,
            @Nullable Lockfile lock,
            Path rootDir,
            Styling styling,
            List<Scope> scopeOrder,
            boolean stack,
            StringBuilder out) {

        WorkspaceGraph ws = WorkspaceGraph.collapse(WorkspaceGraph.modulesByName(root.workspaceModules(), rootDir));
        List<LoadedModule> modules =
                WorkspaceGraph.withRoot(root, lock, WorkspaceGraph.loadModules(root.workspaceModules(), rootDir));

        List<Scope> sections = new ArrayList<>();
        for (Scope s : DependencyTreeStyle.sectionOrder(scopeOrder)) {
            if (modules.stream().anyMatch(m -> !m.build().dependencies().of(s).isEmpty())) {
                sections.add(s);
            }
        }
        if (sections.isEmpty()) return;

        if (stack) {
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (LoadedModule m : modules) {
                LockGraph graph = LockGraph.forLock(m.lock(), sections);
                Map<String, String> declared = DeclaredDeps.versions(m.build(), sections);
                Map<String, String> pinTags = DeclaredDeps.pinTags(m.build(), sections);
                for (Scope s : sections) {
                    for (String dep : DeclaredDeps.modulesOf(m.build(), s)) {
                        collect(dep, graph, ws, visited, collected, declared.get(dep), pinTags.get(dep));
                    }
                }
            }
            renderSection(DependencyTreeStyle.badgeRow(sections, styling), true, collected, styling, out);
            return;
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (LoadedModule m : modules) {
                if (m.build().dependencies().of(s).isEmpty()) continue;
                LockGraph graph = LockGraph.forLock(m.lock(), List.of(s));
                Map<String, String> declared = DeclaredDeps.versions(m.build(), List.of(s));
                Map<String, String> pinTags = DeclaredDeps.pinTags(m.build(), List.of(s));
                for (String dep : DeclaredDeps.modulesOf(m.build(), s)) {
                    collect(dep, graph, ws, visited, collected, declared.get(dep), pinTags.get(dep));
                }
            }
            renderSection(
                    styling.scopeBadge().apply(DependencyTreeStyle.scopeLabel(s)),
                    si == sections.size() - 1,
                    collected,
                    styling,
                    out);
        }
    }

    /** Emit a header badge (a single scope or a stacked badge row) then a flat, sorted dep list. */
    private static void renderSection(
            String headerBadge, boolean last, Map<String, FlatDep> collected, Styling styling, StringBuilder out) {

        out.append(styling.rail().apply(last ? "╰─" : "├─")).append(headerBadge).append('\n');
        String scopePrefix = styling.rail().apply(last ? "   " : "│  ");
        List<FlatDep> deps = new ArrayList<>(collected.values());
        for (int i = 0; i < deps.size(); i++) {
            FlatDep d = deps.get(i);
            out.append(scopePrefix)
                    .append(styling.rail().apply(i == deps.size() - 1 ? "╰─ " : "├─ "))
                    .append(TreeCoords.coordVersioned(d.module(), d.version(), styling))
                    .append(styling.rail().apply(d.tag()))
                    .append('\n');
        }
    }

    private static void collect(
            String module, LockGraph graph, WorkspaceGraph ws, Set<String> visited, Map<String, FlatDep> out) {
        collect(module, graph, ws, visited, out, null, null);
    }

    /**
     * Walk a dependency and its transitive closure, accumulating distinct coords into {@code out}.
     *
     * @param pinTag the tag of a pin source (a BOM or a managed entry), or {@code null} for a dependency
     */
    private static void collect(
            String module,
            LockGraph graph,
            WorkspaceGraph ws,
            Set<String> visited,
            Map<String, FlatDep> out,
            @Nullable String declaredVersion,
            @Nullable String pinTag) {

        LoadedModule sibling = ws.sibling(module);
        if (sibling != null) {
            // ws.sibling only hits in the member graph (byGa is populated only by forMember);
            // the collapsed [workspace] form renders via Dependency.isWorkspaceRef below.
            String ga = WorkspaceGraph.moduleGa(sibling.build());
            String ver = sibling.build().project().version();
            if (!visited.add(ga)) return;
            // A relocating sibling's fat jar bundles its graph: the consumer sees the jar alone.
            if (sibling.build().relocates()) {
                put(out, new FlatDep(ga, ver, DependencyTree.SHADED_SUFFIX));
                return;
            }
            put(out, new FlatDep(ga, ver, ""));
            LockGraph siblingGraph = sibling.lock() == null ? graph : LockGraph.forLock(sibling.lock());
            // The sibling contributes its own surface (export/main/runtime), not whatever
            // scope section of the consumer declared it. Module (workspace) edges chain
            // only through export/main — WorkspaceClasspath never adds a sibling's RUNTIME module
            // deps to the consumer's classpath, so the tree must not draw them either.
            for (Scope s : WorkspaceGraph.siblingContributedScopes()) {
                boolean moduleEdges = WorkspaceGraph.chainsModuleEdges(s);
                for (String dep : DeclaredDeps.inheritedModulesOf(sibling.build(), s)) {
                    if (!moduleEdges && ws.isSiblingDep(dep)) continue;
                    collect(dep, siblingGraph, ws, visited, out);
                }
            }
            return;
        }
        if (Dependency.isWorkspaceRef(module)) {
            put(out, new FlatDep(ws.collapsedCoord(module), null, " [workspace]"));
            return;
        }
        // A member's partition rows below a coordinate make its closure a different one.
        if (!visited.add(graph.subtreeKey(module))) return;
        Lockfile.Artifact pkg = graph.artifact(module);
        if (pkg == null) {
            if (pinTag != null) {
                put(out, new FlatDep(module, declaredVersion, pinTag));
            } else {
                put(out, new FlatDep(module, null, DependencyTreeStyle.MISSING_SUFFIX));
            }
            return;
        }
        put(out, new FlatDep(module, pkg.version(), graph.membersTag(module)));
        for (String child : graph.forward(module)) {
            collect(child, graph, ws, visited, out);
        }
    }

    /**
     * Dedup by {@code group:artifact} and tag, preferring an entry that carries a resolved version;
     * a partition row lists beside the workspace's row under its members.
     */
    private static void put(Map<String, FlatDep> out, FlatDep dep) {
        FlatDep existing = out.get(dep.key());
        if (existing == null || (existing.version() == null && dep.version() != null)) {
            out.put(dep.key(), dep);
        }
    }
}
