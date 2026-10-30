// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A declaration's facts, held on its node under the {@code jk} key of its extra fields.
 *
 * <p>Keys: {@code kind} ({@code class}, {@code interface}, {@code enum}, {@code record}, {@code
 * annotation}, {@code object}, {@code trait}, {@code method}, {@code constructor}, {@code function},
 * {@code property}, {@code constant}), {@code vis} ({@code public}, {@code protected}, {@code
 * internal}, {@code package}, {@code private}), {@code end} (last line, 1-based), {@code ann}
 * (annotation simple names, sorted), {@code doc} (the doc comment's first sentence), {@code more}
 * (the {@code [line, end]} of each further overload sharing the node), {@code ext} and {@code impl}
 * (a type's superclass and interfaces, qualified where known).
 */
public final class Decl {

    private Decl() {}

    /** The node key the facts live under. */
    public static final String KEY = "jk";

    public static final String KIND = "kind";
    public static final String VIS = "vis";
    public static final String END = "end";
    public static final String ANN = "ann";
    public static final String DOC = "doc";
    public static final String MORE = "more";
    public static final String EXT = "ext";
    public static final String IMPL = "impl";

    /** Visibilities, most visible first. */
    public static final List<String> VISIBILITIES = List.of("public", "protected", "internal", "package", "private");

    /** Kinds that declare a type. */
    public static final List<String> TYPE_KINDS =
            List.of("class", "interface", "enum", "record", "annotation", "object", "trait");

    /** The facts on {@code n}; empty when it has none. */
    public static Map<String, Object> of(GraphNode n) {
        return n.extra().get(KEY) instanceof Map<?, ?> m ? strings(m) : Map.of();
    }

    public static @Nullable String kind(GraphNode n) {
        return of(n).get(KIND) instanceof String s ? s : null;
    }

    public static @Nullable String vis(GraphNode n) {
        return of(n).get(VIS) instanceof String s ? s : null;
    }

    /** The last line, 0 when unknown. */
    public static int end(GraphNode n) {
        return of(n).get(END) instanceof Number e ? e.intValue() : 0;
    }

    public static List<String> annotations(GraphNode n) {
        return strings(n, ANN);
    }

    /** The superclass as written or compiled; empty when only {@code Object}. */
    public static List<String> superclass(GraphNode n) {
        return strings(n, EXT);
    }

    public static List<String> interfaces(GraphNode n) {
        return strings(n, IMPL);
    }

    private static List<String> strings(GraphNode n, String key) {
        List<String> out = new ArrayList<>();
        if (of(n).get(key) instanceof List<?> l) l.forEach(a -> out.add(String.valueOf(a)));
        return out;
    }

    /** {@code [line, end]} of every overload after the first. */
    public static List<int[]> more(GraphNode n) {
        List<int[]> out = new ArrayList<>();
        if (of(n).get(MORE) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof List<?> pair
                        && pair.size() == 2
                        && pair.get(0) instanceof Number a
                        && pair.get(1) instanceof Number b) {
                    out.add(new int[] {a.intValue(), b.intValue()});
                }
            }
        }
        return out;
    }

    public static @Nullable String doc(GraphNode n) {
        return of(n).get(DOC) instanceof String s ? s : null;
    }

    /** The 1-based first line of {@code n}, 0 when unknown. */
    public static int line(GraphNode n) {
        String loc = n.sourceLocation();
        if (loc.length() < 2 || loc.charAt(0) != 'L') return 0;
        try {
            return Integer.parseInt(loc.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isType(GraphNode n) {
        String kind = kind(n);
        return kind != null && TYPE_KINDS.contains(kind);
    }

    /** {@code n} with {@code facts} merged into its own by {@link #merge}. */
    public static GraphNode with(GraphNode n, Map<String, Object> facts) {
        if (facts.isEmpty()) return n;
        Map<String, Object> extra = new LinkedHashMap<>(n.extra());
        extra.put(KEY, merge(of(n), facts));
        return new GraphNode(n.id(), n.label(), n.fileType(), n.sourceFile(), n.sourceLocation(), extra);
    }

    /**
     * Two readings of one declaration combined, whichever came first: the more specific kind (a
     * scanner's {@code object} over the class file's {@code class}), the more visible visibility (a
     * class file knows an interface method is public where the source says nothing) unless either
     * says {@code internal}, the later end line, and every annotation either saw.
     */
    public static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        Map<String, Object> out = new LinkedHashMap<>(a);
        b.forEach(out::putIfAbsent);
        String ka = str(a, KIND);
        String kb = str(b, KIND);
        if (ka != null && kb != null) out.put(KIND, generic(ka) ? kb : ka);
        String va = str(a, VIS);
        String vb = str(b, VIS);
        if (va != null && vb != null) {
            String vis = va.equals("internal") || vb.equals("internal")
                    ? "internal"
                    : VISIBILITIES.indexOf(va) <= VISIBILITIES.indexOf(vb) ? va : vb;
            out.put(VIS, vis);
        }
        if (a.get(END) instanceof Number ea && b.get(END) instanceof Number eb) {
            out.put(END, Math.max(ea.intValue(), eb.intValue()));
        }
        for (String key : List.of(ANN, EXT, IMPL)) {
            if (a.get(key) instanceof List<?> aa && b.get(key) instanceof List<?> ab) {
                TreeSet<String> all = new TreeSet<>();
                aa.forEach(x -> all.add(String.valueOf(x)));
                ab.forEach(x -> all.add(String.valueOf(x)));
                out.put(key, List.copyOf(all));
            }
        }
        return out;
    }

    /** The visibility a modifier list names, or {@code fallback}. */
    public static String visibility(Iterable<String> modifiers, String fallback) {
        for (String m : modifiers) {
            String w = m.toLowerCase(Locale.ROOT);
            if (VISIBILITIES.contains(w) && !w.equals("package")) return w;
        }
        return fallback;
    }

    private static boolean generic(String kind) {
        return kind.equals("class") || kind.equals("method");
    }

    private static @Nullable String str(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? s : null;
    }

    private static Map<String, Object> strings(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
