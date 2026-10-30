// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk graph query}: seed from {@link GraphSearch}, walk {@code depth} hops breadth- or
 * depth-first (hubs above the 99th degree percentile, at least 50, are not expanded unless seeded),
 * then render seeds first and the rest nearest-first, cut to {@code budget} tokens at three
 * characters per token. A symbol reads {@code Owner.member()  path/File.java:12}, a connection
 * {@code a -calls-> b  path/File.java:21,9}.
 */
public final class GraphQuery {

    /** Traversal order. */
    public enum Mode {
        BFS,
        DFS
    }

    static final String NO_MATCH = "No matching nodes found.";

    private static final int MAX_LABEL = 256;

    /** A walk an ambiguous inferred context narrows below this many nodes runs again unfiltered. */
    static final int MIN_FILTERED = 10;

    /** Contexts whose hint words are as often plain English as a question about the relation. */
    private static final Set<String> AMBIGUOUS = Set.of("field", "parameter_type", "return_type", "generic_arg");

    /** Edge contexts a question's words imply, in the order the header names them. */
    private static final List<Map.Entry<String, List<String>>> CONTEXT_HINTS = List.of(
            Map.entry("call", List.of("call", "calls", "called", "caller", "callers", "invoke", "invokes", "invoked")),
            Map.entry("import", List.of("import", "imports", "imported", "module", "modules")),
            Map.entry("field", List.of("field", "fields", "member", "members", "property", "properties")),
            Map.entry("parameter_type", List.of("parameter", "parameters", "param", "params", "argument", "arguments")),
            Map.entry("return_type", List.of("return", "returns", "returned")),
            Map.entry("generic_arg", List.of("generic", "generics", "template", "templates")));

    private final CodeGraph graph;
    private final Scope scope;
    private final Map<String, List<String>> aliases;
    private final GraphTools tools;

    public GraphQuery(CodeGraph graph) {
        this(graph, Scope.ALL, Map.of());
    }

    /**
     * Queries that seed and walk only inside {@code scope}; a question holding one of a term's
     * {@code aliases} (term → outside words, {@code [graph.aliases]}) also seeds on the term.
     */
    public GraphQuery(CodeGraph graph, Scope scope, Map<String, List<String>> aliases) {
        this.graph = graph;
        this.scope = scope;
        this.aliases = aliases;
        this.tools = new GraphTools(graph, scope);
    }

    /** The budgeted answer to {@code question}; {@code contexts} empty infers them from its words. */
    public String query(String question, Mode mode, int depth, int budget, List<String> contexts) {
        GraphSearch search = new GraphSearch(graph, scope);
        List<String> seeds = search.seeds(withAliases(question));
        Map<String, Integer> relevant = new HashMap<>();
        for (String id : search.rank(withAliases(question))) relevant.putIfAbsent(id, relevant.size());
        if (seeds.isEmpty()) return noMatch(search.nearest(question, 8));
        List<String> filters = contexts.isEmpty() ? inferContexts(question) : contexts;
        String source = contexts.isEmpty() ? "read from the question" : "as asked";
        Walk walk = mode == Mode.DFS ? dfs(seeds, depth, filters) : bfs(seeds, depth, filters);
        if (contexts.isEmpty()
                && AMBIGUOUS.containsAll(filters)
                && !filters.isEmpty()
                && walk.nodes().size() < MIN_FILTERED) {
            // "returns", "member", "template" are ordinary English as often as they ask for a
            // relation ("which endpoint returns …"); a context read off one that starves the walk
            // was the wrong guess, so the walk runs unfiltered. "calls" and "imports" always mean it.
            Walk open = mode == Mode.DFS ? dfs(seeds, depth, List.of()) : bfs(seeds, depth, List.of());
            if (open.nodes().size() > walk.nodes().size()) {
                String dropped = "every edge: only " + String.join(", ", filters)
                        + " edges, read from the question, left " + walk.nodes().size() + " symbols";
                return header(seeds, mode, depth, open, dropped) + "\n\n"
                        + render(open, seeds, relevant, budget, List.of());
            }
        }
        String following = filters.isEmpty() ? null : String.join(", ", filters) + " edges (" + source + ")";
        return header(seeds, mode, depth, walk, following) + "\n\n" + render(walk, seeds, relevant, budget, filters);
    }

    /**
     * {@code From A, B: 19 symbols within 2 hops, breadth-first, following call edges (read from the
     * question).}
     */
    private String header(List<String> seeds, Mode mode, int depth, Walk walk, @Nullable String following) {
        return "From " + String.join(", ", seeds.stream().map(this::label).toList()) + ": "
                + walk.nodes().size()
                + (walk.nodes().size() == 1 ? " symbol" : " symbols") + " within " + depth
                + (depth == 1 ? " hop, " : " hops, ") + (mode == Mode.DFS ? "depth-first" : "breadth-first")
                + (following == null ? "" : ", following " + following) + ".";
    }

    record Walk(Set<String> nodes, List<String[]> edges) {}

    Walk bfs(List<String> seeds, int depth, List<String> filters) {
        int hub = hubThreshold();
        Set<String> seedSet = new HashSet<>(seeds);
        Set<String> visited = new LinkedHashSet<>(seeds);
        Set<String> frontier = new LinkedHashSet<>(seeds);
        List<String[]> edges = new ArrayList<>();
        for (int d = 0; d < depth; d++) {
            Set<String> next = new LinkedHashSet<>();
            for (String n : frontier) {
                if (!seedSet.contains(n) && graph.degree(n) >= hub) continue;
                for (String nb : neighbors(n, filters)) {
                    if (!visited.contains(nb) && next.add(nb)) edges.add(new String[] {n, nb});
                }
            }
            visited.addAll(next);
            frontier = next;
        }
        completeInduced(visited, edges, filters);
        return new Walk(visited, edges);
    }

    Walk dfs(List<String> seeds, int depth, List<String> filters) {
        int hub = hubThreshold();
        Set<String> seedSet = new HashSet<>(seeds);
        Set<String> visited = new LinkedHashSet<>();
        List<String[]> edges = new ArrayList<>();
        Deque<Map.Entry<String, Integer>> stack = new ArrayDeque<>();
        for (int i = seeds.size() - 1; i >= 0; i--) stack.push(Map.entry(seeds.get(i), 0));
        while (!stack.isEmpty()) {
            Map.Entry<String, Integer> top = stack.pop();
            String n = top.getKey();
            if (visited.contains(n) || top.getValue() > depth) continue;
            visited.add(n);
            if (!seedSet.contains(n) && graph.degree(n) >= hub) continue;
            for (String nb : neighbors(n, filters)) {
                if (!visited.contains(nb)) {
                    stack.push(Map.entry(nb, top.getValue() + 1));
                    edges.add(new String[] {n, nb});
                }
            }
        }
        completeInduced(visited, edges, filters);
        return new Walk(visited, edges);
    }

    /**
     * The walk as lines, cut to {@code budget}: the seeds, then the nodes the question's own words
     * match, best match first, then the rest nearest first and by degree. When the budget cuts, what it
     * cuts is the neighbourhood the question did not ask about.
     */
    private String render(
            Walk walk, List<String> seeds, Map<String, Integer> relevant, int budget, List<String> filters) {
        List<String> seedHits = seeds.stream().filter(walk.nodes()::contains).toList();
        Map<String, Integer> dist = hops(seedHits, walk.nodes());
        List<String> ordered = new ArrayList<>(seedHits);
        walk.nodes().stream()
                .filter(n -> !seedHits.contains(n))
                .sorted(Comparator.comparingInt((String n) -> relevant.getOrDefault(n, Integer.MAX_VALUE))
                        .thenComparingInt(n -> dist.getOrDefault(n, Integer.MAX_VALUE))
                        .thenComparingInt(n -> -graph.degree(n))
                        .thenComparing(n -> n))
                .forEach(ordered::add);
        List<String> nodes = new ArrayList<>();
        for (String id : ordered) nodes.add(nodeLine(id));
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) rank.put(ordered.get(i), i);
        Set<String> drawn = new HashSet<>();
        List<GraphEdge> drawnEdges = new ArrayList<>();
        for (String[] uv : walk.edges()) {
            if (!drawn.add(pairKey(uv[0], uv[1]))) continue;
            for (GraphEdge e : graph.between(uv[0], uv[1])) {
                if (filters.isEmpty() || filters.contains(e.context())) drawnEdges.add(e);
            }
        }
        // An edge ranks by its nearer end, so a budget cut keeps the seeds' own connections.
        List<String> edges = new ArrayList<>();
        drawnEdges.stream()
                .sorted(Comparator.comparingInt((GraphEdge e) -> Math.min(
                                rank.getOrDefault(e.source(), Integer.MAX_VALUE),
                                rank.getOrDefault(e.target(), Integer.MAX_VALUE)))
                        .thenComparingInt(e -> Math.max(
                                rank.getOrDefault(e.source(), Integer.MAX_VALUE),
                                rank.getOrDefault(e.target(), Integer.MAX_VALUE))))
                .forEach(e -> edges.add(edgeLine(e)));
        return Budget.cut(nodes, edges, seedHits.size(), budget);
    }

    /** {@code Owner.member()  path/File.java:12}. */
    private String nodeLine(String id) {
        return clean(graph.display(id)) + tools.where(id);
    }

    /**
     * {@code a -references-> b (field, inferred)  path/File.java:21,9}: the context only when neither
     * the relation nor an end already says it, the confidence only when the edge was not read from
     * the code.
     */
    private String edgeLine(GraphEdge e) {
        List<String> notes = new ArrayList<>();
        String context = e.context();
        if (context != null
                && !context.isBlank()
                && !e.relation().startsWith(context)
                && !label(e.source()).contains(context)
                && !label(e.target()).contains(context)) notes.add(clean(context));
        if (e.confidence() != Confidence.EXTRACTED)
            notes.add(e.confidence().name().toLowerCase(Locale.ROOT));
        return clean(label(e.source())) + " -" + clean(e.relation()) + "-> " + clean(label(e.target()))
                + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")") + GraphTools.at(e);
    }

    private Map<String, Integer> hops(List<String> seeds, Set<String> nodes) {
        Map<String, Integer> dist = new HashMap<>();
        seeds.forEach(s -> dist.put(s, 0));
        List<String> frontier = seeds;
        for (int hop = 1; !frontier.isEmpty(); hop++) {
            List<String> next = new ArrayList<>();
            for (String n : frontier) {
                for (String nb : graph.neighbors(n)) {
                    if (nodes.contains(nb) && !dist.containsKey(nb)) {
                        dist.put(nb, hop);
                        next.add(nb);
                    }
                }
            }
            frontier = next;
        }
        return dist;
    }

    private void completeInduced(Set<String> visited, List<String[]> edges, List<String> filters) {
        Set<String> seen = new HashSet<>();
        for (String[] uv : edges) seen.add(pairKey(uv[0], uv[1]));
        for (String u : visited.stream().sorted().toList()) {
            for (String v : neighbors(u, filters)) {
                if (visited.contains(v) && seen.add(pairKey(u, v))) edges.add(new String[] {u, v});
            }
        }
    }

    private List<String> neighbors(String id, List<String> filters) {
        if (filters.isEmpty() && scope.all()) return List.copyOf(graph.neighbors(id));
        Set<String> out = new LinkedHashSet<>();
        for (GraphEdge e : graph.incident(id)) {
            if (!filters.isEmpty() && !filters.contains(e.context())) continue;
            String other = e.source().equals(id) ? e.target() : e.source();
            if (scope.contains(graph.node(other))) out.add(other);
        }
        out.remove(id);
        return List.copyOf(out);
    }

    private int hubThreshold() {
        int[] degrees = graph.nodes().stream()
                .mapToInt(n -> graph.degree(n.id()))
                .sorted()
                .toArray();
        return degrees.length == 0
                ? 50
                : Math.max(50, degrees[Math.min(degrees.length - 1, (int) (degrees.length * 0.99))]);
    }

    private String label(String id) {
        return graph.display(id);
    }

    /** {@code question} with every term one of whose aliases it says appended. */
    String withAliases(String question) {
        if (aliases.isEmpty()) return question;
        String words = " " + String.join(" ", GraphText.searchTokens(question)) + " ";
        StringBuilder out = new StringBuilder(question);
        aliases.forEach((term, outside) -> {
            for (String w : outside) {
                String phrase = String.join(" ", GraphText.searchTokens(w));
                if (!phrase.isEmpty() && words.contains(" " + phrase + " ")) {
                    out.append(' ').append(term);
                    break;
                }
            }
        });
        return out.toString();
    }

    /** {@link #NO_MATCH}, with the closest names when there are any. */
    String noMatch(List<String> nearest) {
        if (nearest.isEmpty()) return NO_MATCH;
        List<String> lines = new ArrayList<>();
        lines.add(NO_MATCH + " Closest names:");
        for (String id : nearest) {
            if (graph.has(id)) lines.add("  " + clean(graph.display(id)) + tools.where(id));
        }
        return String.join("\n", lines);
    }

    static List<String> inferContexts(String question) {
        Set<String> words = new HashSet<>();
        for (String w : question.replace('?', ' ').replace(',', ' ').split("\\s+")) {
            words.add(GraphText.fold(w));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> hint : CONTEXT_HINTS) {
            if (hint.getValue().stream().anyMatch(words::contains)) out.add(hint.getKey());
        }
        return out;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + '\u0000' + b : b + '\u0000' + a;
    }

    static String clean(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_LABEL));
        text.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(out::appendCodePoint);
        return out.length() > MAX_LABEL ? out.substring(0, MAX_LABEL) : out.toString();
    }
}
