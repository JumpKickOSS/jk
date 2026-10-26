// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code find_symbols}: the declarations that pass every filter given, one line each. Filters take
 * comma-separated alternatives and match case-insensitively; a supertype or annotation matches by
 * simple or qualified name.
 *
 * @param kinds declaration kinds, plus {@code type} for every type kind and {@code member} for
 *     methods, functions and constructors
 * @param visibilities {@code public}, {@code protected}, {@code internal}, {@code package}, {@code private}
 * @param extendsType a superclass anywhere up the superclass chain
 * @param implementsType a supertype anywhere above, through superclasses and interfaces
 * @param annotated an annotation on the declaration
 * @param name a glob over the declaration's name ({@code *Service}, {@code get*})
 */
public record SymbolFinder(
        List<String> kinds,
        List<String> visibilities,
        List<String> extendsType,
        List<String> implementsType,
        List<String> annotated,
        @Nullable String name) {

    private static final List<String> MEMBER_KINDS = List.of("method", "function", "constructor", "property");

    public SymbolFinder {
        kinds = lower(kinds);
        visibilities = lower(visibilities);
        extendsType = lower(extendsType);
        implementsType = lower(implementsType);
        annotated = lower(annotated).stream()
                .map(a -> a.startsWith("@") ? a.substring(1) : a)
                .toList();
    }

    /** The filters {@code options} name ({@code kind}, {@code visibility}, …), each a comma list. */
    public static SymbolFinder of(Map<String, String> options, @Nullable String name) {
        return new SymbolFinder(
                split(options.get("kind")),
                split(options.get("visibility")),
                split(options.get("extends")),
                split(options.get("implements")),
                split(options.get("annotated")),
                name == null || name.isBlank() ? null : name.strip());
    }

    public boolean empty() {
        return kinds.isEmpty()
                && visibilities.isEmpty()
                && extendsType.isEmpty()
                && implementsType.isEmpty()
                && annotated.isEmpty()
                && name == null;
    }

    /** The matches in {@code graph} inside {@code scope}, by file and line, within {@code budget} tokens. */
    public String find(CodeGraph graph, Scope scope, int budget) {
        if (empty()) {
            throw new IllegalArgumentException(
                    "find needs a filter: a name glob, kind, visibility, extends, implements" + " or annotated");
        }
        Map<String, GraphNode> types = new HashMap<>();
        for (GraphNode n : graph.nodes()) {
            if (n.extra().get(Linker.QUALIFIED_NAME) instanceof String q) types.put(dotted(q), n);
        }
        Pattern glob = name == null ? null : namePattern(name);
        List<GraphNode> hits = new ArrayList<>();
        for (GraphNode n : graph.nodes()) {
            String kind = Decl.kind(n);
            if (kind == null || n.sourceFile().isEmpty() || !scope.contains(n)) continue;
            if (!kinds.isEmpty() && !kindMatches(kind)) continue;
            String vis = Decl.vis(n);
            if (!visibilities.isEmpty() && (vis == null || !visibilities.contains(vis))) continue;
            if (!annotated.isEmpty() && Decl.annotations(n).stream().noneMatch(a -> named(a, annotated))) continue;
            if (glob != null && !glob.matcher(plain(n.label())).matches()) continue;
            // A member query filters a member by its type's supertypes: `total -t method -I Shop` is
            // every override. Any other query keeps supertype filters to types.
            if (!extendsType.isEmpty() || !implementsType.isEmpty()) {
                GraphNode holder = Decl.isType(n) ? n : memberQuery() ? owner(graph, n) : null;
                if (holder == null) continue;
                if (!extendsType.isEmpty()
                        && !supertypes(holder, types, false).stream().anyMatch(s -> named(s, extendsType))) {
                    continue;
                }
                if (!implementsType.isEmpty()
                        && !supertypes(holder, types, true).stream().anyMatch(s -> named(s, implementsType))) {
                    continue;
                }
            }
            hits.add(n);
        }
        hits.sort(Comparator.comparing(GraphNode::sourceFile)
                .thenComparingInt(Decl::line)
                .thenComparing(GraphNode::id));
        List<String> lines = new ArrayList<>();
        lines.add(hits.size() == 1 ? "1 match" : hits.size() + " matches");
        for (GraphNode n : hits) {
            String vis = Decl.vis(n);
            String head = Decl.kind(n) + (vis == null ? "" : " " + vis) + "  " + display(graph, n) + "  "
                    + n.sourceFile() + ":";
            lines.add(head + Decl.line(n) + (Decl.end(n) > 0 ? "-" + Decl.end(n) : ""));
            // Overloads share one node; each is listed at its own lines.
            for (int[] more : Decl.more(n)) lines.add(head + more[0] + "-" + more[1]);
        }
        return Budget.lines(lines, budget, "Narrow with scope or another filter");
    }

    /** Whether the kinds asked for are members only ({@code method}, {@code member}, …). */
    private boolean memberQuery() {
        return !kinds.isEmpty() && kinds.stream().allMatch(k -> k.equals("member") || MEMBER_KINDS.contains(k));
    }

    private boolean kindMatches(String kind) {
        if (kinds.contains(kind)) return true;
        if (kinds.contains("type") && Decl.TYPE_KINDS.contains(kind)) return true;
        return kinds.contains("member") && MEMBER_KINDS.contains(kind);
    }

    /**
     * Every supertype name above {@code n}: up the superclass chain only, or through interfaces too
     * when {@code all}. A supertype the workspace declares is followed; one it does not ends the climb.
     */
    private static Set<String> supertypes(GraphNode n, Map<String, GraphNode> types, boolean all) {
        Set<String> out = new HashSet<>();
        Deque<GraphNode> todo = new ArrayDeque<>(List.of(n));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            GraphNode t = todo.poll();
            if (!seen.add(t.id())) continue;
            List<String> names = new ArrayList<>(Decl.superclass(t));
            if (all) names.addAll(Decl.interfaces(t));
            for (String s : names) {
                String d = dotted(s);
                out.add(d);
                GraphNode declared = types.get(d);
                if (declared != null) todo.add(declared);
            }
        }
        return out;
    }

    /**
     * Whether {@code qualified} is one of {@code wanted}: by its whole name, its simple name, or a
     * dotted tail ({@code Outer.Inner} for a nested type); a name written unqualified in source
     * matches a wanted name that ends in it.
     */
    private static boolean named(String qualified, List<String> wanted) {
        String q = dotted(qualified).toLowerCase(Locale.ROOT);
        String simple = q.substring(q.lastIndexOf('.') + 1);
        for (String w : wanted) {
            if (w.equals(q) || w.equals(simple) || q.endsWith("." + w)) return true;
            if (!q.contains(".") && w.endsWith("." + q)) return true;
        }
        return false;
    }

    /** The type that declares member {@code n}, by its {@code method} or {@code contains} edge. */
    private static @Nullable GraphNode owner(CodeGraph graph, GraphNode n) {
        for (GraphEdge e : graph.incident(n.id())) {
            if (e.target().equals(n.id())
                    && (e.relation().equals(Relations.METHOD) || e.relation().equals(Relations.CONTAINS))) {
                GraphNode o = graph.node(e.source());
                if (o != null && Decl.isType(o)) return o;
            }
        }
        return null;
    }

    private static String display(CodeGraph graph, GraphNode n) {
        if (n.extra().get(Linker.QUALIFIED_NAME) instanceof String q) return dotted(q);
        for (GraphEdge e : graph.incident(n.id())) {
            if (e.target().equals(n.id())
                    && (e.relation().equals(Relations.METHOD) || e.relation().equals(Relations.CONTAINS))) {
                GraphNode owner = graph.node(e.source());
                if (owner != null && Decl.isType(owner)) return owner.label() + "." + plain(n.label());
            }
        }
        return plain(n.label());
    }

    private static String dotted(String name) {
        String s = name.replace('$', '.');
        int angle = s.indexOf('<');
        return angle < 0 ? s : s.substring(0, angle);
    }

    private static String plain(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }

    /** A name glob ({@code *} any run, {@code ?} one character) as a case-insensitive whole-name pattern. */
    public static Pattern namePattern(String g) {
        StringBuilder re = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') re.append(".*");
            else if (c == '?') re.append('.');
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static List<String> split(@Nullable String list) {
        if (list == null || list.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : list.split(",")) {
            if (!p.isBlank()) out.add(p.strip());
        }
        return out;
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(v -> v.strip().toLowerCase(Locale.ROOT)).toList();
    }
}
