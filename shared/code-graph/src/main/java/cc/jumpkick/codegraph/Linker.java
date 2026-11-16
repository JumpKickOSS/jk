// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Resolves every fragment's {@link Mention}s against the merged graph. A class name, file path or
 * module path that names exactly one node becomes an {@code EXTRACTED} edge; a prose symbol, or a
 * configuration key matched by name to a {@code config} anchor some file already names, becomes
 * {@code INFERRED}. A mention that matches nothing, or a
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

    private static final List<String> MODULE_SUFFIXES =
            List.of("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".vue", "/index.ts", "/index.tsx", "/index.js");

    private final Map<String, GraphNode> nodes;
    private final Map<String, List<String>> supertypes = new HashMap<>();
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, List<String>> symbols = new HashMap<>();
    private final Map<String, String> files = new HashMap<>();
    private final List<String> configKeys = new ArrayList<>();
    private final Map<Integer, List<RouteAnchor>> routes = new HashMap<>();

    private Linker(Map<String, GraphNode> nodes) {
        this.nodes = nodes;
        for (GraphNode n : nodes.values()) {
            if (n.extra().get(QUALIFIED_NAME) instanceof String fqcn) classes.put(fqcn.replace('$', '.'), n.id());
            if (Anchors.is(n)) {
                if (Anchors.CONFIG.equals(Anchors.kindOf(n.id()))) configKeys.add(n.id());
                if (Anchors.ROUTE.equals(Anchors.kindOf(n.id()))) {
                    RouteAnchor r = RouteAnchor.of(n.id());
                    routes.computeIfAbsent(r.wild() ? -1 : r.segments().size(), k -> new ArrayList<>())
                            .add(r);
                }
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
                        edges.merge(e.source() + '\u0000' + e.target() + '\u0000' + e.relation(), e, Linker::stronger);
                    }
                }
            }
        }
        return new Fragment(List.of(), List.copyOf(edges.values()));
    }

    /**
     * Two edges between the same nodes as one: the surer one, with the other's sites. A request a test
     * makes declared and the same literal seen again as a bare path stay declared.
     */
    private static GraphEdge stronger(GraphEdge a, GraphEdge b) {
        return b.confidence().ordinal() < a.confidence().ordinal() ? b.withSitesOf(a) : a.withSitesOf(b);
    }

    private List<GraphEdge> resolve(Mention m) {
        GraphNode from = nodes.get(m.source());
        if (from == null) return List.of();
        List<String> targets =
                switch (m.kind()) {
                    case CLASS -> one(classes.get(m.text().replace('$', '.')));
                    case MEMBER -> one(member(m));
                    case SYMBOL -> one(unique(symbols.get(bare(m.text()))));
                    case FILE -> one(files.get(relative(from.sourceFile(), m.text())));
                    case MODULE -> one(module(m.text()));
                    case CONFIG_KEY -> configKeys(m.text());
                    case ROUTE, ROUTE_CALL -> routes(m.text());
                    case TABLE, COLUMN -> sqlNames(m);
                };
        if (m.kind() == Mention.Kind.TABLE || m.kind() == Mention.Kind.COLUMN) return sqlEdges(m, from, targets);
        if (m.kind() == Mention.Kind.ROUTE || m.kind() == Mention.Kind.ROUTE_CALL) return routeEdges(m, from, targets);
        boolean inferred = m.kind() == Mention.Kind.SYMBOL || m.kind() == Mention.Kind.CONFIG_KEY;
        String context = m.context() != null
                ? m.context()
                : switch (m.kind()) {
                    case SYMBOL -> m.text();
                    case CONFIG_KEY -> asWritten(m.text());
                    case MEMBER -> "call";
                    default -> null;
                };
        List<GraphEdge> out = new ArrayList<>();
        for (String target : targets) {
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
     * A member mention's target. With the overload it joins, the target is the nearest type up the
     * hierarchy that declares that very descriptor: a supertype declaring only other overloads of the
     * name is passed over, so an override of an inherited overload lands on the type that declares
     * it. An override that no type declares links to nothing; a call falls back to the name.
     */
    private @Nullable String member(Mention m) {
        String declared = null;
        if (m.overload() != null) {
            String to = m.overload().substring(m.overload().indexOf('>') + 1);
            if (!to.equals("*")) declared = to;
        }
        String exact = member(m.text(), declared);
        if (exact != null || declared == null || m.relation().equals(Relations.OVERRIDES)) return exact;
        return member(m.text(), null);
    }

    /**
     * {@code owner#name}: the member on the owner, else on the nearest supertype declaring it; with
     * {@code descriptor}, only a member whose compiled overloads include it (one whose overloads are
     * not known counts).
     */
    private @Nullable String member(String text, @Nullable String descriptor) {
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
                if (nodes.containsKey(id) && declares(id, descriptor)) return id;
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

    /** True when {@code descriptor} is null, or member {@code id} has it among its compiled descriptors or has none listed. */
    private boolean declares(String id, @Nullable String descriptor) {
        if (descriptor == null) return true;
        GraphNode n = nodes.get(id);
        return n == null || !(n.extra().get(DESCRIPTORS) instanceof List<?> declares) || declares.contains(descriptor);
    }

    /** The property a Kotlin accessor reads: {@code getAppModule} → {@code appModule}; {@code null} for any other name. */
    static @Nullable String propertyOf(String accessor) {
        if (accessor.length() > 3 && accessor.startsWith("get") && Character.isUpperCase(accessor.charAt(3))) {
            return Character.toLowerCase(accessor.charAt(3)) + accessor.substring(4);
        }
        return null;
    }

    /**
     * The {@code config} anchor of the key, or of every key under {@code prefix.*} (at most 25): only
     * an anchor some file already names, so a key-shaped string that names none links to nothing.
     */
    private List<String> configKeys(String text) {
        if (!text.endsWith(".*")) {
            String id = Anchors.id(Anchors.CONFIG, text);
            return nodes.containsKey(id) ? List.of(id) : List.of();
        }
        String prefix = Anchors.id(Anchors.CONFIG, text.substring(0, text.length() - 1));
        List<String> out =
                configKeys.stream().filter(id -> id.startsWith(prefix)).sorted().toList();
        return out.size() > 25 ? out.subList(0, 25) : out;
    }

    /**
     * A route anchor split for matching: its method, its path's segments ({@code {}} a variable,
     * {@code *} any one segment, {@code **} any rest), and how many are literal.
     */
    private record RouteAnchor(String id, String method, List<String> segments, int literals, boolean wild) {

        static RouteAnchor of(String id) {
            String key = id.substring(Anchors.ROUTE.length() + 1);
            int space = key.indexOf(' ');
            String method = key.substring(0, space);
            List<String> segs = segments(key.substring(space + 1));
            int literals = (int) segs.stream().filter(RouteAnchor::literal).count();
            return new RouteAnchor(id, method, segs, literals, segs.contains("**"));
        }

        static List<String> segments(String path) {
            return path.equals("/") ? List.of() : List.of(path.substring(1).split("/"));
        }

        static boolean literal(String segment) {
            return !segment.equals("{}") && !segment.equals("*") && !segment.equals("**");
        }

        /** Whether a request of {@code method} to {@code path}'s segments reaches this route. */
        boolean fits(String method, List<String> path) {
            if (!this.method.equals("*") && !method.equals("*") && !this.method.equals(method)) return false;
            int i = 0;
            for (; i < segments.size(); i++) {
                String s = segments.get(i);
                if (s.equals("**")) return true;
                if (i >= path.size()) return false;
                String p = path.get(i);
                if (s.equals("{}") || s.equals("*")) continue;
                // A variable in the request (a template's {id}) fills only a variable of the route.
                if (!s.equals(p)) return false;
            }
            return i == path.size();
        }
    }

    /**
     * The route anchors a request {@code text} ({@code METHOD /path}) fits, those with the most literal
     * segments only, and only routes with at least one literal segment: {@code /{}} fits every
     * one-segment path, which says nothing.
     */
    private List<String> routes(String text) {
        String key = Anchors.key(Anchors.ROUTE, text);
        int space = key.indexOf(' ');
        String method = key.substring(0, space);
        List<String> path = RouteAnchor.segments(key.substring(space + 1));
        List<RouteAnchor> candidates = new ArrayList<>(routes.getOrDefault(path.size(), List.of()));
        candidates.addAll(routes.getOrDefault(-1, List.of()));
        int best = 0;
        List<String> out = new ArrayList<>();
        for (RouteAnchor r : candidates) {
            if (r.literals() == 0 || !r.fits(method, path)) continue;
            if (r.literals() > best) {
                best = r.literals();
                out.clear();
            }
            if (r.literals() == best) out.add(r.id());
        }
        out.sort(null);
        return out;
    }

    /**
     * A request's uses edges: declared for a client call naming a route's own method (or any, for a
     * route that takes every method) and its very template, inferred otherwise; the path as written as
     * the context when the route shows it otherwise.
     */
    private List<GraphEdge> routeEdges(Mention m, GraphNode from, List<String> targets) {
        String key = Anchors.key(Anchors.ROUTE, m.text());
        String path = key.substring(key.indexOf(' ') + 1);
        String method = key.substring(0, key.indexOf(' '));
        List<GraphEdge> out = new ArrayList<>();
        for (String target : targets) {
            RouteAnchor r = RouteAnchor.of(target);
            boolean exact = m.kind() == Mention.Kind.ROUTE_CALL
                    && target.endsWith(" " + path)
                    && (r.method().equals(method) || r.method().equals("*"));
            GraphEdge e = GraphEdge.of(
                    m.source(),
                    target,
                    Relations.USES,
                    exact ? Confidence.DECLARED : Confidence.INFERRED,
                    from.sourceFile(),
                    m.line());
            String written = m.text().strip();
            int sp = written.indexOf(' ');
            String writtenPath = sp < 0 ? written : written.substring(sp + 1).strip();
            out.add(writtenPath.equals(target.substring(target.indexOf(' ') + 1)) ? e : e.withContext(writtenPath));
        }
        return out;
    }

    /**
     * The table or column anchors a query's name resolves to, only ones some file names: {@code
     * orders} or {@code orders.status} itself, or for {@code status@orders,items} each of those tables'
     * {@code status} that exists.
     */
    private List<String> sqlNames(Mention m) {
        String text = m.text();
        if (m.kind() == Mention.Kind.TABLE) {
            String id = Anchors.id(Anchors.TABLE, text);
            return nodes.containsKey(id) ? List.of(id) : List.of();
        }
        int at = text.indexOf('@');
        List<String> candidates = new ArrayList<>();
        if (at < 0) {
            candidates.add(Anchors.id(Anchors.COLUMN, text));
        } else {
            String column = text.substring(0, at);
            for (String table : text.substring(at + 1).split(",")) {
                if (!table.isEmpty()) candidates.add(Anchors.id(Anchors.COLUMN, table + "." + column));
            }
        }
        return candidates.stream()
                .filter(nodes::containsKey)
                .distinct()
                .sorted()
                .toList();
    }

    /** A query's uses edges: inferred, or ambiguous when a bare column fits more than one of its tables. */
    private List<GraphEdge> sqlEdges(Mention m, GraphNode from, List<String> targets) {
        Confidence grade = targets.size() > 1 ? Confidence.AMBIGUOUS : Confidence.INFERRED;
        List<GraphEdge> out = new ArrayList<>();
        for (String target : targets) {
            GraphEdge e = GraphEdge.of(m.source(), target, Relations.USES, grade, from.sourceFile(), m.line());
            out.add(m.context() == null ? e : e.withContext(m.context()));
        }
        return out;
    }

    /** The key as the mention wrote it, when the anchor shows it otherwise; else {@code null}. */
    private static @Nullable String asWritten(String text) {
        if (text.endsWith(".*")) return text;
        return text.equals(Anchors.display(Anchors.CONFIG, text)) ? null : text;
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

    private static String bare(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }
}
