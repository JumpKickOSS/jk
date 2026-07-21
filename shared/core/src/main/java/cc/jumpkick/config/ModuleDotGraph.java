// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Emit a Graphviz DOT digraph of workspace module prereq edges (same rules as {@link ModuleOrder}
 * / {@link AffectedModules#edgesFor}). No Graphviz binary required — users pipe to {@code dot -Tsvg}
 * externally.
 */
public final class ModuleDotGraph {

    private ModuleDotGraph() {}

    /**
     * Render DOT for {@code modulesByDir}. When {@code only} is non-null, only those module dirs
     * (and edges fully inside the set) appear. Empty map → a trivial empty digraph.
     */
    public static String toDot(Path workspaceRoot, Map<Path, JkBuild> modulesByDir, Set<Path> only) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(modulesByDir, "modulesByDir");
        Path root = workspaceRoot.toAbsolutePath().normalize();

        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            Path dir = e.getKey().toAbsolutePath().normalize();
            if (only != null && !containsNormalized(only, dir)) continue;
            modules.put(dir, e.getValue());
        }

        Map<Path, Set<Path>> edges = AffectedModules.edgesFor(modulesByDir);
        // Restrict edges to the filtered node set.
        Map<Path, Set<Path>> filteredEdges = new LinkedHashMap<>();
        for (Path mod : modules.keySet()) {
            Set<Path> prereqs = new LinkedHashSet<>();
            for (Path p : edges.getOrDefault(mod, Set.of())) {
                Path pn = p.toAbsolutePath().normalize();
                if (modules.containsKey(pn)) prereqs.add(pn);
            }
            filteredEdges.put(mod, prereqs);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("digraph modules {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box, fontname=\"Helvetica\"];\n");

        Map<Path, String> ids = new LinkedHashMap<>();
        int i = 0;
        for (var e : modules.entrySet()) {
            String id = "m" + (i++);
            ids.put(e.getKey(), id);
            String coord = e.getValue().project().group() + ":" + e.getValue().project().name();
            String rel = relLabel(root, e.getKey());
            // Prefer coord as visible label; path as Graphviz tooltip for monorepo debugging.
            sb.append("  ")
                    .append(id)
                    .append(" [label=")
                    .append(quote(coord))
                    .append(", tooltip=")
                    .append(quote(rel))
                    .append("];\n");
        }

        // Edges: dependent → prereq (same direction as ModuleOrder prereq edges).
        // DOT convention for "depends on" is often dependent → dependency; matches prereq map.
        for (var e : filteredEdges.entrySet()) {
            String from = ids.get(e.getKey());
            if (from == null) continue;
            for (Path prereq : e.getValue()) {
                String to = ids.get(prereq);
                if (to == null) continue;
                sb.append("  ").append(from).append(" -> ").append(to).append(";\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Single-module project (not a workspace root): one node, no edges.
     */
    public static String singleModuleDot(JkBuild build, Path projectDir) {
        Objects.requireNonNull(build, "build");
        String coord = build.project().group() + ":" + build.project().name();
        if (coord.equals(":")) {
            coord = projectDir != null ? projectDir.getFileName().toString() : "module";
        }
        return "digraph modules {\n"
                + "  rankdir=LR;\n"
                + "  node [shape=box, fontname=\"Helvetica\"];\n"
                + "  m0 [label="
                + quote(coord)
                + "];\n"
                + "}\n";
    }

    private static boolean containsNormalized(Set<Path> only, Path dir) {
        Path n = dir.toAbsolutePath().normalize();
        for (Path p : only) {
            if (p.toAbsolutePath().normalize().equals(n)) return true;
        }
        return false;
    }

    private static String relLabel(Path root, Path moduleDir) {
        try {
            Path rel = root.relativize(moduleDir);
            String s = rel.toString().replace('\\', '/');
            return s.isEmpty() ? "." : s;
        } catch (IllegalArgumentException e) {
            return moduleDir.toString();
        }
    }

    /** Graphviz double-quoted string with escapes. */
    static String quote(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
