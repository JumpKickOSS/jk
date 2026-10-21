// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic Louvain modularity clustering. Nodes are visited in id order and ties go to the
 * lowest community, so equal graphs get equal communities. A community holding more than a quarter
 * of a graph of at least ten nodes is clustered again on its own. Ids are assigned largest first,
 * and each community is named after its highest-degree member, as graphify names them without an
 * LLM.
 */
public final class Communities {

    private Communities() {}

    /** Cluster {@code graph} and store the result on it. */
    public static void assign(CodeGraph graph) {
        List<String> ids = graph.nodes().stream().map(GraphNode::id).sorted().toList();
        List<List<String>> groups = new ArrayList<>();
        for (List<String> group : louvain(graph, ids)) groups.addAll(split(graph, group, ids.size()));
        groups.sort(Comparator.comparingInt((List<String> g) -> -g.size()).thenComparing(g -> g.getFirst()));
        Map<String, Integer> assignment = new HashMap<>();
        Map<Integer, String> names = new TreeMap<>();
        for (int c = 0; c < groups.size(); c++) {
            for (String id : groups.get(c)) assignment.put(id, c);
            names.put(c, hubName(graph, groups.get(c), c));
        }
        graph.assignCommunities(assignment, names);
    }

    private static List<List<String>> split(CodeGraph graph, List<String> group, int total) {
        if (total < 10 || group.size() * 4 <= total || group.size() < 10) return List.of(group);
        List<List<String>> parts = louvain(graph, group);
        return parts.size() <= 1 ? List.of(group) : parts;
    }

    /** Louvain over the subgraph {@code members} induces; each part sorted by id. */
    static List<List<String>> louvain(CodeGraph graph, List<String> members) {
        int n = members.size();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(members.get(i), i);
        List<Map<Integer, Double>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new TreeMap<>());
        for (GraphEdge e : graph.edges()) {
            Integer a = index.get(e.source());
            Integer b = index.get(e.target());
            if (a == null || b == null || a.equals(b)) continue;
            double w = e.confidence() == Confidence.AMBIGUOUS ? 0.5 : 1.0;
            adj.get(a).merge(b, w, Math::max);
            adj.get(b).merge(a, w, Math::max);
        }
        int[] membership = new int[n];
        for (int i = 0; i < n; i++) membership[i] = i;
        List<Map<Integer, Double>> level = adj;
        int[] levelOf = membership.clone();
        while (true) {
            int[] moved = onePass(level);
            int count = Arrays.stream(moved).max().orElse(-1) + 1;
            if (count == level.size()) break;
            for (int i = 0; i < n; i++) levelOf[i] = moved[levelOf[i]];
            level = aggregate(level, moved, count);
        }
        Map<Integer, List<String>> parts = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            parts.computeIfAbsent(levelOf[i], k -> new ArrayList<>()).add(members.get(i));
        List<List<String>> out = new ArrayList<>(parts.values());
        out.forEach(p -> p.sort(null));
        return out;
    }

    /** One local-moving phase; returns each node's community renumbered densely in first-seen order. */
    private static int[] onePass(List<Map<Integer, Double>> adj) {
        int n = adj.size();
        double[] k = new double[n];
        double m2 = 0;
        for (int i = 0; i < n; i++) {
            for (double w : adj.get(i).values()) k[i] += w;
            m2 += k[i];
        }
        int[] comm = new int[n];
        double[] tot = new double[n];
        for (int i = 0; i < n; i++) {
            comm[i] = i;
            tot[i] = k[i];
        }
        if (m2 == 0) return renumber(comm);
        boolean improved = true;
        for (int sweep = 0; improved && sweep < 100; sweep++) {
            improved = false;
            for (int i = 0; i < n; i++) {
                Map<Integer, Double> links = new TreeMap<>();
                for (Map.Entry<Integer, Double> e : adj.get(i).entrySet()) {
                    if (e.getKey() != i) links.merge(comm[e.getKey()], e.getValue(), Double::sum);
                }
                int own = comm[i];
                tot[own] -= k[i];
                int best = own;
                double bestGain = links.getOrDefault(own, 0.0) - tot[own] * k[i] / m2;
                for (Map.Entry<Integer, Double> e : links.entrySet()) {
                    double gain = e.getValue() - tot[e.getKey()] * k[i] / m2;
                    if (gain > bestGain + 1e-12) {
                        best = e.getKey();
                        bestGain = gain;
                    }
                }
                tot[best] += k[i];
                if (best != own) {
                    comm[i] = best;
                    improved = true;
                }
            }
        }
        return renumber(comm);
    }

    private static List<Map<Integer, Double>> aggregate(List<Map<Integer, Double>> adj, int[] comm, int count) {
        List<Map<Integer, Double>> out = new ArrayList<>();
        for (int c = 0; c < count; c++) out.add(new TreeMap<>());
        for (int i = 0; i < adj.size(); i++) {
            for (Map.Entry<Integer, Double> e : adj.get(i).entrySet()) {
                out.get(comm[i]).merge(comm[e.getKey()], e.getValue(), Double::sum);
            }
        }
        return out;
    }

    private static int[] renumber(int[] comm) {
        Map<Integer, Integer> dense = new HashMap<>();
        int[] out = new int[comm.length];
        for (int i = 0; i < comm.length; i++) out[i] = dense.computeIfAbsent(comm[i], c -> dense.size());
        return out;
    }

    private static String hubName(CodeGraph graph, List<String> members, int c) {
        String hub = members.stream()
                .min(Comparator.comparingInt((String id) -> -graph.degree(id)).thenComparing(id -> id))
                .orElse(null);
        GraphNode n = hub == null ? null : graph.node(hub);
        String name = n == null ? "" : n.label().strip();
        if (name.endsWith("()")) name = name.substring(0, name.length() - 2);
        return name.isEmpty() ? "Community " + c : name;
    }
}
