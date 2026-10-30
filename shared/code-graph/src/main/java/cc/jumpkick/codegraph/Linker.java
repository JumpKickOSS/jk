// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** A compiled method node's JVM descriptors, one per overload: what an override mention is checked against. */
    public static final String DESCRIPTORS = "descriptors";

    /**
     * A compiled method node's calls between its own overloads, each {@code from>to}: an overload
     * that delegates to another runs what that one runs, and the linker keeps no edge from a node to itself.
     */
    public static final String DELEGATES = "delegates";

    /** The {@code node_kind} of a configuration key node. */
    public static final String CONFIG_KEY = "config_key";

    private static final List<String> MODULE_SUFFIXES =
            List.of("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".vue", "/index.ts", "/index.tsx", "/index.js");

    private final Map<String, GraphNode> nodes;
    private final Map<String, List<String>> supertypes = new HashMap<>();
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, List<String>> symbols = new HashMap<>();
    private final Map<String, String> files = new HashMap<>();
    private final Map<String, List<String>> keys = new HashMap<>();

    private Linker(Map<String, GraphNode> nodes) {
        this.nodes = nodes;
        for (GraphNode n : nodes.values()) {
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

    /**
     * The graph {@code fragments} merge into, with their mentions resolved to edges. Method calls
     * resolve last, once every inheritance edge is known, so a call to an inherited method finds
     * the supertype declaring it. The graph itself is built once, at the end.
     */
    public static CodeGraph link(List<Fragment> fragments) {
        Linker linker = new Linker(CodeGraph.mergeNodes(fragments));
        Fragment types = linker.edges(fragments, false);
        for (Fragment f : fragments) linker.supertypes(f.edges());
        linker.supertypes(types.edges());
        Fragment calls = linker.edges(fragments, true);
        List<Fragment> all = new ArrayList<>(fragments);
        all.add(types);
        all.add(calls);
        CodeGraph graph = CodeGraph.merge(all);
        graph.stale(linker.stale);
        return graph;
    }

    /**
     * Candidate stale references: a document's relative link no graph file answers, and a qualified
     * class name in a package the workspace declares that no node is. The caller drops links to files
     * that exist outside the graph (images, licenses).
     */
    private final List<StaleRef> stale = new ArrayList<>();

    private void noteStale(Mention m, GraphNode from) {
        if (!from.fileType().equals("document") || stale.size() >= StaleRef.LIMIT * 4) return;
        if (m.kind() == Mention.Kind.FILE) {
            String path = relative(from.sourceFile(), m.text());
            if (!path.isEmpty()) stale.add(new StaleRef(from.sourceFile(), m.line(), StaleRef.FILE, path));
        } else if (m.kind() == Mention.Kind.CLASS) {
            String name = m.text().replace('$', '.');
            int dot = name.lastIndexOf('.');
            if (dot > 0 && packages().contains(name.substring(0, dot))) {
                stale.add(new StaleRef(from.sourceFile(), m.line(), StaleRef.CLASS, name));
            }
        }
    }

    private @Nullable Set<String> packages;

    /** Every package a workspace class is declared in. */
    private Set<String> packages() {
        Set<String> have = packages;
        if (have != null) return have;
        Set<String> out = new HashSet<>();
        for (String fqcn : classes.keySet()) {
            int dot = fqcn.lastIndexOf('.');
            if (dot > 0) out.add(fqcn.substring(0, dot));
        }
        packages = out;
        return out;
    }

    private void supertypes(List<GraphEdge> edges) {
        for (GraphEdge e : edges) {
            if (e.relation().equals(Relations.INHERITS) || e.relation().equals(Relations.IMPLEMENTS)) {
                supertypes.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
            }
        }
    }

    private Fragment edges(List<Fragment> fragments, boolean members) {
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        for (Fragment f : fragments) {
            for (Mention m : f.mentions()) {
                if ((m.kind() == Mention.Kind.MEMBER) != members) continue;
                List<GraphEdge> resolved = resolve(m);
                if (resolved.isEmpty() && !members) {
                    GraphNode from = nodes.get(m.source());
                    if (from != null) noteStale(m, from);
                }
                for (GraphEdge e : resolved) {
                    if (!e.source().equals(e.target())) {
                        edges.merge(
                                e.source() + '\u0000' + e.target() + '\u0000' + e.relation(),
                                e,
                                GraphEdge::withSitesOf);
                    }
                }
            }
        }
        return new Fragment(List.of(), List.copyOf(edges.values()));
    }

    private List<GraphEdge> resolve(Mention m) {
        GraphNode from = nodes.get(m.source());
        if (from == null) return List.of();
        List<String> targets =
                switch (m.kind()) {
                    case CLASS -> one(classes.get(m.text().replace('$', '.')));
                    case MEMBER -> one(member(m.text()));
                    case SYMBOL -> one(unique(symbols.get(bare(m.text()))));
                    case FILE -> one(files.get(relative(from.sourceFile(), m.text())));
                    case MODULE -> one(module(m.text()));
                    case CONFIG_KEY -> configKeys(m.text());
                };
        boolean inferred = m.kind() == Mention.Kind.SYMBOL || m.kind() == Mention.Kind.CONFIG_KEY;
        String context = m.context() != null
                ? m.context()
                : switch (m.kind()) {
                    case SYMBOL -> m.text();
                    case CONFIG_KEY -> "config_key";
                    case MEMBER -> "call";
                    default -> null;
                };
        List<GraphEdge> out = new ArrayList<>();
        for (String target : targets) {
            if (!overrides(m, target)) continue;
            GraphEdge edge = GraphEdge.of(
                    m.source(),
                    target,
                    m.relation(),
                    inferred ? Confidence.INFERRED : Confidence.EXTRACTED,
                    from.sourceFile(),
                    m.line());
            if (m.overload() != null) edge = edge.withOverload(m.overload());
            if (m.kind() == Mention.Kind.SYMBOL) edge = edge.withScore(0.95);
            if (m.kind() == Mention.Kind.CONFIG_KEY) edge = edge.withScore(0.85);
            out.add(context == null ? edge : edge.withContext(context));
        }
        return out;
    }

    /**
     * False for an override mention whose target's compiled signatures lack the descriptor it
     * names: an overload of the same name, which a call to it never dispatches to the mentioning one.
     */
    private boolean overrides(Mention m, String target) {
        if (!m.relation().equals(Relations.OVERRIDES) || m.overload() == null) return true;
        String declared = m.overload().substring(m.overload().indexOf('>') + 1);
        GraphNode n = nodes.get(target);
        return n == null || !(n.extra().get(DESCRIPTORS) instanceof List<?> declares) || declares.contains(declared);
    }

    /** {@code owner#name}: the member on the owner, else on the nearest supertype declaring it. */
    private @Nullable String member(String text) {
        int hash = text.indexOf('#');
        if (hash < 0) return null;
        String owner = classes.get(text.substring(0, hash).replace('$', '.'));
        if (owner == null) return null;
        String name = text.substring(hash + 1);
        List<String> frontier = List.of(owner);
        for (int hop = 0; hop < 6 && !frontier.isEmpty(); hop++) {
            List<String> next = new ArrayList<>();
            for (String type : frontier) {
                String id = GraphIds.member(type, name);
                if (nodes.containsKey(id)) return id;
                String property = propertyOf(name);
                if (property != null && nodes.containsKey(GraphIds.member(type, property))) {
                    return GraphIds.member(type, property);
                }
                next.addAll(supertypes.getOrDefault(type, List.of()));
            }
            frontier = next;
        }
        return null;
    }

    /** The property a Kotlin accessor reads: {@code getAppModule} → {@code appModule}; {@code null} for any other name. */
    static @Nullable String propertyOf(String accessor) {
        if (accessor.length() > 3 && accessor.startsWith("get") && Character.isUpperCase(accessor.charAt(3))) {
            return Character.toLowerCase(accessor.charAt(3)) + accessor.substring(4);
        }
        return null;
    }

    /** The key itself, or every key under {@code prefix.*} (at most 25). */
    private List<String> configKeys(String text) {
        if (!text.endsWith(".*")) return one(unique(keys.get(relaxed(text))));
        String prefix = relaxed(text.substring(0, text.length() - 1));
        List<String> out = new ArrayList<>();
        keys.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.addAll(e.getValue()));
        return out.size() > 25 ? out.subList(0, 25) : out;
    }

    private static List<String> one(@Nullable String id) {
        return id == null ? List.of() : List.of(id);
    }

    private @Nullable String module(String path) {
        for (String suffix : MODULE_SUFFIXES) {
            String hit = files.get(path + suffix);
            if (hit != null) return hit;
        }
        return null;
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
