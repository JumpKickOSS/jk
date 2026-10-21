// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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

    /**
     * Files that import each other in a ring, smallest rings first: every strongly connected set of
     * two or more files over {@code imports} and {@code imports_from} edges, each a sorted path list.
     */
    public static List<List<String>> importCycles(CodeGraph graph, int limit) {
        Map<String, Set<String>> deps = new TreeMap<>();
        for (GraphEdge e : graph.edges()) {
            if (!e.relation().equals(Relations.IMPORTS) && !e.relation().equals(Relations.IMPORTS_FROM)) continue;
            GraphNode from = graph.node(e.source());
            GraphNode to = graph.node(e.target());
            if (from == null
                    || to == null
                    || from.sourceFile().isEmpty()
                    || to.sourceFile().isEmpty()) continue;
            if (from.sourceFile().equals(to.sourceFile())) continue;
            deps.computeIfAbsent(from.sourceFile(), k -> new TreeSet<>()).add(to.sourceFile());
        }
        List<List<String>> cycles = new ArrayList<>();
        new Tarjan(deps, cycles).run();
        cycles.sort(Comparator.comparingInt((List<String> c) -> c.size()).thenComparing(c -> c.getFirst()));
        return cycles.subList(0, Math.min(limit, cycles.size()));
    }

    /** Tarjan's strongly connected components, iterative so a long import chain cannot overflow the stack. */
    private static final class Tarjan {
        private final Map<String, Set<String>> deps;
        private final List<List<String>> out;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> low = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private int next;

        Tarjan(Map<String, Set<String>> deps, List<List<String>> out) {
            this.deps = deps;
            this.out = out;
        }

        void run() {
            for (String v : deps.keySet()) {
                if (!index.containsKey(v)) visit(v);
            }
        }

        private void visit(String root) {
            Deque<Map.Entry<String, Iterator<String>>> work = new ArrayDeque<>();
            enter(root, work);
            while (!work.isEmpty()) {
                Map.Entry<String, Iterator<String>> top = work.peek();
                String v = top.getKey();
                if (top.getValue().hasNext()) {
                    String w = top.getValue().next();
                    if (!index.containsKey(w)) {
                        enter(w, work);
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low(v), idx(w)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    String parent = work.peek().getKey();
                    low.put(parent, Math.min(low(parent), low(v)));
                }
                if (low(v) == idx(v)) {
                    List<String> component = new ArrayList<>();
                    String w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        component.add(w);
                    } while (!w.equals(v));
                    if (component.size() > 1) {
                        component.sort(null);
                        out.add(component);
                    }
                }
            }
        }

        private int low(String v) {
            return low.getOrDefault(v, 0);
        }

        private int idx(String v) {
            return index.getOrDefault(v, 0);
        }

        private void enter(String v, Deque<Map.Entry<String, Iterator<String>>> work) {
            index.put(v, next);
            low.put(v, next);
            next++;
            stack.push(v);
            onStack.add(v);
            work.push(Map.entry(v, deps.getOrDefault(v, Set.of()).iterator()));
        }
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
