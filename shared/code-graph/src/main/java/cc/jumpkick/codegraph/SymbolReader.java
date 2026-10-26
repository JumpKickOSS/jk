// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code read_symbol}: the source of a symbol, a type's members or a set of files, in the richest
 * view that fits a token budget. Views, richest first: {@code content} (the numbered source of each
 * span), {@code structure} (declarations without bodies) and {@code headline} (one line per symbol).
 *
 * <p>An address is one or more targets separated by {@code ;}, each optionally followed by {@code
 * #line=N,M}:
 *
 * <ul>
 *   <li>a symbol: {@code OrderService}, {@code OrderService.total}, {@code com.acme.OrderService}
 *   <li>a type's direct members, {@code OrderService.*}, or every declaration nested in it, {@code
 *       OrderService.**}
 *   <li>a file or path glob from the workspace root: {@code src/main/java/com/acme/Shop.java}, {@code
 *       server/engine/**}{@code /*.java}, with {@code !} excluding
 * </ul>
 */
public final class SymbolReader {

    /** A representation, richest first. */
    public enum View {
        CONTENT,
        STRUCTURE,
        HEADLINE;

        /** The view {@code name} spells; {@code null} or {@code auto} for the richest that fits. */
        public static @Nullable View of(@Nullable String name) {
            if (name == null || name.isBlank() || name.equalsIgnoreCase("auto")) return null;
            return switch (name.strip().toLowerCase(Locale.ROOT)) {
                case "content" -> CONTENT;
                case "structure" -> STRUCTURE;
                case "headline", "headlines" -> HEADLINE;
                default ->
                    throw new IllegalArgumentException(
                            "view must be content, structure or headline, got '" + name + "'");
            };
        }
    }

    /** Lines a span without a recorded end may run before it is cut. */
    private static final int OPEN_SPAN = 200;

    private static final int HEADER_LINES = 6;

    private static final Pattern LINES = Pattern.compile("#line=(\\d+)(?:,(\\d+))?$");

    private final CodeGraph graph;
    private final Scope scope;
    private final Function<String, @Nullable String> files;
    private final Function<String, @Nullable String> library;

    /** A reader over {@code graph} whose source text {@code files} returns by repo-relative path. */
    public SymbolReader(CodeGraph graph, Scope scope, Function<String, @Nullable String> files) {
        this(graph, scope, files, name -> null);
    }

    /**
     * A reader that answers a single symbol the workspace does not declare from {@code library}: the
     * locked jars' description of the type or member it names, {@code null} when none declares it.
     */
    public SymbolReader(
            CodeGraph graph,
            Scope scope,
            Function<String, @Nullable String> files,
            Function<String, @Nullable String> library) {
        this.graph = graph;
        this.scope = scope;
        this.files = files;
        this.library = library;
    }

    /** One span to show: a node, or a whole file when {@code node} is null, cut to {@code from..to}. */
    record Span(@Nullable GraphNode node, String file, int from, int to) {}

    /** The answer for {@code address} in {@code view} ({@code null} picks one), within {@code budget} tokens. */
    public String read(String address, @Nullable View view, int budget) {
        List<Span> spans = new ArrayList<>();
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (String raw : address.split(";")) {
            String target = raw.strip();
            if (target.isEmpty()) continue;
            if (target.startsWith("!")) {
                String not = target.substring(1).strip();
                excludes.add(not.contains("/") ? not : "**/" + not);
                continue;
            }
            int from = 0;
            int to = Integer.MAX_VALUE;
            Matcher lines = LINES.matcher(target);
            if (lines.find()) {
                from = Integer.parseInt(lines.group(1));
                to = lines.group(2) == null ? from : Integer.parseInt(lines.group(2));
                target = target.substring(0, lines.start()).strip();
            }
            if (isPath(target)) {
                if (!target.contains("/")) target = "**/" + target;
                includes.add(target);
                if (from > 0) {
                    for (String file : files(target, List.of())) spans.add(new Span(null, file, from, to));
                    includes.removeLast();
                }
                continue;
            }
            String error = symbols(target, from, to, spans);
            if (error != null && error.startsWith("No symbol") && spans.isEmpty() && !address.contains(";")) {
                String api = library.apply(target);
                if (api != null) return Budget.lines(api.lines().toList(), budget, "Name one member: Type.member");
            }
            if (error != null) return error;
        }
        if (!includes.isEmpty()) {
            for (String file : files(String.join(";", includes), excludes)) {
                spans.add(new Span(null, file, 1, Integer.MAX_VALUE));
            }
        }
        if (spans.isEmpty()) return "Nothing at '" + address.strip() + "'.";
        List<Span> unique = new ArrayList<>(new LinkedHashSet<>(spans));
        return render(unique, view, budget);
    }

    /** Whether a target names files rather than a symbol. */
    static boolean isPath(String target) {
        if (target.contains("/") || target.contains("*") && !target.endsWith(".*") && !target.endsWith(".**")) {
            return true;
        }
        int dot = target.lastIndexOf('.');
        if (dot < 0 || target.endsWith(".*") || target.endsWith(".**")) return false;
        String ext = target.substring(dot + 1);
        return Set.of(
                        "java",
                        "kt",
                        "kts",
                        "groovy",
                        "gradle",
                        "md",
                        "adoc",
                        "yml",
                        "yaml",
                        "properties",
                        "toml",
                        "xml",
                        "json",
                        "ts",
                        "tsx",
                        "js",
                        "jsx",
                        "vue",
                        "html",
                        "css",
                        "scss",
                        "sql")
                .contains(ext.toLowerCase(Locale.ROOT));
    }

    /** The files a glob list names, in scope, sorted. */
    private List<String> files(String globs, List<String> excludes) {
        String spec = globs + (excludes.isEmpty() ? "" : ";!" + String.join(";!", excludes));
        Scope paths = Scope.of(spec, List.of(), null, graph.modules());
        Set<String> out = new TreeSet<>();
        for (GraphNode n : graph.nodes()) {
            String f = n.sourceFile();
            if (!f.isEmpty() && n.id().equals(GraphIds.file(f)) && paths.containsPath(f) && scope.containsPath(f)) {
                out.add(f);
            }
        }
        return List.copyOf(out);
    }

    /** Adds the spans a symbol target names; returns the refusal when it names none or too many. */
    private @Nullable String symbols(String target, int from, int to, List<Span> spans) {
        boolean all = target.endsWith(".**");
        boolean members = all || target.endsWith(".*");
        String name = all
                ? target.substring(0, target.length() - 3)
                : members ? target.substring(0, target.length() - 2) : target;
        List<String> found = resolve(name);
        if (found.isEmpty()) return "No symbol '" + name + "'.";
        if (found.size() > 1) {
            List<String> lines = new ArrayList<>();
            lines.add("'" + name + "' names " + found.size() + " symbols; qualify it (Type.member or the package):");
            for (String id : found.subList(0, Math.min(found.size(), 10))) {
                GraphNode n = graph.node(id);
                if (n != null) lines.add("  " + qualified(n) + "  " + n.sourceFile() + ":" + Decl.line(n));
            }
            return String.join("\n", lines);
        }
        GraphNode n = graph.node(found.getFirst());
        if (n == null) return "No symbol '" + name + "'.";
        if (!members) {
            spans.addAll(spans(n, from, to));
            return null;
        }
        List<GraphNode> kids = all ? descendants(n) : children(n);
        if (kids.isEmpty()) return "'" + name + "' declares no members.";
        for (GraphNode k : kids) spans.addAll(spans(k, from, to));
        return null;
    }

    /** {@code n}'s span and each further overload's, cut to {@code from..to}; empty when none overlaps. */
    private List<Span> spans(GraphNode n, int from, int to) {
        List<int[]> ranges = new ArrayList<>();
        ranges.add(new int[] {Math.max(Decl.line(n), 1), end(n)});
        ranges.addAll(Decl.more(n));
        List<Span> out = new ArrayList<>();
        for (int[] r : ranges) {
            int a = Math.max(r[0], from);
            int b = Math.min(r[1], to);
            if (a <= b) out.add(new Span(n, n.sourceFile(), a, b));
        }
        return out;
    }

    /**
     * Ids of the code nodes {@code name} names: a node id; a qualified name ({@code com.acme.Shop},
     * {@code com.acme.Shop.total}); {@code Type.member}; else a label. Scope narrows a name several
     * modules declare.
     */
    List<String> resolve(String name) {
        String wanted = name.strip();
        if (graph.has(wanted)) return List.of(wanted);
        List<String> qualified = new ArrayList<>();
        List<String> labelled = new ArrayList<>();
        String bare = wanted.replace('$', '.');
        int dot = bare.lastIndexOf('.');
        for (GraphNode n : graph.nodes()) {
            if (!n.fileType().equals("code") || n.sourceFile().isEmpty()) continue;
            if (n.extra().get(Linker.QUALIFIED_NAME) instanceof String q
                    && q.replace('$', '.').equals(bare)) {
                qualified.add(n.id());
            } else if (dot < 0 && plain(n.label()).equals(bare) && !"constructor".equals(Decl.kind(n))) {
                labelled.add(n.id());
            }
        }
        if (!qualified.isEmpty()) return narrow(qualified);
        if (dot > 0) {
            List<String> owners = resolve(bare.substring(0, dot));
            String member = bare.substring(dot + 1);
            List<String> out = new ArrayList<>();
            for (String owner : owners) {
                GraphNode o = graph.node(owner);
                if (o == null) continue;
                for (GraphNode k : children(o)) {
                    if (plain(k.label()).equals(member)) out.add(k.id());
                }
            }
            return narrow(out);
        }
        return narrow(labelled);
    }

    private List<String> narrow(List<String> ids) {
        List<String> in =
                ids.stream().filter(id -> scope.contains(graph.node(id))).toList();
        List<String> pick = in.isEmpty() ? ids : in;
        return pick.stream().sorted().toList();
    }

    /** {@code .total()} and {@code total()} are {@code total}. */
    private static String plain(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }

    /** The declarations directly inside {@code n}, in source order. */
    List<GraphNode> children(GraphNode n) {
        List<GraphNode> out = new ArrayList<>();
        for (GraphEdge e : graph.incident(n.id())) {
            if (!e.source().equals(n.id())) continue;
            if (!e.relation().equals(Relations.METHOD) && !e.relation().equals(Relations.CONTAINS)) continue;
            GraphNode k = graph.node(e.target());
            if (k != null && k.sourceFile().equals(n.sourceFile()) && !k.id().equals(n.id())) out.add(k);
        }
        out.sort(Comparator.comparingInt(Decl::line).thenComparing(GraphNode::id));
        return out.stream().distinct().toList();
    }

    private List<GraphNode> descendants(GraphNode n) {
        List<GraphNode> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collect(n, out, seen);
        return out;
    }

    private void collect(GraphNode n, List<GraphNode> out, Set<String> seen) {
        for (GraphNode k : children(n)) {
            if (!seen.add(k.id())) continue;
            out.add(k);
            collect(k, out, seen);
        }
    }

    /** {@code n}'s last line: recorded, else the line before the next declaration in its file. */
    private int end(GraphNode n) {
        int end = Decl.end(n);
        int start = Decl.line(n);
        if (end >= start && end > 0) return end;
        int next = Integer.MAX_VALUE;
        for (GraphNode other : graph.nodes()) {
            if (!other.sourceFile().equals(n.sourceFile()) || other.id().equals(n.id())) continue;
            int l = Decl.line(other);
            if (l > start && l < next && other.fileType().equals(n.fileType())) next = l;
        }
        return next == Integer.MAX_VALUE ? start + OPEN_SPAN : Math.min(next - 1, start + OPEN_SPAN);
    }

    private String render(List<Span> spans, @Nullable View wanted, int budget) {
        Map<String, List<String>> text = new LinkedHashMap<>();
        int chars = budget * Budget.CHARS_PER_TOKEN;
        if (wanted != null) return finish(draw(spans, wanted, text), wanted, null, 0, budget);
        int richest = 0;
        for (View v : View.values()) {
            String out = draw(spans, v, text);
            if (v == View.CONTENT) richest = out.length();
            if (out.length() <= chars || v == View.HEADLINE) {
                return finish(out, v, v == View.CONTENT ? null : View.CONTENT, richest, budget);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** {@code body} under the budget, saying so when a richer view did not fit. */
    private static String finish(String body, View view, @Nullable View richer, int richerChars, int budget) {
        String out = body;
        if (richer != null) {
            out = "[i] " + name(view) + " view: " + name(richer) + " needs ~" + (richerChars / Budget.CHARS_PER_TOKEN)
                    + " tokens, over the ~" + budget + "-token budget. Raise the budget (CLI -b, MCP token_budget)"
                    + " or narrow the address (Type.member, #line=N,M).\n\n" + body;
        }
        return Budget.lines(out.lines().toList(), budget, "Narrow the address or raise the budget");
    }

    private static String name(View v) {
        return v.name().toLowerCase(Locale.ROOT);
    }

    private String draw(List<Span> spans, View view, Map<String, List<String>> text) {
        List<String> out = new ArrayList<>();
        for (Span s : spans) {
            switch (view) {
                case CONTENT -> content(s, text, out);
                case STRUCTURE -> structure(s, text, out);
                case HEADLINE -> headline(s, out);
            }
        }
        return String.join("\n", out);
    }

    private void content(Span s, Map<String, List<String>> text, List<String> out) {
        List<String> lines = lines(s.file(), text);
        int from = Math.max(1, s.from());
        int to = Math.min(lines.size(), s.to());
        out.add(title(s, from, to));
        if (lines.isEmpty()) {
            out.add("  (source not readable)");
            return;
        }
        int width = String.valueOf(to).length();
        for (int i = from; i <= to; i++) out.add(number(i, width) + lines.get(i - 1));
        out.add("");
    }

    private void structure(Span s, Map<String, List<String>> text, List<String> out) {
        List<String> lines = lines(s.file(), text);
        List<GraphNode> roots = new ArrayList<>();
        if (s.node() != null) {
            if (s.from() != Math.max(Decl.line(s.node()), 1)
                    && Decl.more(s.node()).stream().anyMatch(r -> r[0] == s.from())) {
                return;
            }
            roots.add(s.node());
        } else {
            GraphNode file = graph.node(GraphIds.file(s.file()));
            if (file != null) roots.addAll(children(file));
            if (roots.isEmpty()) {
                out.add(s.file() + " (" + lines.size() + " lines)");
                return;
            }
            out.add(s.file());
        }
        int width = String.valueOf(Math.max(lines.size(), 1)).length();
        for (GraphNode r : roots) declarations(r, lines, width, "", out, new LinkedHashSet<>());
        out.add("");
    }

    private void declarations(
            GraphNode n, List<String> lines, int width, String indent, List<String> out, Set<String> seen) {
        if (!seen.add(n.id())) return;
        int line = Decl.line(n);
        List<String> ann = Decl.annotations(n);
        if (!ann.isEmpty()) out.add(" ".repeat(width + 2) + indent + "@" + String.join(" @", ann));
        String where = indent.isEmpty() ? "  " + n.sourceFile() + ":" + line + "-" + end(n) : "";
        out.add(number(line, width) + indent + header(n, line, lines) + where);
        for (int[] more : Decl.more(n)) out.add(number(more[0], width) + indent + header(n, more[0], lines));
        String kind = Decl.kind(n);
        if (kind != null && !Decl.isType(n)) return;
        for (GraphNode k : children(n)) declarations(k, lines, width, indent + "  ", out, seen);
    }

    /**
     * The declaration of {@code n} at {@code line}: a JVM declaration from that line up to its body,
     * joined onto one line; anything else, that line.
     */
    private static String header(GraphNode n, int line, List<String> lines) {
        if (line < 1 || line > lines.size()) return n.label();
        if (Decl.kind(n) == null) return lines.get(line - 1).strip();
        StringBuilder out = new StringBuilder();
        for (int i = line; i <= Math.min(lines.size(), line + HEADER_LINES - 1); i++) {
            String l = lines.get(i - 1).strip();
            int brace = l.indexOf('{');
            int semi = l.indexOf(';');
            int cut = brace >= 0 && (semi < 0 || brace < semi) ? brace : semi;
            if (!out.isEmpty()) out.append(' ');
            out.append(cut >= 0 ? l.substring(0, cut).strip() : l);
            if (cut >= 0 || l.endsWith("=") || l.contains(" = ")) break;
        }
        String s = out.toString().replaceAll("\\s+", " ");
        if (s.endsWith("=")) s = s.substring(0, s.length() - 1).strip();
        int eq = s.indexOf(" = ");
        if (eq > 0 && !Decl.isType(n)) s = s.substring(0, eq);
        return s.length() > 240 ? s.substring(0, 237) + "..." : s;
    }

    private void headline(Span s, List<String> out) {
        GraphNode n = s.node();
        if (n == null) {
            GraphNode file = graph.node(GraphIds.file(s.file()));
            int symbols = file == null ? 0 : descendants(file).size();
            out.add(s.file()
                    + (symbols == 0 ? "" : symbols == 1 ? "  (1 declaration)" : "  (" + symbols + " declarations)"));
            return;
        }
        String kind = Decl.kind(n);
        String vis = Decl.vis(n);
        String facts = kind == null ? "" : " [" + kind + (vis == null ? "" : " " + vis) + "]";
        // Overloads share one node: each span prints at its own lines, and the doc is the first one's.
        int line = Math.max(Decl.line(n), 1);
        String doc = s.from() <= line && line <= s.to() ? Decl.doc(n) : null;
        out.add(qualified(n) + facts + "  " + n.sourceFile() + ":" + s.from() + "-" + s.to()
                + (doc == null ? "" : "  " + doc));
    }

    private String title(Span s, int from, int to) {
        GraphNode n = s.node();
        String what = n == null ? "" : qualified(n) + "  ";
        return "== " + what + s.file() + ":" + from + "-" + to;
    }

    /** {@code Shop.total} for a member, the qualified name for a type when it has one. */
    private String qualified(GraphNode n) {
        if (n.extra().get(Linker.QUALIFIED_NAME) instanceof String q) return q.replace('$', '.');
        for (GraphEdge e : graph.incident(n.id())) {
            if (e.target().equals(n.id())
                    && (e.relation().equals(Relations.METHOD) || e.relation().equals(Relations.CONTAINS))) {
                GraphNode owner = graph.node(e.source());
                if (owner != null && Decl.isType(owner)) return owner.label() + "." + plain(n.label());
            }
        }
        return plain(n.label());
    }

    private List<String> lines(String file, Map<String, List<String>> text) {
        return text.computeIfAbsent(file, f -> {
            String t = files.apply(f);
            return t == null ? List.of() : t.lines().toList();
        });
    }

    private static String number(int line, int width) {
        String n = String.valueOf(line);
        return " ".repeat(Math.max(0, width - n.length())) + n + "| ";
    }
}
