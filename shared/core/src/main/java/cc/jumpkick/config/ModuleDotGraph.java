// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Emit a module dependency DAG for {@code jk explain --graph} and the engine dashboard.
 * Formats: Graphviz DOT, Mermaid flowchart, and a structured {@link GraphData} model (JSON for
 * the web client). Same edges as {@link ModuleOrder} / {@link AffectedModules#edgesFor}. No
 * external binary required — users pipe DOT to {@code dot -Tsvg} or open Mermaid in any renderer.
 */
public final class ModuleDotGraph {

    /** Supported {@code --graph} format names (lowercase). */
    public static final Set<String> FORMATS = Set.of("dot", "mermaid");

    private ModuleDotGraph() {}

    /**
     * One module in the DAG. {@code id} is stable within a response ({@code m0}, {@code m1}, …);
     * {@code label} is {@code group:name}; {@code path} is the workspace-relative dir (or
     * {@code "."} for a standalone project).
     */
    public record Node(String id, String label, String path) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * Dependency edge: {@code from} (dependent) → {@code to} (prereq), same direction as
     * {@link ModuleOrder} / DOT / Mermaid.
     */
    public record Edge(String from, String to) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * Structured module DAG for JSON / ECharts. Empty {@code nodes} is a valid empty workspace.
     *
     * @param workspace {@code true} when built from a {@code [workspace]} root
     */
    public record GraphData(boolean workspace, List<Node> nodes, List<Edge> edges) {
        public GraphData {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        public static GraphData empty(boolean workspace) {
            return new GraphData(workspace, List.of(), List.of());
        }
    }

    /** True when {@code format} is a known graph format (case-insensitive). */
    public static boolean isSupportedFormat(String format) {
        if (format == null || format.isBlank()) return false;
        return FORMATS.contains(format.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Render the module DAG in {@code format} ({@code dot} or {@code mermaid}). When {@code only}
     * is non-null, only those module dirs (and edges fully inside the set) appear.
     */
    public static String render(String format, Path workspaceRoot, Map<Path, JkBuild> modulesByDir, Set<Path> only) {
        String fmt = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (fmt) {
            case "dot" -> toDot(workspaceRoot, modulesByDir, only);
            case "mermaid" -> toMermaid(workspaceRoot, modulesByDir, only);
            default -> throw new IllegalArgumentException("unsupported graph format: " + format);
        };
    }

    /**
     * Structured DAG for {@code modulesByDir}. When {@code only} is non-null, only those module
     * dirs (and edges fully inside the set) appear.
     */
    public static GraphData graphData(Path workspaceRoot, Map<Path, JkBuild> modulesByDir, @Nullable Set<Path> only) {
        Graph g = build(workspaceRoot, modulesByDir, only == null ? Set.of() : only);
        return toGraphData(g, true);
    }

    /** Single-module project: one node, no edges. */
    public static GraphData singleModuleData(JkBuild build, Path projectDir) {
        Objects.requireNonNull(build, "build");
        Path dir = projectDir != null ? projectDir.toAbsolutePath().normalize() : Path.of(".");
        String label = coordOf(build, dir);
        return new GraphData(false, List.of(new Node("m0", label, ".")), List.of());
    }

    /**
     * Load the module DAG for a project directory (same rules as {@code jk explain --graph}):
     * workspace root → all modules; standalone project → one node. Missing / unparseable
     * {@code jk.toml} → empty non-workspace graph (never throws for absent files).
     */
    public static GraphData forProjectDir(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir");
        Path root = projectDir.toAbsolutePath().normalize();
        Path toml = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(toml)) {
            return GraphData.empty(false);
        }
        try {
            JkBuild entry = JkBuildParser.parse(toml);
            if (entry.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, entry);
                return graphData(root, modules, null);
            }
            return singleModuleData(entry, root);
        } catch (IOException | RuntimeException e) {
            // Missing modules / parse errors: empty graph rather than 500 (detail page still loads).
            return GraphData.empty(false);
        }
    }

    /**
     * Render DOT for {@code modulesByDir}. When {@code only} is non-null, only those module dirs
     * (and edges fully inside the set) appear. Empty map → a trivial empty digraph.
     */
    public static String toDot(Path workspaceRoot, Map<Path, JkBuild> modulesByDir, Set<Path> only) {
        Graph g = build(workspaceRoot, modulesByDir, only);
        StringBuilder sb = new StringBuilder();
        sb.append("digraph modules {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box, fontname=\"Helvetica\"];\n");

        for (var e : g.ids.entrySet()) {
            Path dir = e.getKey();
            String id = e.getValue();
            JkBuild build = g.modules.get(dir);
            String coord = coordOf(build, dir);
            String rel = relLabel(g.root, dir);
            sb.append("  ")
                    .append(id)
                    .append(" [label=")
                    .append(quoteDot(coord))
                    .append(", tooltip=")
                    .append(quoteDot(rel))
                    .append("];\n");
        }

        // Edges: dependent → prereq (same direction as ModuleOrder prereq edges).
        for (var e : g.edges.entrySet()) {
            String from = g.ids.get(e.getKey());
            if (from == null) continue;
            for (Path prereq : e.getValue()) {
                String to = g.ids.get(prereq);
                if (to == null) continue;
                sb.append("  ").append(from).append(" -> ").append(to).append(";\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Mermaid {@code flowchart LR} for the same DAG. Labels use quoted node text; node ids stay
     * alphanumeric ({@code m0}, {@code m1}, …) so coordinates with {@code :} are safe.
     */
    public static String toMermaid(Path workspaceRoot, Map<Path, JkBuild> modulesByDir, Set<Path> only) {
        Graph g = build(workspaceRoot, modulesByDir, only);
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart LR\n");
        if (g.modules.isEmpty()) {
            // Valid empty diagram (no nodes).
            return sb.toString();
        }
        for (var e : g.ids.entrySet()) {
            Path dir = e.getKey();
            String id = e.getValue();
            String coord = coordOf(g.modules.get(dir), dir);
            sb.append("  ")
                    .append(id)
                    .append("[\"")
                    .append(escapeMermaidLabel(coord))
                    .append("\"]\n");
        }
        for (var e : g.edges.entrySet()) {
            String from = g.ids.get(e.getKey());
            if (from == null) continue;
            for (Path prereq : e.getValue()) {
                String to = g.ids.get(prereq);
                if (to == null) continue;
                sb.append("  ").append(from).append(" --> ").append(to).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Single-module project (not a workspace root): one node, no edges.
     */
    public static String singleModuleDot(JkBuild build, Path projectDir) {
        return singleModule(build, projectDir, "dot");
    }

    /** Single-module Mermaid flowchart (one node). */
    public static String singleModuleMermaid(JkBuild build, Path projectDir) {
        return singleModule(build, projectDir, "mermaid");
    }

    /** Single-module graph in the given format. */
    public static String singleModule(JkBuild build, Path projectDir, String format) {
        Objects.requireNonNull(build, "build");
        String coord = coordOf(build, projectDir);
        String fmt = format == null ? "dot" : format.trim().toLowerCase(Locale.ROOT);
        return switch (fmt) {
            case "mermaid" -> "flowchart LR\n  m0[\"" + escapeMermaidLabel(coord) + "\"]\n";
            case "dot" ->
                "digraph modules {\n"
                        + "  rankdir=LR;\n"
                        + "  node [shape=box, fontname=\"Helvetica\"];\n"
                        + "  m0 [label="
                        + quoteDot(coord)
                        + "];\n"
                        + "}\n";
            default -> throw new IllegalArgumentException("unsupported graph format: " + format);
        };
    }

    private record Graph(Path root, Map<Path, JkBuild> modules, Map<Path, Set<Path>> edges, Map<Path, String> ids) {}

    private static GraphData toGraphData(Graph g, boolean workspace) {
        List<Node> nodes = new ArrayList<>(g.ids.size());
        for (var e : g.ids.entrySet()) {
            Path dir = e.getKey();
            nodes.add(new Node(e.getValue(), coordOf(g.modules.get(dir), dir), relLabel(g.root, dir)));
        }
        List<Edge> edges = new ArrayList<>();
        for (var e : g.edges.entrySet()) {
            String from = g.ids.get(e.getKey());
            if (from == null) continue;
            for (Path prereq : e.getValue()) {
                String to = g.ids.get(prereq);
                if (to == null) continue;
                edges.add(new Edge(from, to));
            }
        }
        return new GraphData(workspace, nodes, edges);
    }

    private static Graph build(Path workspaceRoot, Map<Path, JkBuild> modulesByDir, Set<Path> only) {
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
        Map<Path, Set<Path>> filteredEdges = new LinkedHashMap<>();
        for (Path mod : modules.keySet()) {
            Set<Path> prereqs = new LinkedHashSet<>();
            for (Path p : edges.getOrDefault(mod, Set.of())) {
                Path pn = p.toAbsolutePath().normalize();
                if (modules.containsKey(pn)) prereqs.add(pn);
            }
            filteredEdges.put(mod, prereqs);
        }

        Map<Path, String> ids = new LinkedHashMap<>();
        int i = 0;
        for (Path dir : modules.keySet()) {
            ids.put(dir, "m" + (i++));
        }
        return new Graph(root, modules, filteredEdges, ids);
    }

    private static String coordOf(@Nullable JkBuild build, Path dir) {
        if (build == null) {
            return dir != null && dir.getFileName() != null ? dir.getFileName().toString() : "module";
        }
        String coord = build.project().group() + ":" + build.project().name();
        if (coord.equals(":")) {
            return dir != null && dir.getFileName() != null ? dir.getFileName().toString() : "module";
        }
        return coord;
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
        return quoteDot(s);
    }

    static String quoteDot(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** Escape text inside a Mermaid quoted node label {@code id["…"]}. */
    static String escapeMermaidLabel(String s) {
        if (s == null) s = "";
        // Mermaid: " ends the label; # starts comments; < > can open HTML labels.
        return s.replace("\\", "\\\\")
                .replace("\"", "#quot;")
                .replace("#", "#35;")
                .replace("<", "#lt;")
                .replace(">", "#gt;")
                .replace("\n", " ");
    }
}
