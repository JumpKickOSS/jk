// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves every fragment's {@link Mention}s against the merged graph. A class name, file path or
 * module path that names exactly one node becomes an {@code EXTRACTED} edge; a prose symbol or a
 * configuration key matched by name becomes {@code INFERRED}. A mention that matches nothing, or a
 * symbol several nodes share, is dropped.
 */
public final class Linker {

    /** The node attribute holding a JVM type's binary name, {@code com.acme.Shop$Cart}. */
    public static final String QUALIFIED_NAME = "qualified_name";

    /** The {@code node_kind} of a configuration key node. */
    public static final String CONFIG_KEY = "config_key";

    private static final List<String> MODULE_SUFFIXES =
            List.of("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".vue", "/index.ts", "/index.tsx", "/index.js");

    private final CodeGraph graph;
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, List<String>> symbols = new HashMap<>();
    private final Map<String, String> files = new HashMap<>();
    private final Map<String, List<String>> keys = new HashMap<>();

    private Linker(CodeGraph graph) {
        this.graph = graph;
        for (GraphNode n : graph.nodes()) {
            if (n.extra().get(QUALIFIED_NAME) instanceof String fqcn) classes.put(fqcn.replace('$', '.'), n.id());
            if (CONFIG_KEY.equals(n.extra().get("node_kind"))) {
                keys.computeIfAbsent(relaxed(n.label()), k -> new ArrayList<>()).add(n.id());
            } else if (!n.sourceFile().isEmpty() && n.id().equals(GraphIds.file(n.sourceFile()))) {
                files.put(n.sourceFile(), n.id());
            } else if (n.fileType().equals("code") && !n.sourceFile().isEmpty()) {
                symbols.computeIfAbsent(bare(n.label()), k -> new ArrayList<>()).add(n.id());
            }
        }
    }

    /** The graph {@code fragments} merge into, with their mentions resolved to edges. */
    public static CodeGraph link(List<Fragment> fragments) {
        CodeGraph merged = CodeGraph.merge(fragments);
        Linker linker = new Linker(merged);
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        for (Fragment f : fragments) {
            for (Mention m : f.mentions()) {
                GraphEdge e = linker.resolve(m);
                if (e != null && !e.source().equals(e.target())) {
                    edges.putIfAbsent(e.source() + '\u0000' + e.target() + '\u0000' + e.relation(), e);
                }
            }
        }
        if (edges.isEmpty()) return merged;
        List<Fragment> all = new ArrayList<>(fragments);
        all.add(new Fragment(List.of(), List.copyOf(edges.values())));
        return CodeGraph.merge(all);
    }

    private @Nullable GraphEdge resolve(Mention m) {
        GraphNode from = graph.node(m.source());
        if (from == null) return null;
        String target =
                switch (m.kind()) {
                    case CLASS -> classes.get(m.text().replace('$', '.'));
                    case SYMBOL -> unique(symbols.get(bare(m.text())));
                    case FILE -> files.get(relative(from.sourceFile(), m.text()));
                    case MODULE -> module(m.text());
                    case CONFIG_KEY -> unique(keys.get(relaxed(m.text())));
                };
        if (target == null) return null;
        GraphEdge edge = GraphEdge.of(
                m.source(),
                target,
                m.relation(),
                inferred(m.kind()) ? Confidence.INFERRED : Confidence.EXTRACTED,
                from.sourceFile(),
                m.line());
        if (m.kind() == Mention.Kind.SYMBOL) return edge.withScore(0.95).withContext(m.text());
        if (m.kind() == Mention.Kind.CONFIG_KEY) return edge.withScore(0.85).withContext("config_key");
        return edge;
    }

    private @Nullable String module(String path) {
        for (String suffix : MODULE_SUFFIXES) {
            String hit = files.get(path + suffix);
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean inferred(Mention.Kind kind) {
        return kind == Mention.Kind.SYMBOL || kind == Mention.Kind.CONFIG_KEY;
    }

    private static @Nullable String unique(@Nullable List<String> ids) {
        return ids != null && ids.size() == 1 ? ids.getFirst() : null;
    }

    /** {@code link} resolved against the directory of {@code fromFile}; a leading {@code /} is the root. */
    static String relative(String fromFile, String link) {
        String path = link;
        int cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        if (path.isEmpty()) return "";
        String base;
        if (path.startsWith("/")) {
            base = "";
            path = path.substring(1);
        } else {
            int slash = fromFile.lastIndexOf('/');
            base = slash < 0 ? "" : fromFile.substring(0, slash);
        }
        List<String> parts = new ArrayList<>();
        for (String seg : (base.isEmpty() ? path : base + "/" + path).split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.add(seg);
            }
        }
        return String.join("/", parts);
    }

    /** A config key as Spring's relaxed binding compares it: case, {@code -} and {@code _} ignored. */
    static String relaxed(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static String bare(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }
}
