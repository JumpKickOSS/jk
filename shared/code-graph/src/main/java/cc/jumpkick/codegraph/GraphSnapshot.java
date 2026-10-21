// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import cc.jumpkick.jsonl.MiniJson;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A built graph in a compact binary form a query loads in a fraction of {@code graph.json}'s time:
 * a string table, then nodes and edges as indices into it. {@code key} records what the graph was
 * built from, so a reader can tell whether it still describes the tree. Not an exchange format:
 * {@code graph.json} is the one other tools read.
 *
 * <p>Layout: magic {@code JKGS}, format 1, the key, the string table, the nodes ({@code id, label,
 * file_type, source_file, source_location, extra-as-JSON, community}), the edges ({@code source,
 * target, relation, confidence ordinal, score, source_file, source_location, weight, context,
 * extra-as-JSON}, every string an index, {@code -1} for none), then the community names.
 */
public final class GraphSnapshot {

    private GraphSnapshot() {}

    private static final int MAGIC = 0x4A4B4753;
    private static final int FORMAT = 1;

    /** What a graph was built from: extractor version, file count, class index digest, and commit. */
    public record Key(
            String extractors,
            int files,
            String classes,
            @Nullable String commit) {}

    /** A snapshot read back: its key and graph. */
    public record Loaded(Key key, CodeGraph graph) {}

    public static void write(CodeGraph graph, Key key, OutputStream out) throws IOException {
        Strings strings = new Strings();
        List<GraphNode> nodes = new ArrayList<>(graph.nodes());
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) index.put(nodes.get(i).id(), i);
        int[][] n = new int[nodes.size()][];
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode g = nodes.get(i);
            n[i] = new int[] {
                strings.of(g.id()),
                strings.of(g.label()),
                strings.of(g.fileType()),
                strings.of(g.sourceFile()),
                strings.of(g.sourceLocation()),
                g.extra().isEmpty() ? -1 : strings.of(MiniJson.write(g.extra())),
                graph.community(g.id())
            };
        }
        List<GraphEdge> edges = graph.edges();
        DataOutputStream d = new DataOutputStream(new BufferedOutputStream(out, 1 << 16));
        d.writeInt(MAGIC);
        d.writeInt(FORMAT);
        d.writeUTF(key.extractors());
        d.writeInt(key.files());
        d.writeUTF(key.classes());
        d.writeUTF(key.commit() == null ? "" : key.commit());
        int[][] e = new int[edges.size()][];
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge g = edges.get(i);
            e[i] = new int[] {
                index.get(g.source()),
                index.get(g.target()),
                strings.of(g.relation()),
                g.confidence().ordinal(),
                strings.of(g.sourceFile()),
                strings.of(g.sourceLocation()),
                g.context() == null ? -1 : strings.of(g.context()),
                g.extra().isEmpty() ? -1 : strings.of(MiniJson.write(g.extra()))
            };
        }
        d.writeInt(strings.list.size());
        for (String s : strings.list) writeString(d, s);
        d.writeInt(n.length);
        for (int[] row : n) {
            for (int v : row) d.writeInt(v);
        }
        d.writeInt(e.length);
        for (int i = 0; i < e.length; i++) {
            for (int v : e[i]) d.writeInt(v);
            d.writeFloat((float) edges.get(i).confidenceScore());
            d.writeFloat((float) edges.get(i).weight());
        }
        Map<Integer, List<String>> communities = graph.communities();
        d.writeInt(communities.size());
        for (int c : communities.keySet()) {
            d.writeInt(c);
            String name = graph.communityName(c);
            writeString(d, name == null ? "" : name);
        }
        d.flush();
    }

    /** The key alone, without reading the graph. {@code null} for a file that is not a snapshot. */
    public static @Nullable Key key(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 12));
        if (d.readInt() != MAGIC || d.readInt() != FORMAT) return null;
        return readKey(d);
    }

    public static @Nullable Loaded read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        if (d.readInt() != MAGIC || d.readInt() != FORMAT) return null;
        Key key = readKey(d);
        String[] s = new String[d.readInt()];
        for (int i = 0; i < s.length; i++) s[i] = readString(d);
        GraphNode[] nodes = new GraphNode[d.readInt()];
        Map<String, Integer> community = new HashMap<>();
        for (int i = 0; i < nodes.length; i++) {
            String id = s[d.readInt()];
            String label = s[d.readInt()];
            String type = s[d.readInt()];
            String file = s[d.readInt()];
            String loc = s[d.readInt()];
            int extra = d.readInt();
            int c = d.readInt();
            nodes[i] = new GraphNode(id, label, type, file, loc, extra < 0 ? Map.of() : map(s[extra]));
            if (c >= 0) community.put(id, c);
        }
        List<GraphEdge> edges = new ArrayList<>();
        int count = d.readInt();
        Confidence[] confidences = Confidence.values();
        for (int i = 0; i < count; i++) {
            String source = nodes[d.readInt()].id();
            String target = nodes[d.readInt()].id();
            String relation = s[d.readInt()];
            Confidence confidence = confidences[d.readInt()];
            String file = s[d.readInt()];
            String loc = s[d.readInt()];
            int context = d.readInt();
            int extra = d.readInt();
            double score = d.readFloat();
            double weight = d.readFloat();
            edges.add(new GraphEdge(
                    source,
                    target,
                    relation,
                    confidence,
                    score,
                    file,
                    loc,
                    weight,
                    context < 0 ? null : s[context],
                    extra < 0 ? Map.of() : map(s[extra])));
        }
        Map<Integer, String> names = new HashMap<>();
        int communities = d.readInt();
        for (int i = 0; i < communities; i++) {
            int c = d.readInt();
            String name = readString(d);
            if (!name.isEmpty()) names.put(c, name);
        }
        return new Loaded(key, CodeGraph.of(List.of(nodes), edges, community, names));
    }

    private static Key readKey(DataInputStream d) throws IOException {
        String extractors = d.readUTF();
        int files = d.readInt();
        String classes = d.readUTF();
        String commit = d.readUTF();
        return new Key(extractors, files, classes, commit.isEmpty() ? null : commit);
    }

    /** UTF-8 bytes with an int length: {@code writeUTF} stops at 64 KiB. */
    private static void writeString(DataOutputStream d, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        d.writeInt(b.length);
        d.write(b);
    }

    private static String readString(DataInputStream d) throws IOException {
        byte[] b = new byte[d.readInt()];
        d.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> map(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (MiniJson.parse(json) instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /** The string table, deduplicating as it grows. */
    private static final class Strings {
        final List<String> list = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();

        int of(String s) {
            return index.computeIfAbsent(s, k -> {
                list.add(k);
                return list.size() - 1;
            });
        }
    }
}
