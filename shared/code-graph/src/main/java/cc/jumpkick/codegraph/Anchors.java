// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Anchor nodes: one node per surface that code and files no compiler checks both name (a
 * configuration key, an environment variable, an HTTP route, a SQL table or column, a CLI command
 * or flag), with a {@link Relations#DEFINES} or {@link Relations#USES} edge from every file or
 * symbol that names it, graded by its {@link Confidence}. An anchor's id is {@code <kind>:<key>}:
 * the key is the name folded so that every spelling the surface itself treats as one is one id,
 * and depends on nothing but that name, so a file's fragment carries its anchors and is cached
 * alone. Kinds are open: a kind this class does not list keys its name trimmed. Nothing in an id
 * names a language. The grammar is in {@code docs/contributors/architecture.md}.
 */
public final class Anchors {

    private Anchors() {}

    /** The {@code fileType} of an anchor node. */
    public static final String FILE_TYPE = "anchor";

    public static final String CONFIG = "config";
    public static final String ENV = "env";
    public static final String ROUTE = "route";
    public static final String TABLE = "table";
    public static final String COLUMN = "column";
    public static final String CLI = "cli";

    /** The kinds whose key is written down here; any other kind is keyed by its name trimmed. */
    public static final Set<String> KINDS = Set.of(CONFIG, ENV, ROUTE, TABLE, COLUMN, CLI);

    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9.-]*");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern METHOD =
            Pattern.compile("(?i)(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|\\*)\\s+(.*)");
    private static final Pattern VARIABLE = Pattern.compile("^(?:\\{[^}]*}|:[A-Za-z_]\\w*|<[^>]*>)$");
    private static final Pattern FLAG_VALUE = Pattern.compile("^(-{1,2}[^=\\s]+)=.*$");

    /** The id of the anchor {@code name} of {@code kind}. */
    public static String id(String kind, String name) {
        if (!KIND.matcher(kind).matches()) throw new IllegalArgumentException("not an anchor kind: '" + kind + "'");
        return kind + ":" + key(kind, name);
    }

    /** The key of {@code name}: what two spellings share when the surface treats them as one. */
    public static String key(String kind, String name) {
        String n = name.strip();
        return switch (kind) {
            case CONFIG -> relaxed(n);
            case ROUTE -> route(n);
            case TABLE -> sqlName(n);
            case COLUMN -> column(n);
            case CLI -> cli(n);
            default -> n;
        };
    }

    /** The name an answer shows for {@code name}; the key for every kind but {@code config}, whose key drops separators. */
    public static String display(String kind, String name) {
        return kind.equals(CONFIG) ? kebab(name.strip()) : key(kind, name);
    }

    /** True when {@code n} is an anchor node. */
    public static boolean is(@Nullable GraphNode n) {
        return n != null && n.fileType().equals(FILE_TYPE);
    }

    /** The kind of anchor {@code id}, {@code null} when it is not an anchor id. */
    public static @Nullable String kindOf(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0) return null;
        String kind = id.substring(0, colon);
        return KIND.matcher(kind).matches() ? kind : null;
    }

    /**
     * The anchor id {@code text} names when it is written {@code <kind>:<name>} with a kind this class
     * lists ({@code route:GET /orders/{id}}); {@code null} otherwise, so a {@code Type:member} or a
     * path is never read as one.
     */
    public static @Nullable String parse(String text) {
        String t = text.strip();
        String kind = kindOf(t);
        if (kind == null || !KINDS.contains(kind) || t.length() == kind.length() + 1) return null;
        return id(kind, t.substring(kind.length() + 1));
    }

    /**
     * The kind and name glob of a listing address: {@code route:*} (every route) or {@code
     * route:GET /owners/*} (the routes the glob fits), a kind this class lists with a {@code *} in its
     * name; {@code null} for anything else. The glob is {@code null} for a bare {@code *}.
     */
    public static String @Nullable [] listing(String text) {
        String t = text.strip();
        String kind = kindOf(t);
        if (kind == null || !KINDS.contains(kind)) return null;
        String name = t.substring(kind.length() + 1).strip();
        if (!name.contains("*")) return null;
        return new String[] {kind, name.equals("*") ? "" : name};
    }

    /** The node of anchor {@code name} of {@code kind}: no source file, its places are its edges. */
    public static GraphNode node(String kind, String name) {
        return new GraphNode(id(kind, name), display(kind, name), FILE_TYPE, "", "", Map.of("node_kind", kind));
    }

    /**
     * An edge {@code relation} ({@link Relations#DEFINES} or {@link Relations#USES}) from {@code source}
     * to anchor {@code name} of {@code kind}, found at {@code line} of {@code file}; its context is the
     * name as written when the anchor shows it otherwise ({@code {id}} for {@code {}}).
     */
    public static GraphEdge edge(
            String source, String kind, String name, String relation, Confidence grade, String file, int line) {
        GraphEdge e = GraphEdge.of(source, id(kind, name), relation, grade, file, line);
        String written = name.strip();
        return written.equals(display(kind, name)) ? e : e.withContext(written);
    }

    /**
     * Of two nodes for one anchor id, the one to keep: the longer label (a config key's kebab spelling
     * over its run-together one), else the lesser, so the merge is the same in every fragment order.
     */
    public static GraphNode preferred(GraphNode a, GraphNode b) {
        int byLength = Integer.compare(b.label().length(), a.label().length());
        if (byLength != 0) return byLength < 0 ? b : a;
        return a.label().compareTo(b.label()) <= 0 ? a : b;
    }

    /** A config key as Spring's relaxed binding compares it: case, {@code -} and {@code _} ignored. */
    public static String relaxed(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /** {@code shop.maxItems} and {@code shop.max_items} as {@code shop.max-items}. */
    static String kebab(String key) {
        return CAMEL.matcher(key).replaceAll("-").replace('_', '-').toLowerCase(Locale.ROOT);
    }

    /**
     * {@code METHOD /path}: the method upper-cased, {@code *} when none is written; the path with one
     * leading {@code /}, no query or fragment, no empty segment, no trailing {@code /}, and every
     * variable segment as {@code {}}.
     */
    static String route(String name) {
        String method = "*";
        String path = name;
        Matcher m = METHOD.matcher(name);
        if (m.matches()) {
            method = m.group(1).toUpperCase(Locale.ROOT);
            path = m.group(2).strip();
        }
        int cut = indexOfAny(path, '?', '#');
        if (cut >= 0) path = path.substring(0, cut);
        List<String> segments = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.isEmpty()) continue;
            segments.add(VARIABLE.matcher(s).matches() ? "{}" : s);
        }
        return method + " /" + String.join("/", segments);
    }

    /** Schemas a table sits in when none is written: {@code public.orders} is {@code orders}. */
    private static final Set<String> DEFAULT_SCHEMAS = Set.of("public", "dbo");

    /**
     * One SQL identifier path: unquoted parts lower-cased, quoted ones kept, quotes dropped, and a
     * default schema ({@code public}, {@code dbo}) dropped, since a query rarely writes it.
     */
    static String sqlName(String name) {
        List<String> parts = new ArrayList<>();
        for (String p : name.split("\\.")) {
            String s = p.strip();
            boolean quoted = s.length() >= 2
                    && (s.startsWith("\"") && s.endsWith("\"")
                            || s.startsWith("`") && s.endsWith("`")
                            || s.startsWith("[") && s.endsWith("]"));
            parts.add(quoted ? s.substring(1, s.length() - 1) : s.toLowerCase(Locale.ROOT));
        }
        if (parts.size() > 1 && DEFAULT_SCHEMAS.contains(parts.getFirst())) parts.removeFirst();
        return String.join(".", parts);
    }

    /** {@code table.column}, each part as {@link #sqlName} keys it. */
    static String column(String name) {
        return sqlName(name);
    }

    /** The command path and flag, whitespace collapsed and a flag's {@code =value} dropped. */
    static String cli(String name) {
        List<String> words = new ArrayList<>();
        for (String w : name.strip().split("\\s+")) {
            if (w.isEmpty()) continue;
            Matcher f = FLAG_VALUE.matcher(w);
            words.add(f.matches() ? f.group(1) : w);
        }
        return String.join(" ", words);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }
}
