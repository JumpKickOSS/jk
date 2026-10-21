// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code graph.json} in graphify's node-link shape: {@code directed:false} with each link's {@code
 * source → target} order carrying its direction, {@code graph.schema_version} 1. Each object writes
 * its identifying keys first ({@code id}, {@code label}; {@code source}, {@code target}, {@code
 * relation}) and the rest sorted; nodes sort by id and links by endpoints, so equal graphs are equal
 * bytes.
 */
public final class GraphJson {

    /** The {@code graph.schema_version} this writes and reads. */
    public static final int SCHEMA_VERSION = 1;

    private static final Set<String> NODE_KEYS = Set.of(
            "id", "label", "file_type", "source_file", "source_location", "community", "community_name", "norm_label");
    private static final Set<String> EDGE_KEYS = Set.of(
            "source",
            "target",
            "relation",
            "confidence",
            "confidence_score",
            "source_file",
            "source_location",
            "weight",
            "context");

    private GraphJson() {}

    /** The document for {@code graph}; {@code generator} names the writer, {@code commit} the HEAD built from. */
    public static String write(CodeGraph graph, String generator, @Nullable String commit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", SCHEMA_VERSION);
        meta.put("generator", generator);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("directed", false);
        doc.put("multigraph", false);
        doc.put("graph", meta);
        doc.put(
                "nodes",
                graph.nodes().stream()
                        .sorted(Comparator.comparing(GraphNode::id))
                        .map(n -> node(graph, n))
                        .toList());
        doc.put(
                "links",
                graph.edges().stream()
                        .sorted(Comparator.comparing(GraphEdge::source)
                                .thenComparing(GraphEdge::target)
                                .thenComparing(GraphEdge::relation))
                        .map(GraphJson::edge)
                        .toList());
        doc.put("hyperedges", List.of());
        if (commit != null) doc.put("built_at_commit", commit);
        return MiniJson.write(doc) + "\n";
    }

    /** The graph {@code json} holds; throws {@link IllegalArgumentException} on a document that is not one. */
    public static CodeGraph read(String json) {
        if (!(MiniJson.parse(json) instanceof Map<?, ?> doc)) throw new IllegalArgumentException("not a graph.json");
        List<GraphNode> nodes = new ArrayList<>();
        Map<String, Integer> community = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (Object o : MiniJson.list(doc, "nodes")) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String id = str(m, "id");
            nodes.add(new GraphNode(
                    id,
                    str(m, "label").isEmpty() ? id : str(m, "label"),
                    str(m, "file_type"),
                    str(m, "source_file"),
                    str(m, "source_location"),
                    rest(m, NODE_KEYS)));
            if (MiniJson.get(m, "community") instanceof Number c) {
                community.put(id, c.intValue());
                String name = MiniJson.str(m, "community_name");
                if (name != null) names.putIfAbsent(c.intValue(), name);
            }
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (Object o : MiniJson.list(doc, "links")) {
            if (o instanceof Map<?, ?> m) edges.add(edge(m));
        }
        return CodeGraph.of(nodes, edges, community, names);
    }

    /**
     * One fragment as a single JSON line: {@code {"nodes":[…],"links":[…],"mentions":[[source, kind,
     * text, line, relation, context],…]}}.
     */
    public static String writeFragment(Fragment f) {
        return MiniJson.write(fragmentTree(f));
    }

    /** The fragment {@link #writeFragment} wrote. */
    public static Fragment readFragment(String json) {
        return fragmentOf(MiniJson.parse(json));
    }

    /** {@link #writeFragment}'s document as a tree, for embedding in a larger one. */
    public static Map<String, Object> fragmentTree(Fragment f) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("nodes", f.nodes().stream().map(n -> node(null, n)).toList());
        doc.put("links", f.edges().stream().map(GraphJson::edge).toList());
        doc.put(
                "mentions",
                f.mentions().stream()
                        .map(m -> Arrays.asList(
                                m.source(), m.kind().name(), m.text(), m.line(), m.relation(), m.context()))
                        .toList());
        return doc;
    }

    /** The fragment a {@link #fragmentTree} tree holds. */
    public static Fragment fragmentOf(@Nullable Object tree) {
        if (!(tree instanceof Map<?, ?> doc)) throw new IllegalArgumentException("not a fragment");
        List<GraphNode> nodes = new ArrayList<>();
        for (Object o : MiniJson.list(doc, "nodes")) {
            if (o instanceof Map<?, ?> m) {
                nodes.add(new GraphNode(
                        str(m, "id"),
                        str(m, "label"),
                        str(m, "file_type"),
                        str(m, "source_file"),
                        str(m, "source_location"),
                        rest(m, NODE_KEYS)));
            }
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (Object o : MiniJson.list(doc, "links")) {
            if (o instanceof Map<?, ?> m) edges.add(edge(m));
        }
        List<Mention> mentions = new ArrayList<>();
        for (Object o : MiniJson.list(doc, "mentions")) {
            if (o instanceof List<?> l && l.size() == 6 && l.get(3) instanceof Number line) {
                mentions.add(new Mention(
                        String.valueOf(l.get(0)),
                        Mention.Kind.valueOf(String.valueOf(l.get(1))),
                        String.valueOf(l.get(2)),
                        line.intValue(),
                        String.valueOf(l.get(4)),
                        l.get(5) == null ? null : String.valueOf(l.get(5))));
            }
        }
        return new Fragment(nodes, edges, mentions);
    }

    private static Map<String, Object> node(@Nullable CodeGraph graph, GraphNode n) {
        Map<String, Object> sorted = new TreeMap<>(n.extra());
        int c = graph == null ? -1 : graph.community(n.id());
        if (c >= 0) {
            sorted.put("community", c);
            String name = graph == null ? null : graph.communityName(c);
            if (name != null) sorted.put("community_name", name);
        }
        sorted.put("file_type", n.fileType());
        sorted.put("norm_label", n.normLabel());
        sorted.put("source_file", n.sourceFile());
        sorted.put("source_location", n.sourceLocation());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", n.id());
        out.put("label", n.label());
        out.putAll(sorted);
        return out;
    }

    private static Map<String, Object> edge(GraphEdge e) {
        Map<String, Object> sorted = new TreeMap<>(e.extra());
        sorted.put("confidence", e.confidence().name());
        sorted.put("confidence_score", e.confidenceScore());
        if (e.context() != null) sorted.put("context", e.context());
        sorted.put("source_file", e.sourceFile());
        sorted.put("source_location", e.sourceLocation());
        sorted.put("weight", e.weight());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", e.source());
        out.put("target", e.target());
        out.put("relation", e.relation());
        out.putAll(sorted);
        return out;
    }

    private static GraphEdge edge(Map<?, ?> m) {
        Confidence confidence = Confidence.of(str(m, "confidence"));
        return new GraphEdge(
                str(m, "source"),
                str(m, "target"),
                str(m, "relation"),
                confidence,
                num(m, "confidence_score", confidence.defaultScore()),
                str(m, "source_file"),
                str(m, "source_location"),
                num(m, "weight", 1.0),
                MiniJson.get(m, "context") == null ? null : String.valueOf(MiniJson.get(m, "context")),
                rest(m, EDGE_KEYS));
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = MiniJson.get(m, key);
        return v == null ? "" : String.valueOf(v);
    }

    private static double num(Map<?, ?> m, String key, double fallback) {
        return MiniJson.get(m, key) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static Map<String, Object> rest(Map<?, ?> m, Set<String> known) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!known.contains(key) && e.getValue() != null) out.put(key, e.getValue());
        }
        return out;
    }
}
