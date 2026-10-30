// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The merged graph. Nodes are unique by id and edges by {@code (source, target, relation)}; an
 * edge whose endpoint no fragment declares is dropped. Traversal treats the graph as undirected
 * (graphify's model) while every edge keeps its true direction. Communities are assigned after
 * construction by {@link Communities}.
 */
public final class CodeGraph {

    private final Map<String, GraphNode> nodes;
    private final List<GraphEdge> edges;
    private final Map<String, Set<String>> neighbors = new HashMap<>();
    private final Map<String, List<GraphEdge>> incident = new HashMap<>();
    private final Map<String, Integer> community = new HashMap<>();
    private final Map<Integer, String> communityNames = new HashMap<>();
    private Modules modules = Modules.NONE;
    private List<StaleRef> stale = List.of();

    private CodeGraph(Map<String, GraphNode> nodes, List<GraphEdge> edges) {
        this.nodes = nodes;
        this.edges = edges;
        for (GraphEdge e : edges) {
            if (!e.source().equals(e.target())) {
                neighbors
                        .computeIfAbsent(e.source(), k -> new LinkedHashSet<>())
                        .add(e.target());
                neighbors
                        .computeIfAbsent(e.target(), k -> new LinkedHashSet<>())
                        .add(e.source());
            }
            incident.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
            if (!e.source().equals(e.target())) {
                incident.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e);
            }
        }
    }

    /**
     * One graph from {@code fragments}. A node declared twice keeps the copy that has a source file
     * and line, with the other's extra fields added: the source scan's span and the class file's
     * signatures land on one node.
     */
    public static CodeGraph merge(Collection<Fragment> fragments) {
        Map<String, GraphNode> nodes = mergeNodes(fragments);
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        for (Fragment f : fragments) {
            for (GraphEdge e : f.edges()) {
                if (!nodes.containsKey(e.source()) || !nodes.containsKey(e.target())) continue;
                edges.merge(e.source() + '\u0000' + e.target() + '\u0000' + e.relation(), e, GraphEdge::withSitesOf);
            }
        }
        return new CodeGraph(nodes, new ArrayList<>(edges.values()));
    }

    /** Every fragment's nodes by id, duplicates combined as {@link #merge} combines them. */
    static Map<String, GraphNode> mergeNodes(Collection<Fragment> fragments) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        for (Fragment f : fragments) {
            for (GraphNode n : f.nodes()) {
                GraphNode had = nodes.get(n.id());
                nodes.put(n.id(), had == null ? n : combine(had, n));
            }
        }
        return nodes;
    }

    private static GraphNode combine(GraphNode had, GraphNode n) {
        boolean takeNew = had.sourceFile().isEmpty() && !n.sourceFile().isEmpty()
                || had.sourceLocation().isEmpty()
                        && !n.sourceLocation().isEmpty()
                        && had.sourceFile().equals(n.sourceFile());
        GraphNode base = takeNew ? n : had;
        GraphNode other = takeNew ? had : n;
        if (other.extra().isEmpty()) return base;
        Map<String, Object> extra = new LinkedHashMap<>(other.extra());
        extra.putAll(base.extra());
        Map<String, Object> facts = Decl.merge(Decl.of(base), Decl.of(other));
        if (!facts.isEmpty()) extra.put(Decl.KEY, facts);
        return new GraphNode(base.id(), base.label(), base.fileType(), base.sourceFile(), base.sourceLocation(), extra);
    }

    /** A graph read back from {@code graph.json}, communities included. */
    static CodeGraph of(
            List<GraphNode> nodes, List<GraphEdge> edges, Map<String, Integer> community, Map<Integer, String> names) {
        CodeGraph g = merge(List.of(new Fragment(nodes, edges)));
        g.community.putAll(community);
        g.communityNames.putAll(names);
        return g;
    }

    public Collection<GraphNode> nodes() {
        return nodes.values();
    }

    public List<GraphEdge> edges() {
        return edges;
    }

    public @Nullable GraphNode node(String id) {
        return nodes.get(id);
    }

    public boolean has(String id) {
        return nodes.containsKey(id);
    }

    public int size() {
        return nodes.size();
    }

    /**
     * The name an answer shows for {@code id}: a member as {@code Outer.Inner.member()}, a nested type
     * as {@code Outer.Inner}, anything else as its label. A bare {@code .member()} leaves the reader
     * to guess the class from the file, and a nested type's member then reads as the outer class's.
     */
    public String display(String id) {
        GraphNode n = nodes.get(id);
        if (n == null) return id;
        if (n.label().startsWith(".")) {
            GraphNode owner = owner(id);
            return owner == null ? n.label() : display(owner.id()) + n.label();
        }
        if (n.extra().get(Linker.QUALIFIED_NAME) instanceof String q && Decl.isType(n)) {
            String simple = q.substring(q.lastIndexOf('.') + 1).replace('$', '.');
            if (simple.endsWith(n.label())) return simple;
        }
        return n.label();
    }

    /** The type that declares member {@code id}: the source of its {@code method} edge. */
    public @Nullable GraphNode owner(String id) {
        for (GraphEdge e : incident(id)) {
            if (e.target().equals(id) && e.relation().equals(Relations.METHOD)) {
                GraphNode owner = nodes.get(e.source());
                if (owner != null && !owner.label().startsWith(".")) return owner;
            }
        }
        return null;
    }

    /** Distinct neighbours of {@code id}, in either direction. */
    public Set<String> neighbors(String id) {
        return neighbors.getOrDefault(id, Set.of());
    }

    /** Distinct neighbour count: graphify's degree on its simple undirected graph. */
    public int degree(String id) {
        return neighbors(id).size();
    }

    /** Every edge touching {@code id}, either end. */
    public List<GraphEdge> incident(String id) {
        return incident.getOrDefault(id, List.of());
    }

    /** The edges between {@code a} and {@code b}, either direction. */
    public List<GraphEdge> between(String a, String b) {
        List<GraphEdge> out = new ArrayList<>();
        for (GraphEdge e : incident(a)) {
            if ((e.source().equals(a) && e.target().equals(b))
                    || (e.source().equals(b) && e.target().equals(a))) {
                out.add(e);
            }
        }
        return out;
    }

    /** The community of {@code id}, {@code -1} before {@link Communities#assign} ran. */
    public int community(String id) {
        return community.getOrDefault(id, -1);
    }

    public @Nullable String communityName(int id) {
        return communityNames.get(id);
    }

    /** Community id → member ids, members in graph order. */
    public Map<Integer, List<String>> communities() {
        Map<Integer, List<String>> out = new TreeMap<>();
        for (String id : nodes.keySet()) {
            int c = community(id);
            if (c >= 0) out.computeIfAbsent(c, k -> new ArrayList<>()).add(id);
        }
        return out;
    }

    /** The workspace's modules, {@link Modules#NONE} until a build or a reader sets them. */
    public Modules modules() {
        return modules;
    }

    public void modules(Modules modules) {
        this.modules = modules;
    }

    /** Document references that name nothing, by file and line. */
    public List<StaleRef> stale() {
        return stale;
    }

    public void stale(List<StaleRef> stale) {
        this.stale = List.copyOf(stale);
    }

    void assignCommunities(Map<String, Integer> assignment, Map<Integer, String> names) {
        community.clear();
        community.putAll(assignment);
        communityNames.clear();
        communityNames.putAll(names);
    }
}
