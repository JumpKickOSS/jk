// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Fragment} in the form the action cache keeps it: rows in a fixed order, so equal
 * fragments are equal bytes.
 */
public final class GraphJson {

    private GraphJson() {}

    /**
     * One fragment of {@code file} in its cached form: rows instead of objects, a value left out
     * where it is the default or the file itself, and every id that starts with the fragment's first
     * node id written from {@code ~}, so a fragment holds its long id prefix once. {@code {"b":base,"n":[[id,label,type,location,extra,file]…],"e":[[source,target,
     * relation,confidence,location,context,extra,score,weight,file]…],"m":[[source,kind,text,line,
     * relation,context,overload]…]}}, each row without its trailing nulls and each empty list left out.
     */
    public static Map<String, Object> compactTree(Fragment f, String file) {
        String base = f.nodes().isEmpty() ? null : f.nodes().getFirst().id();
        Map<String, Object> doc = new LinkedHashMap<>();
        if (base != null) doc.put("b", base);
        List<Object> nodes = new ArrayList<>();
        for (GraphNode n : f.nodes()) {
            nodes.add(trimmed(Arrays.asList(
                    intern(n.id(), base),
                    n.label(),
                    n.fileType(),
                    n.sourceLocation(),
                    n.extra().isEmpty() ? null : new TreeMap<>(n.extra()),
                    n.sourceFile().equals(file) ? null : n.sourceFile())));
        }
        List<Object> edges = new ArrayList<>();
        for (GraphEdge e : f.edges()) {
            edges.add(trimmed(Arrays.asList(
                    intern(e.source(), base),
                    intern(e.target(), base),
                    e.relation(),
                    e.confidence().name(),
                    e.sourceLocation(),
                    e.context(),
                    e.extra().isEmpty() ? null : new TreeMap<>(e.extra()),
                    e.confidenceScore() == e.confidence().defaultScore() ? null : e.confidenceScore(),
                    e.weight() == 1.0 ? null : e.weight(),
                    e.sourceFile().equals(file) ? null : e.sourceFile())));
        }
        List<Object> mentions = new ArrayList<>();
        for (Mention m : f.mentions()) {
            mentions.add(trimmed(Arrays.asList(
                    intern(m.source(), base),
                    m.kind().name(),
                    m.text(),
                    m.line(),
                    m.relation(),
                    m.context(),
                    m.overload())));
        }
        if (!nodes.isEmpty()) doc.put("n", nodes);
        if (!edges.isEmpty()) doc.put("e", edges);
        if (!mentions.isEmpty()) doc.put("m", mentions);
        return doc;
    }

    /** The fragment of {@code file} a {@link #compactTree} tree holds. */
    public static Fragment compactOf(@Nullable Object tree, String file) {
        if (!(tree instanceof Map<?, ?> doc)) throw new IllegalArgumentException("not a fragment");
        String base = MiniJson.get(doc, "b") == null ? null : String.valueOf(MiniJson.get(doc, "b"));
        List<GraphNode> nodes = new ArrayList<>();
        for (Object o : MiniJson.list(doc, "n")) {
            List<?> r = row(o, 4);
            nodes.add(new GraphNode(
                    expand(at(r, 0), base),
                    at(r, 1),
                    at(r, 2),
                    r.size() > 5 && r.get(5) != null ? at(r, 5) : file,
                    at(r, 3),
                    map(r, 4)));
        }
        List<GraphEdge> edges = new ArrayList<>();
        for (Object o : MiniJson.list(doc, "e")) {
            List<?> r = row(o, 5);
            Confidence confidence = Confidence.of(at(r, 3));
            edges.add(new GraphEdge(
                    expand(at(r, 0), base),
                    expand(at(r, 1), base),
                    at(r, 2),
                    confidence,
                    r.size() > 7 && r.get(7) instanceof Number s ? s.doubleValue() : confidence.defaultScore(),
                    r.size() > 9 && r.get(9) != null ? at(r, 9) : file,
                    at(r, 4),
                    r.size() > 8 && r.get(8) instanceof Number w ? w.doubleValue() : 1.0,
                    r.size() > 5 && r.get(5) != null ? at(r, 5) : null,
                    map(r, 6)));
        }
        List<Mention> mentions = new ArrayList<>();
        for (Object o : MiniJson.list(doc, "m")) {
            List<?> r = row(o, 5);
            if (!(r.get(3) instanceof Number line)) throw new IllegalArgumentException("mention without a line");
            mentions.add(new Mention(
                    expand(at(r, 0), base),
                    Mention.Kind.valueOf(at(r, 1)),
                    at(r, 2),
                    line.intValue(),
                    at(r, 4),
                    r.size() > 5 && r.get(5) != null ? at(r, 5) : null,
                    r.size() > 6 && r.get(6) != null ? at(r, 6) : null));
        }
        return new Fragment(nodes, edges, mentions);
    }

    private static String intern(String id, @Nullable String base) {
        return base != null && id.startsWith(base) ? "~" + id.substring(base.length()) : id;
    }

    private static String expand(String id, @Nullable String base) {
        return base != null && id.startsWith("~") ? base + id.substring(1) : id;
    }

    private static List<Object> trimmed(List<Object> row) {
        int end = row.size();
        while (end > 0 && row.get(end - 1) == null) end--;
        return new ArrayList<>(row.subList(0, end));
    }

    private static List<?> row(@Nullable Object o, int least) {
        if (!(o instanceof List<?> r) || r.size() < least) throw new IllegalArgumentException("short row");
        return r;
    }

    private static String at(List<?> r, int i) {
        return i < r.size() && r.get(i) != null ? String.valueOf(r.get(i)) : "";
    }

    private static Map<String, Object> map(List<?> r, int i) {
        if (i >= r.size() || !(r.get(i) instanceof Map<?, ?> m)) return Map.of();
        Map<String, Object> out = new TreeMap<>();
        m.forEach((k, v) -> {
            if (v != null) out.put(String.valueOf(k), v);
        });
        return out;
    }
}
