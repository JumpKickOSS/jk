// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** graphify's structural highlights: the most-connected entities and the edges least expected. */
public final class GraphAnalysis {

    private GraphAnalysis() {}

    private static final Set<String> NOISE = Set.of(
            "Object",
            "String",
            "Exception",
            "RuntimeException",
            "Override",
            "List",
            "Map",
            "Set",
            "Optional",
            "Integer",
            "Long",
            "Boolean",
            "Any",
            "Unit",
            "object",
            "string",
            "number",
            "boolean",
            "any",
            "void");

    /** A ranked edge worth a reader's attention, with why. */
    public record Surprise(GraphEdge edge, int score, String why) {}

    /** The {@code topN} highest-degree entities: files, concepts and method stubs excluded. */
    public static List<String> godNodes(CodeGraph graph, int topN) {
        return graph.nodes().stream()
                .filter(n -> !isStructural(graph, n) && !NOISE.contains(n.label()))
                .sorted(Comparator.comparingInt((GraphNode n) -> -graph.degree(n.id()))
                        .thenComparing(GraphNode::id))
                .limit(topN)
                .map(GraphNode::id)
                .toList();
    }

    /**
     * Cross-file edges ranked by how unexpected they are: an uncertain edge, one crossing file
     * types, top-level directories or communities, and a peripheral node reaching a hub all score.
     */
    public static List<Surprise> surprises(CodeGraph graph, int topN) {
        List<Surprise> out = new ArrayList<>();
        for (GraphEdge e : graph.edges()) {
            GraphNode a = graph.node(e.source());
            GraphNode b = graph.node(e.target());
            if (a == null
                    || b == null
                    || a.sourceFile().isEmpty()
                    || b.sourceFile().isEmpty()) continue;
            if (a.sourceFile().equals(b.sourceFile()) || isStructural(graph, a) || isStructural(graph, b)) continue;
            int score =
                    switch (e.confidence()) {
                        case AMBIGUOUS -> 3;
                        case INFERRED -> 2;
                        case EXTRACTED -> 1;
                    };
            List<String> why = new ArrayList<>();
            if (!a.fileType().equals(b.fileType())) {
                score += 2;
                why.add(a.fileType() + " → " + b.fileType());
            }
            if (!top(a.sourceFile()).equals(top(b.sourceFile()))) {
                score += 2;
                why.add("crosses " + top(a.sourceFile()) + "/ → " + top(b.sourceFile()) + "/");
            }
            int ca = graph.community(a.id());
            if (ca >= 0 && ca != graph.community(b.id())) {
                score += 1;
                why.add("bridges communities");
            }
            int da = graph.degree(a.id());
            int db = graph.degree(b.id());
            if (Math.min(da, db) <= 2 && Math.max(da, db) >= 5) {
                score += 1;
                why.add("peripheral node reaches a hub");
            }
            if (!why.isEmpty()) out.add(new Surprise(e, score, String.join("; ", why)));
        }
        out.sort(Comparator.comparingInt((Surprise s) -> -s.score())
                .thenComparing(s -> s.edge().source())
                .thenComparing(s -> s.edge().target()));
        return out.subList(0, Math.min(topN, out.size()));
    }

    /** A file node, a concept stub, or a method that only its owner touches. */
    static boolean isStructural(CodeGraph graph, GraphNode n) {
        if (n.fileType().equals("concept")) return true;
        if (!n.sourceFile().isEmpty() && n.id().equals(GraphIds.file(n.sourceFile()))) return true;
        String label = n.label();
        if (label.startsWith(".") && label.endsWith("()")) return true;
        return label.endsWith("()") && graph.degree(n.id()) <= 1;
    }

    private static String top(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String root : List.of("src/main/", "src/test/")) {
            if (p.startsWith(root))
                return p.substring(0, p.indexOf('/', root.length()) < 0 ? p.length() : p.indexOf('/', root.length()));
        }
        int slash = p.indexOf('/');
        return slash < 0 ? "." : p.substring(0, slash);
    }
}
