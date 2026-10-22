// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Collects one file's fragment. The file's own node is created first, labelled with the file name;
 * every other node and edge is attributed to the same {@code source_file}.
 */
public final class FragmentBuilder {

    private final String relPath;
    private final String fileId;
    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final List<Mention> mentions = new ArrayList<>();

    /** A builder for {@code relPath} whose file node has {@code fileType} and, when set, {@code nodeKind}. */
    public FragmentBuilder(String relPath, String fileType, @Nullable String nodeKind) {
        this.relPath = relPath;
        this.fileId = GraphIds.file(relPath);
        int slash = relPath.lastIndexOf('/');
        Map<String, Object> extra = nodeKind == null ? Map.of() : Map.of("node_kind", nodeKind);
        nodes.put(fileId, new GraphNode(fileId, relPath.substring(slash + 1), fileType, relPath, "L1", extra));
    }

    public String relPath() {
        return relPath;
    }

    public String fileId() {
        return fileId;
    }

    /** Sets {@code attributes} on the file's own node. */
    public void annotateFile(Map<String, Object> attributes) {
        annotateNode(fileId, attributes);
    }

    /** Sets {@code attributes} on node {@code id}, merged over any it has. */
    public void annotateNode(String id, Map<String, Object> attributes) {
        GraphNode n = nodes.get(id);
        if (n == null) return;
        Map<String, Object> extra = new LinkedHashMap<>(n.extra());
        Map<String, Object> merged = new LinkedHashMap<>();
        if (extra.get("attributes") instanceof Map<?, ?> had) had.forEach((k, v) -> merged.put(String.valueOf(k), v));
        merged.putAll(attributes);
        extra.put("attributes", merged);
        nodes.put(id, new GraphNode(id, n.label(), n.fileType(), n.sourceFile(), n.sourceLocation(), extra));
    }

    /**
     * Records the {@link Decl} facts of a declaration at {@code line} on node {@code id}. Overloads
     * share one node: the first gives its facts, each later one adds its {@code [line, end]} to
     * {@code more}.
     */
    public void declare(String id, int line, Map<String, Object> facts) {
        GraphNode n = nodes.get(id);
        if (n == null) return;
        Map<String, Object> had = Decl.of(n);
        if (had.isEmpty()) {
            nodes.put(id, Decl.with(n, facts));
            return;
        }
        if (line == Decl.line(n) || !(facts.get(Decl.END) instanceof Number end)) return;
        Map<String, Object> merged = new LinkedHashMap<>(had);
        List<Object> more = new ArrayList<>();
        if (had.get(Decl.MORE) instanceof List<?> l) more.addAll(l);
        more.add(List.of(line, end.intValue()));
        merged.put(Decl.MORE, List.copyOf(more));
        Map<String, Object> extra = new LinkedHashMap<>(n.extra());
        extra.put(Decl.KEY, merged);
        nodes.put(id, new GraphNode(id, n.label(), n.fileType(), n.sourceFile(), n.sourceLocation(), extra));
    }

    public boolean has(String id) {
        return nodes.containsKey(id);
    }

    /** Adds a node unless one with its id exists; returns the id. */
    public String node(String id, String label, String fileType, int line, Map<String, Object> extra) {
        nodes.putIfAbsent(id, new GraphNode(id, label, fileType, relPath, GraphNode.location(line), extra));
        return id;
    }

    /** A node declared in this file: id {@code <file stem>_<name>}. */
    public String declared(String name, String label, String fileType, int line, Map<String, Object> extra) {
        return node(GraphIds.declared(relPath, name), label, fileType, line, extra);
    }

    /** A node without a source file (an external library type, a dependency coordinate). */
    public String external(String id, String label) {
        nodes.putIfAbsent(id, new GraphNode(id, label, "concept", "", "", Map.of("external", true)));
        return id;
    }

    public void edge(String source, String target, String relation, Confidence confidence, int line) {
        edges.add(GraphEdge.of(source, target, relation, confidence, relPath, line));
    }

    public void edge(String source, String target, String relation, Confidence confidence, int line, String context) {
        edges.add(GraphEdge.of(source, target, relation, confidence, relPath, line)
                .withContext(context));
    }

    public void mention(String source, Mention.Kind kind, String text, int line) {
        mentions.add(new Mention(source, kind, text, line));
    }

    public void mention(String source, Mention.Kind kind, String text, int line, String relation) {
        mentions.add(new Mention(source, kind, text, line, relation));
    }

    /** A {@code NOTE:}/{@code WHY:}-style comment as a rationale node pointing at {@code target}. */
    public void rationale(String text, int line, String target) {
        String id = GraphIds.make(GraphIds.base(relPath), "rationale", String.valueOf(line));
        String label = text.strip().replaceAll("\\s+", " ");
        if (label.length() > 120) label = label.substring(0, 117) + "...";
        node(id, label, "rationale", line, Map.of());
        edge(id, target, Relations.RATIONALE_FOR, Confidence.EXTRACTED, line);
    }

    public Fragment build() {
        return new Fragment(List.copyOf(nodes.values()), edges, mentions);
    }
}
