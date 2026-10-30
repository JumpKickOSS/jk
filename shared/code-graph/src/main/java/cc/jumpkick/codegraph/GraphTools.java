// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The read tools over one graph, each answering in graphify's text so an agent's prompts carry
 * over: {@code get_node}, {@code get_neighbors}, {@code get_community}, {@code god_nodes}, {@code
 * graph_stats}, {@code shortest_path} and the CLI's {@code explain}. A name resolves by exact id,
 * then exact label, then the best search hit; a label several files declare is refused with the
 * candidates.
 */
public final class GraphTools {

    /** The most call-site lines one edge quotes; the rest stay line numbers in {@code at=}. */
    static final int QUOTED_SITES = 4;

    /** The widest quoted call-site line, in characters. */
    static final int QUOTE_WIDTH = 160;

    private final CodeGraph graph;
    private final Scope scope;
    private final Function<String, @Nullable String> files;
    private final Map<String, @Nullable List<String>> read = new HashMap<>();

    public GraphTools(CodeGraph graph) {
        this(graph, Scope.ALL);
    }

    /** Tools whose names resolve, and whose connections and rankings list, inside {@code scope} first. */
    public GraphTools(CodeGraph graph, Scope scope) {
        this(graph, scope, rel -> null);
    }

    /**
     * Tools that also quote each edge's source lines, read through {@code files} (a workspace-relative
     * path to its text, {@code null} when unreadable): an agent edits a call site from the answer
     * without reading the file to find it.
     */
    public GraphTools(CodeGraph graph, Scope scope, Function<String, @Nullable String> files) {
        this.graph = graph;
        this.scope = scope;
        this.files = files;
    }

    /** A resolved node id, or the message explaining why there is none. */
    record Resolved(@Nullable String id, @Nullable String error) {}

    Resolved resolve(String name) {
        String wanted = name.strip();
        if (graph.has(wanted)) return new Resolved(wanted, null);
        String folded = GraphText.fold(wanted);
        List<String> anywhere = graph.nodes().stream()
                .filter(n -> bare(n.normLabel()).equals(bare(folded)))
                .map(GraphNode::id)
                .sorted()
                .toList();
        List<String> scoped =
                anywhere.stream().filter(id -> scope.contains(graph.node(id))).toList();
        List<String> exact = scoped.isEmpty() ? anywhere : scoped;
        if (exact.size() > 1) {
            List<String> types = exact.stream()
                    .filter(id -> !String.valueOf(label(id)).startsWith("."))
                    .toList();
            if (types.size() == 1) return new Resolved(types.getFirst(), null);
        }
        if (exact.size() == 1) return new Resolved(exact.getFirst(), null);
        if (exact.size() > 1) {
            List<String> lines = new ArrayList<>();
            lines.add("'" + wanted + "' matches " + exact.size() + " nodes; pass one id:");
            for (String id : exact.subList(0, Math.min(exact.size(), 10))) {
                GraphNode n = graph.node(id);
                lines.add("  " + id + (n == null ? "" : "  [" + n.sourceFile() + " " + n.sourceLocation() + "]"));
            }
            return new Resolved(null, String.join("\n", lines));
        }
        List<String> members = members(wanted);
        if (members.size() == 1) return new Resolved(members.getFirst(), null);
        if (members.size() > 1) {
            List<String> lines = new ArrayList<>();
            lines.add("'" + wanted + "' names " + members.size() + " members; pass one id:");
            for (String id : members.subList(0, Math.min(members.size(), 10))) {
                GraphNode n = graph.node(id);
                lines.add("  " + id + (n == null ? "" : "  [" + n.sourceFile() + " " + n.sourceLocation() + "]"));
            }
            return new Resolved(null, String.join("\n", lines));
        }
        String missing = missingMember(wanted);
        if (missing != null) return new Resolved(null, missing);
        List<String> ranked = new GraphSearch(graph, scope).rank(wanted);
        if (ranked.isEmpty() && !scope.all()) ranked = new GraphSearch(graph).rank(wanted);
        if (ranked.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("No node matching '" + wanted + "' found.");
            List<String> near = new GraphSearch(graph, scope).nearest(wanted, 5);
            if (!near.isEmpty()) lines.add("Closest names:");
            for (String id : near) {
                GraphNode n = graph.node(id);
                if (n != null)
                    lines.add("  " + GraphQuery.clean(n.label()) + "  " + n.sourceFile() + ":" + Decl.line(n));
            }
            return new Resolved(null, String.join("\n", lines));
        }
        List<String> tokens = GraphText.searchTokens(wanted);
        for (String id : ranked) {
            GraphNode n = graph.node(id);
            if (n != null && GraphText.searchTokens(n.label()).containsAll(tokens)) return new Resolved(id, null);
        }
        return new Resolved(ranked.getFirst(), null);
    }

    public String node(String name) {
        Resolved r = resolve(name);
        if (r.id() == null) return String.valueOf(r.error());
        GraphNode n = node0(r.id());
        List<String> lines = new ArrayList<>();
        lines.add("Node: " + GraphQuery.clean(graph.display(n.id())));
        lines.add("  ID: " + n.id());
        lines.add("  Source: " + n.sourceFile() + " " + n.sourceLocation());
        lines.add("  Type: " + n.fileType());
        lines.add("  Community: " + community(n.id()));
        lines.add("  Degree: " + graph.degree(n.id()));
        Object attrs = n.extra().get("attributes");
        if (attrs != null) lines.add("  Attributes: " + GraphQuery.clean(String.valueOf(attrs)));
        return String.join("\n", lines);
    }

    public String neighbors(String name, String relationFilter, int budget) {
        // Every overload of a Type.member address answers at once: who calls a method rarely means one signature.
        List<String> overloads = members(name.strip());
        List<String> ids;
        if (overloads.size() > 1) {
            ids = overloads;
        } else {
            Resolved r = resolve(name);
            if (r.id() == null) return String.valueOf(r.error());
            ids = List.of(r.id());
        }
        List<String> lines = new ArrayList<>();
        for (String id : ids) {
            GraphNode n = graph.node(id);
            String at = n == null || ids.size() == 1 ? "" : " [" + n.sourceFile() + ":" + Decl.line(n) + "]";
            lines.add("Neighbors of " + GraphQuery.clean(graph.display(id)) + at + ":");
            lines.addAll(edgeLines(id, relationFilter.toLowerCase(Locale.ROOT), Integer.MAX_VALUE));
        }
        return Budget.lines(lines, budget, "Narrow with relation_filter or use get_node for a specific symbol");
    }

    /** One method that reaches the subject: {@code hop} calls away, through {@code callee}, at {@code edge}. */
    private record Caller(String id, int hop, String callee, GraphEdge edge) {}

    /**
     * Who calls a method, answered so it can be acted on without a search to check it: each caller
     * named {@code Owner.member()} with its call sites' {@code file:line}, main sources before
     * tests, counted, and said to be complete. Every overload of a {@code Type.member} address
     * answers. {@code depth} above 1 also lists the callers' callers, each with the method it
     * calls on the way, up to {@code depth} hops; nothing is listed twice.
     */
    public String callers(String name, int depth, int budget) {
        String wanted = name.strip();
        List<String> targets = graph.has(wanted) ? List.of(wanted) : members(wanted);
        if (targets.isEmpty()) return noMethod(wanted);
        Map<String, Caller> found = new LinkedHashMap<>();
        List<String> frontier = targets;
        for (int hop = 1; hop <= depth && !frontier.isEmpty(); hop++) {
            List<String> next = new ArrayList<>();
            for (String callee : frontier) {
                for (GraphEdge e : graph.incident(callee)) {
                    if (!e.target().equals(callee) || !e.relation().equals(Relations.CALLS)) continue;
                    String caller = e.source();
                    if (targets.contains(caller) || found.containsKey(caller) || !scope.contains(graph.node(caller)))
                        continue;
                    found.put(caller, new Caller(caller, hop, callee, e));
                    next.add(caller);
                }
            }
            frontier = next;
        }
        Comparator<Caller> order = Comparator.comparingInt(Caller::hop).thenComparing(c -> graph.display(c.id()));
        List<Caller> main = new ArrayList<>();
        List<Caller> test = new ArrayList<>();
        for (Caller c : found.values()) {
            boolean inTest = graph.modules().sourceSet(c.edge().sourceFile()) == Modules.SourceSet.TEST;
            (inTest ? test : main).add(c);
        }
        main.sort(order);
        test.sort(order);
        boolean transitive = depth > 1;
        String subject =
                graph.display(targets.getFirst()) + (targets.size() > 1 ? " (" + targets.size() + " overloads)" : "");
        List<String> lines = new ArrayList<>();
        lines.add((transitive ? "Every method that reaches " : "Callers of ") + subject + ": " + main.size()
                + " in main sources, " + test.size() + " in tests"
                + (transitive ? ", up to " + depth + " hops." : "."));
        lines.add("Complete for the compiled classes: every call site they record is listed, and a call inside a "
                + "lambda counts for the method holding it, so no search is needed to confirm it. A call made "
                + "through a supertype's method is listed under that method.");
        for (List<Caller> set : List.of(main, test)) {
            if (set.isEmpty()) continue;
            lines.add(set == main ? "main:" : "test:");
            int hop = 0;
            for (Caller c : set) {
                if (transitive && c.hop() != hop) {
                    hop = c.hop();
                    lines.add("  hop " + hop + (hop == 1 ? " (calls it directly):" : ":"));
                }
                String via = transitive && c.hop() > 1 ? "  -> " + GraphQuery.clean(graph.display(c.callee())) : "";
                lines.add("  " + GraphQuery.clean(graph.display(c.id())) + "  "
                        + c.edge().sourceFile() + ":"
                        + String.join(",", c.edge().sites()).replace("L", "") + via);
                if (!transitive) lines.addAll(quotes(c.edge()).stream().limit(2).toList());
            }
        }
        if (found.isEmpty()) lines.add("Nothing in the compiled classes calls it.");
        return Budget.lines(lines, budget, "Raise the budget, or narrow with scope, modules or source_set");
    }

    /**
     * Why {@code address} names no method, with the closest names: an answer an agent acts on without
     * checking must never be about something it did not ask for.
     */
    private String noMethod(String address) {
        String missing = missingMember(address);
        if (missing != null) return missing;
        List<String> lines = new ArrayList<>();
        lines.add("No method '" + address + "'. Pass Type.method.");
        List<String> near = new GraphSearch(graph, scope).nearest(address, 5);
        if (!near.isEmpty()) lines.add("Closest names:");
        for (String id : near) {
            GraphNode n = graph.node(id);
            if (n != null)
                lines.add("  " + GraphQuery.clean(graph.display(id)) + "  " + n.sourceFile() + ":" + Decl.line(n));
        }
        return String.join("\n", lines);
    }

    /**
     * For a {@code Type.member} address whose type the graph has but whose member it does not: the
     * answer saying so, with the type's members; {@code null} otherwise. A search hit on the type
     * would answer a question about the member with the type's connections.
     */
    private @Nullable String missingMember(String address) {
        String wanted = address.endsWith("()") ? address.substring(0, address.length() - 2) : address;
        int dot = wanted.lastIndexOf('.');
        if (dot <= 0 || dot == wanted.length() - 1) return null;
        String owner = wanted.substring(0, dot);
        String typeName = GraphText.fold(owner.substring(owner.lastIndexOf('.') + 1));
        for (GraphNode type : graph.nodes()) {
            if (type.label().startsWith(".") || !bare(type.normLabel()).equals(typeName)) continue;
            TreeSet<String> names = new TreeSet<>();
            for (GraphEdge e : graph.incident(type.id())) {
                if (e.source().equals(type.id()) && e.relation().equals(Relations.METHOD))
                    names.add(bare(label(e.target())));
            }
            return "'" + owner + "' (" + type.sourceFile() + ") declares no member '" + wanted.substring(dot + 1) + "'"
                    + (names.isEmpty() ? "." : "; its members: " + String.join(", ", names));
        }
        return null;
    }

    /**
     * The member nodes a {@code Type.member} address names: the {@code method} edges of every type
     * whose label is the address's last type segment (qualifiers before it must match the end of
     * the type's source path), to a node labelled {@code member}. Empty when the address has no dot.
     */
    List<String> members(String address) {
        String wanted = address.endsWith("()") ? address.substring(0, address.length() - 2) : address;
        int dot = wanted.lastIndexOf('.');
        if (dot <= 0 || dot == wanted.length() - 1) return List.of();
        String owner = wanted.substring(0, dot);
        String member = GraphText.fold(wanted.substring(dot + 1));
        String typeName = GraphText.fold(owner.substring(owner.lastIndexOf('.') + 1));
        String qualifier =
                owner.contains(".") ? owner.substring(0, owner.lastIndexOf('.')).replace('.', '/') : "";
        List<String> out = new ArrayList<>();
        for (GraphNode type : graph.nodes()) {
            if (type.label().startsWith(".") || !bare(type.normLabel()).equals(typeName)) continue;
            if (!qualifier.isEmpty() && !type.sourceFile().replace('\\', '/').contains(qualifier)) continue;
            for (GraphEdge e : graph.incident(type.id())) {
                if (!e.source().equals(type.id()) || !e.relation().equals(Relations.METHOD)) continue;
                GraphNode m = graph.node(e.target());
                if (m != null && bare(m.normLabel()).equals(member)) out.add(m.id());
            }
        }
        List<String> scoped = out.stream()
                .filter(id -> scope.contains(graph.node(id)))
                .sorted()
                .toList();
        return scoped.isEmpty() ? out.stream().sorted().toList() : scoped;
    }

    public String explain(String name, boolean all) {
        Resolved r = resolve(name);
        if (r.id() == null) return String.valueOf(r.error());
        GraphNode n = node0(r.id());
        List<String> lines = new ArrayList<>();
        lines.add("Node: " + GraphQuery.clean(graph.display(n.id())));
        lines.add("  Source:    " + n.sourceFile() + " " + n.sourceLocation());
        lines.add("  Community: " + community(n.id()));
        lines.add("  Degree:    " + graph.degree(n.id()));
        List<String> edges = edgeLines(n.id(), "", all ? Integer.MAX_VALUE : 20);
        lines.add("");
        lines.add("Connections (" + graph.incident(n.id()).size() + "):");
        lines.addAll(edges);
        return String.join("\n", lines);
    }

    public String community(int id, int budget) {
        List<String> members = graph.communities().getOrDefault(id, List.of());
        if (members.isEmpty()) return "Community " + id + " not found.";
        String name = graph.communityName(id);
        String header = "Community " + id + (name == null ? "" : " — " + GraphQuery.clean(name));
        List<String> lines = new ArrayList<>();
        lines.add(header + " (" + members.size() + " nodes):");
        for (String m : members)
            lines.add("  " + GraphQuery.clean(graph.display(m)) + " [" + node0(m).sourceFile() + "]");
        return Budget.lines(lines, budget, "Raise token_budget or use get_node for specific members");
    }

    public String godNodes(int topN) {
        List<String> lines = new ArrayList<>();
        lines.add("God nodes (most connected):");
        int i = 1;
        for (String id : GraphAnalysis.godNodes(graph, topN, scope)) {
            lines.add("  " + i++ + ". " + GraphQuery.clean(graph.display(id)) + " - " + graph.degree(id) + " edges");
        }
        return String.join("\n", lines);
    }

    public String stats(@Nullable String builtFrom) {
        Map<Confidence, Integer> counts = new HashMap<>();
        graph.edges().forEach(e -> counts.merge(e.confidence(), 1, Integer::sum));
        double total = Math.max(graph.edges().size(), 1);
        StringBuilder out = new StringBuilder();
        out.append("Nodes: ").append(graph.size()).append('\n');
        out.append("Edges: ").append(graph.edges().size()).append('\n');
        out.append("Communities: ").append(graph.communities().size()).append('\n');
        for (Confidence c : Confidence.values()) {
            out.append(c)
                    .append(": ")
                    .append(Math.round(counts.getOrDefault(c, 0) / total * 100))
                    .append("%\n");
        }
        if (builtFrom != null)
            out.append("Built from commit: ").append(builtFrom).append('\n');
        return out.toString();
    }

    /** The fewest hops from {@code source} to {@code target}; directed unless {@code undirected}. */
    public String shortestPath(String source, String target, int maxHops, boolean undirected) {
        Resolved from = resolve(source);
        if (from.id() == null) return String.valueOf(from.error());
        Resolved to = resolve(target);
        if (to.id() == null) return String.valueOf(to.error());
        if (from.id().equals(to.id())) {
            return "'" + source + "' and '" + target + "' both resolved to the same node '" + from.id()
                    + "'. Use a more specific label or the exact node ID.";
        }
        List<String> path = bfsPath(from.id(), to.id(), undirected);
        if (path.isEmpty()) {
            return undirected
                    ? "No path found between '" + graph.display(from.id()) + "' and '" + graph.display(to.id()) + "'."
                    : "No directed path found between '" + graph.display(from.id()) + "' and '" + graph.display(to.id())
                            + "'. Retry with undirected=true to search ignoring edge direction.";
        }
        int hops = path.size() - 1;
        if (hops > maxHops) return "Path exceeds max_hops=" + maxHops + " (" + hops + " hops found).";
        StringBuilder out = new StringBuilder(graph.display(path.getFirst()));
        for (int i = 0; i < hops; i++) out.append(' ').append(segment(path.get(i), path.get(i + 1)));
        return "Shortest path (" + hops + " hops):\n  " + out;
    }

    private String segment(String u, String v) {
        List<GraphEdge> fwd = new ArrayList<>();
        List<GraphEdge> bwd = new ArrayList<>();
        for (GraphEdge e : graph.between(u, v)) (e.source().equals(u) ? fwd : bwd).add(e);
        List<GraphEdge> datas = fwd.isEmpty() ? bwd : fwd;
        String rel = String.join(
                "/", new TreeSet<>(datas.stream().map(GraphEdge::relation).toList()));
        String conf = String.join(
                "/",
                new TreeSet<>(datas.stream().map(e -> e.confidence().name()).toList()));
        String tag = (rel.isEmpty() ? "related" : rel) + (conf.isEmpty() ? "" : " [" + conf + "]");
        return fwd.isEmpty() ? "<--" + tag + "-- " + graph.display(v) : "--" + tag + "--> " + graph.display(v);
    }

    private List<String> bfsPath(String from, String to, boolean undirected) {
        Map<String, String> parent = new HashMap<>();
        parent.put(from, from);
        Deque<String> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            String n = queue.poll();
            if (n.equals(to)) break;
            List<String> next = graph.incident(n).stream()
                    .filter(e -> undirected || e.source().equals(n))
                    .map(e -> e.source().equals(n) ? e.target() : e.source())
                    .sorted()
                    .toList();
            for (String nb : next) {
                if (parent.putIfAbsent(nb, n) == null) queue.add(nb);
            }
        }
        if (!parent.containsKey(to)) return List.of();
        List<String> path = new ArrayList<>();
        for (String n = to; !n.equals(from); n = parent.getOrDefault(n, from)) path.addFirst(n);
        path.addFirst(from);
        return path;
    }

    private List<String> edgeLines(String id, String relationFilter, int limit) {
        List<GraphEdge> edges = graph.incident(id).stream()
                .filter(e -> relationFilter.isEmpty()
                        || e.relation().toLowerCase(Locale.ROOT).contains(relationFilter))
                .filter(e -> scope.contains(graph.node(other(e, id))))
                .sorted(Comparator.comparingInt((GraphEdge e) -> -graph.degree(other(e, id)))
                        .thenComparing(e -> other(e, id)))
                .toList();
        List<String> lines = new ArrayList<>();
        for (GraphEdge e : edges.subList(0, Math.min(limit, edges.size()))) {
            String arrow = e.source().equals(id) ? "-->" : "<--";
            String at = e.sourceLocation().isEmpty() ? "" : " at=" + e.sourceFile() + ":" + String.join(",", e.sites());
            lines.add("  " + arrow + " " + GraphQuery.clean(graph.display(other(e, id))) + " [" + e.relation() + "] ["
                    + e.confidence() + "]" + at);
            lines.addAll(quotes(e));
        }
        if (edges.size() > limit) lines.add("  ... " + (edges.size() - limit) + " more (use --all)");
        return lines;
    }

    /** The edge's first {@link #QUOTED_SITES} source lines, {@code L<n>| text}; none when the file is unreadable. */
    private List<String> quotes(GraphEdge e) {
        if (e.sourceFile().isEmpty()) return List.of();
        List<String> text = read.computeIfAbsent(e.sourceFile(), f -> {
            String body = files.apply(f);
            return body == null ? null : body.lines().toList();
        });
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String site : e.sites()) {
            if (out.size() == QUOTED_SITES) break;
            int n = lineNumber(site);
            if (n < 1 || n > text.size()) continue;
            String line = text.get(n - 1).strip();
            if (line.length() > QUOTE_WIDTH) line = line.substring(0, QUOTE_WIDTH) + "…";
            out.add("      L" + n + "| " + line);
        }
        return out;
    }

    private static int lineNumber(String site) {
        String digits = site.startsWith("L") ? site.substring(1) : site;
        int dash = digits.indexOf('-');
        if (dash > 0) digits = digits.substring(0, dash);
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException notALine) {
            return -1;
        }
    }

    private static String other(GraphEdge e, String id) {
        return e.source().equals(id) ? e.target() : e.source();
    }

    private String community(String id) {
        int c = graph.community(id);
        if (c < 0) return "";
        String name = graph.communityName(c);
        return name == null ? String.valueOf(c) : GraphQuery.clean(name);
    }

    private GraphNode node0(String id) {
        GraphNode n = graph.node(id);
        if (n == null) throw new IllegalStateException("no node " + id);
        return n;
    }

    private String label(String id) {
        GraphNode n = graph.node(id);
        return n == null ? id : n.label();
    }

    private static String bare(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }
}
