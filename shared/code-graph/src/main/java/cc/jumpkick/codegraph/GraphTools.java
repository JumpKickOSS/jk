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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The read tools over one graph: {@code neighbors}, {@code callers} and {@code path}. Each names a
 * symbol {@code Owner.member()} and places it at {@code file:line}. A name resolves by exact id,
 * then exact label, then the best search hit; a label several files declare is refused with the
 * candidates.
 */
public final class GraphTools {

    /** The most call-site lines one edge quotes; the rest stay line numbers after the file. */
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
            for (String id : exact.subList(0, Math.min(exact.size(), 10))) lines.add("  " + id + where(id));
            return new Resolved(null, String.join("\n", lines));
        }
        List<String> members = members(wanted);
        if (members.size() == 1) return new Resolved(members.getFirst(), null);
        if (members.size() > 1) {
            List<String> lines = new ArrayList<>();
            lines.add("'" + wanted + "' names " + members.size() + " members; pass one id:");
            for (String id : members.subList(0, Math.min(members.size(), 10))) lines.add("  " + id + where(id));
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
            lines.add("Neighbors of " + GraphQuery.clean(graph.display(id)) + (ids.size() == 1 ? "" : where(id)) + ":");
            lines.addAll(edgeLines(id, relationFilter.toLowerCase(Locale.ROOT)));
        }
        return Budget.lines(lines, budget, "Narrow with --relation, or read one symbol with jk graph read");
    }

    /**
     * One method that reaches the subject: {@code hop} calls away, calling {@code callee} at {@code
     * edge}, or calling {@code through}, a supertype's method {@code callee} overrides, which may run it.
     */
    private record Caller(
            String id, int hop, String callee, @Nullable String through, GraphEdge edge) {}

    /**
     * Who calls a method, answered so it can be acted on without a search to check it: each caller
     * named {@code Owner.member()} with its call sites' {@code file:line}, main sources before
     * tests, counted, and said to be complete. Every overload of a {@code Type.member} address
     * answers. {@code depth} above 1 also lists the callers' callers, each with the method it
     * calls on the way, up to {@code depth} hops; nothing is listed twice. A call to a supertype's
     * method that a method overrides may run it, so it counts, marked with the method it names. The
     * walk follows overloads, not names: a call reaches on only through the overload it calls, as
     * the compiled classes record it.
     */
    public String callers(String name, int depth, int budget) {
        String wanted = name.strip();
        List<String> targets = graph.has(wanted) ? List.of(wanted) : members(wanted);
        if (targets.isEmpty()) return noMethod(wanted);
        Map<String, Caller> found = new LinkedHashMap<>();
        Map<String, Set<String>> reached = new HashMap<>();
        Map<String, Set<String>> frontier = new LinkedHashMap<>();
        for (String t : targets) {
            frontier.put(t, Set.of(ANY));
            reached.put(t, new TreeSet<>(Set.of(ANY)));
        }
        for (int hop = 1; hop <= depth && !frontier.isEmpty(); hop++) {
            Map<String, Set<String>> next = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> callee : frontier.entrySet()) {
                for (Reach r : reaches(callee.getKey(), callee.getValue())) {
                    if (targets.contains(r.caller()) || !scope.contains(graph.node(r.caller()))) continue;
                    Set<String> added = add(reached, r.caller(), r.overloads());
                    if (added.isEmpty()) continue;
                    found.putIfAbsent(r.caller(), new Caller(r.caller(), hop, callee.getKey(), r.through(), r.edge()));
                    next.computeIfAbsent(r.caller(), k -> new TreeSet<>()).addAll(added);
                }
            }
            frontier = next;
        }
        boolean cut = frontier.entrySet().stream()
                .flatMap(f -> reaches(f.getKey(), f.getValue()).stream())
                .anyMatch(r -> !targets.contains(r.caller())
                        && scope.contains(graph.node(r.caller()))
                        && !covered(reached.get(r.caller()), r.overloads()));
        int deepest = found.values().stream().mapToInt(Caller::hop).max().orElse(0);
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
                + (!transitive
                        ? "."
                        : cut
                                ? ", stopped at " + depth + " hops: more lie beyond, raise max_hops (-L) for them."
                                : ", every one: the chain ends after " + deepest
                                        + (deepest == 1 ? " hop." : " hops.")));
        lines.add("Complete from the compiled classes; no search needed unless a note below says otherwise. Every"
                + " method listed reaches it: a lambda's call counts for its method, and one marked through calls a"
                + " supertype method that dispatches to it.");
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
                String through = c.through() == null ? "" : "  through " + GraphQuery.clean(graph.display(c.through()));
                lines.add("  " + GraphQuery.clean(graph.display(c.id())) + "  "
                        + c.edge().sourceFile() + ":"
                        + String.join(",", c.edge().sites()).replace("L", "") + via + through);
                if (!transitive) lines.addAll(quotes(c.edge()).stream().limit(2).toList());
            }
        }
        if (found.isEmpty()) lines.add("Nothing in the compiled classes calls it.");
        if (!transitive) {
            long onward = found.keySet().stream()
                    .filter(c -> reaches(c, reached.getOrDefault(c, Set.of(ANY))).stream()
                            .anyMatch(r -> !targets.contains(r.caller()) && scope.contains(graph.node(r.caller()))))
                    .count();
            if (onward > 0) {
                lines.add(
                        onward + (onward == 1 ? " of them is" : " of them are") + " called in turn: transitive=true"
                                + " (-t) lists every method that reaches it in one answer, instead of a callers call per method.");
            }
        }
        return Budget.lines(lines, budget, "Raise the budget, or narrow with scope, modules or source_set");
    }

    /** Any overload: an edge or method whose overload the compiled classes did not say. */
    private static final String ANY = "*";

    /** A method that calls {@code method}'s {@code overloads} or a supertype method they override. */
    private record Reach(
            String caller, Set<String> overloads, @Nullable String through, GraphEdge edge) {}

    /**
     * Who calls {@code overloads} of {@code method}, or of a supertype method they override (the
     * {@code through}), each with the overloads of its own that make the call.
     */
    private List<Reach> reaches(String method, Set<String> overloads) {
        List<Reach> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> called :
                overridden(method, overloads).entrySet()) {
            for (GraphEdge e : graph.incident(called.getKey())) {
                if (!e.target().equals(called.getKey()) || !e.relation().equals(Relations.CALLS)) continue;
                Set<String> from = across(e, called.getValue());
                if (from.isEmpty()) continue;
                String through = called.getKey().equals(method) ? null : called.getKey();
                out.add(new Reach(e.source(), from, through, e));
            }
        }
        return out;
    }

    /**
     * {@code method} with {@code overloads}, then each supertype method they override with the
     * overloads overridden, nearest first: a call to any of them may run one of {@code overloads}.
     */
    private Map<String, Set<String>> overridden(String method, Set<String> overloads) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        out.put(method, overloads);
        List<String> todo = new ArrayList<>(List.of(method));
        for (int i = 0; i < todo.size(); i++) {
            String sub = todo.get(i);
            Set<String> subs = out.getOrDefault(sub, Set.of());
            for (GraphEdge e : graph.incident(sub)) {
                if (!e.source().equals(sub) || !e.relation().equals(Relations.OVERRIDES)) continue;
                Set<String> sup = delegating(e.target(), across(e, subs));
                if (sup.isEmpty()) continue;
                Set<String> had = out.computeIfAbsent(e.target(), k -> new TreeSet<>());
                if (covered(had, sup)) continue;
                had.addAll(sup);
                todo.add(e.target());
            }
        }
        return out;
    }

    /**
     * The overloads at {@code e}'s source that join one of {@code wanted} at its target: every
     * {@code from} of an overload {@code from>to} whose {@code to} is wanted. An edge that names no
     * overloads joins any.
     */
    private static Set<String> across(GraphEdge e, Set<String> wanted) {
        List<String> pairs = e.overloads();
        if (pairs.isEmpty()) return Set.of(ANY);
        Set<String> out = new TreeSet<>();
        for (String pair : pairs) {
            int gt = pair.indexOf('>');
            String to = pair.substring(gt + 1);
            if (wanted.contains(ANY) || to.equals(ANY) || wanted.contains(to)) out.add(pair.substring(0, gt));
        }
        return out;
    }

    /** True when {@code had} already holds every one of {@code overloads}. */
    private static boolean covered(@Nullable Set<String> had, Set<String> overloads) {
        return had != null && (had.contains(ANY) || had.containsAll(overloads));
    }

    /**
     * {@code overloads} of {@code method} and every overload of it that calls one of them, directly or
     * through another: an overload that delegates runs what the one it delegates to runs.
     */
    private Set<String> delegating(String method, Set<String> overloads) {
        GraphNode n = graph.node(method);
        if (overloads.contains(ANY) || n == null || !(n.extra().get(Linker.DELEGATES) instanceof List<?> pairs)) {
            return overloads;
        }
        Set<String> out = new TreeSet<>(overloads);
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Object o : pairs) {
                String pair = String.valueOf(o);
                int gt = pair.indexOf('>');
                String from = pair.substring(0, gt);
                String to = pair.substring(gt + 1);
                if ((out.contains(to) || to.equals(ANY)) && out.add(from)) grew = true;
            }
        }
        return out;
    }

    /** Adds {@code overloads} to {@code method}'s reached set; returns the ones that were new. */
    private Set<String> add(Map<String, Set<String>> reached, String method, Set<String> called) {
        Set<String> overloads = delegating(method, called);
        Set<String> had = reached.computeIfAbsent(method, k -> new TreeSet<>());
        if (covered(had, overloads)) return Set.of();
        if (overloads.contains(ANY)) {
            had.clear();
            had.add(ANY);
            return Set.of(ANY);
        }
        Set<String> added = new TreeSet<>(overloads);
        added.removeAll(had);
        had.addAll(added);
        return added;
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
                            + "'. Retry with --undirected to search ignoring edge direction.";
        }
        int hops = path.size() - 1;
        if (hops > maxHops) return "The shortest path is " + hops + " hops, longer than --max-hops " + maxHops + ".";
        List<String> lines = new ArrayList<>();
        lines.add("Shortest path, " + hops + (hops == 1 ? " hop:" : " hops:"));
        lines.add("  " + GraphQuery.clean(graph.display(path.getFirst())) + where(path.getFirst()));
        for (int i = 0; i < hops; i++) lines.add("  " + segment(path.get(i), path.get(i + 1)));
        return String.join("\n", lines);
    }

    /** One hop of a path: {@code -rel-> Owner.member()  file:line}, the arrow reversed for an edge walked backwards. */
    private String segment(String u, String v) {
        List<GraphEdge> fwd = new ArrayList<>();
        List<GraphEdge> bwd = new ArrayList<>();
        for (GraphEdge e : graph.between(u, v)) (e.source().equals(u) ? fwd : bwd).add(e);
        List<GraphEdge> datas = fwd.isEmpty() ? bwd : fwd;
        String rel = String.join(
                "/", new TreeSet<>(datas.stream().map(GraphEdge::relation).toList()));
        if (rel.isEmpty()) rel = "related";
        String guessed = String.join(
                ", ",
                new TreeSet<>(datas.stream()
                        .map(GraphEdge::confidence)
                        .filter(c -> c != Confidence.EXTRACTED)
                        .map(c -> c.name().toLowerCase(Locale.ROOT))
                        .toList()));
        return (fwd.isEmpty() ? "<-" + rel + "- " : "-" + rel + "-> ")
                + GraphQuery.clean(graph.display(v))
                + (guessed.isEmpty() ? "" : " (" + guessed + ")")
                + where(v);
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

    private List<String> edgeLines(String id, String relationFilter) {
        List<GraphEdge> edges = graph.incident(id).stream()
                .filter(e -> relationFilter.isEmpty()
                        || e.relation().toLowerCase(Locale.ROOT).contains(relationFilter))
                .filter(e -> scope.contains(graph.node(other(e, id))))
                .sorted(Comparator.comparingInt((GraphEdge e) -> -graph.degree(other(e, id)))
                        .thenComparing(e -> other(e, id)))
                .toList();
        List<String> lines = new ArrayList<>();
        for (GraphEdge e : edges) {
            String arrow = e.source().equals(id) ? "-" + e.relation() + "-> " : "<-" + e.relation() + "- ";
            lines.add("  " + arrow + GraphQuery.clean(graph.display(other(e, id))) + confidence(e) + at(e));
            lines.addAll(quotes(e));
        }
        return lines;
    }

    /**
     * Two spaces and the node's {@code file:line}, or its file alone when it has no line; empty for a
     * node with no source file.
     */
    String where(String id) {
        GraphNode n = graph.node(id);
        if (n == null || n.sourceFile().isEmpty()) return "";
        int line = Decl.line(n);
        return "  " + GraphQuery.clean(n.sourceFile()) + (line > 0 ? ":" + line : "");
    }

    /** Two spaces and where the edge was found, {@code file:21,9}; empty for an edge with no site. */
    static String at(GraphEdge e) {
        if (e.sourceFile().isEmpty() || e.sourceLocation().isEmpty()) return "";
        return "  " + GraphQuery.clean(e.sourceFile()) + ":"
                + GraphQuery.clean(String.join(",", e.sites()).replace("L", ""));
    }

    /** {@code " (inferred)"} or {@code " (ambiguous)"} for an edge not read straight from the code; else empty. */
    static String confidence(GraphEdge e) {
        return e.confidence() == Confidence.EXTRACTED
                ? ""
                : " (" + e.confidence().name().toLowerCase(Locale.ROOT) + ")";
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

    private String label(String id) {
        GraphNode n = graph.node(id);
        return n == null ? id : n.label();
    }

    private static String bare(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }
}
