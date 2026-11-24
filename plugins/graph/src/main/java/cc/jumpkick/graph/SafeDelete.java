// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphQuery;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.codegraph.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code delete} operation: removes a method (every overload), a field or a type only when
 * nothing uses it, and otherwise writes nothing and lists what does, as {@code usages} lists it. A
 * use is a call or reference the compiled classes or javac's index hold, a line of a source they do
 * not describe that spells the name, an anchor the symbol maps to or defines, an override, an
 * annotation a framework may call it through, an entry point, a doc link, or a string that names
 * it. The edit goes through the rename's write path and undo record; the engine then compiles the
 * modules it touched ({@code phase=verify}), or undoes it whole when they do not compile ({@code
 * phase=undo}).
 */
final class SafeDelete {

    private SafeDelete() {}

    /** Annotations that change nothing at run time: a declaration carrying only these is no framework's. */
    private static final Set<String> INERT = Set.of(
            "Override",
            "Deprecated",
            "SuppressWarnings",
            "SafeVarargs",
            "FunctionalInterface",
            "Serial",
            "Nullable",
            "NonNull",
            "Nonnull",
            "NotNull",
            "NullMarked",
            "NullUnmarked",
            "CheckForNull",
            "VisibleForTesting",
            "CheckReturnValue",
            "CanIgnoreReturnValue");

    private static final Pattern ANNOTATION = Pattern.compile("@([\\w.$]+)");

    private static final Pattern STATIC = Pattern.compile("(?<![\\w$])static(?![\\w$])");

    private static final int QUOTE_WIDTH = 160;

    /** What a delete removes and what keeps it alive. */
    private static final class Target {
        final String subject;
        final String rel;
        final List<int[]> spans = new ArrayList<>();
        boolean wholeFile;

        /** The answer {@code usages} gives, when a use it lists keeps the symbol. */
        final List<String> callers = new ArrayList<>();

        /** Uses {@code usages} does not list, one line each. */
        final List<String> also = new ArrayList<>();

        /** Patterns whose match in a string, outside the deleted lines, names the symbol. */
        final List<Pattern> strings = new ArrayList<>();

        /** Whether only a string literal counts: a member's name is a word in too much prose to count elsewhere. */
        boolean literalOnly;

        /**
         * For a static method, the simple names of its class and the classes inheriting it, as words:
         * reflection reaches a static method only through one of those classes, so a JVM source that
         * names none of them cannot reach it by a string.
         */
        @Nullable
        Pattern reflectedThrough;

        Target(String subject, String rel) {
            this.subject = subject;
            this.rel = rel;
        }
    }

    static GraphService.Reply run(GraphService.Workspace w, CodeGraph graph, GraphService.Request r)
            throws IOException {
        Path root = w.root();
        Path out = w.out();
        Map<String, String> options = r.options();
        String phase = options.get("phase");
        if ("verify".equals(phase)) return GraphService.Reply.of(w, RenameProof.verify(root, out, graph));
        if ("undo".equals(phase)) {
            return GraphService.Reply.of(w, undo(root, out, options.getOrDefault("why", "the code does not compile")));
        }
        if (r.args().isEmpty() || r.args().getFirst().isBlank())
            throw new IllegalArgumentException("needs what to delete");
        // Type#member, as javadoc writes it, is Type.member.
        String address = r.args().getFirst().strip().replace('#', '.');
        try {
            return GraphService.Reply.of(w, delete(w, graph, address, options.get("kind"), r.budget()));
        } catch (RenameRefusal refused) {
            return GraphService.Reply.of(w, String.valueOf(refused.getMessage()));
        }
    }

    private static String delete(
            GraphService.Workspace w, CodeGraph graph, String address, @Nullable String kind, int budget)
            throws IOException {
        String stale = edited(w.indexes(), "Not deleted", "delete");
        if (stale != null) throw new RenameRefusal(stale);
        if (address.contains("(")) {
            throw new RenameRefusal("Not deleted: a delete takes Type.method and removes every overload of it;"
                    + " pass the name without parameters.");
        }
        SymbolRename.Ctx ctx = new SymbolRename.Ctx(w, graph);
        String k = kind == null || kind.isBlank() ? RenameOp.kindOf(ctx, graph, address) : kind;
        GraphTools tools = GraphService.tools(w, graph, Scope.ALL);
        Target t =
                switch (k) {
                    case "method" -> method(w, graph, tools, address);
                    case "field" -> field(w, graph, tools, address);
                    case "type" -> type(w, graph, tools, address);
                    case "package" ->
                        throw new RenameRefusal("Not deleted: " + address + " is a package; delete takes a"
                                + " method, a field or a type.");
                    default -> throw new IllegalArgumentException("kind is method, field or type, not '" + k + "'");
                };
        String text = RenameText.read(w.root(), t.rel);
        if (text == null) throw new RenameRefusal("Not deleted: " + t.rel + " cannot be read.");
        String[] lines = text.split("\n", -1);
        Set<Integer> removed = new TreeSet<>();
        for (int[] span : t.spans) removed.addAll(widened(lines, span[0], span[1]));
        if (!t.wholeFile) shares(graph, t, lines, removed);
        strings(w.root(), t, removed);
        if (!t.callers.isEmpty() || !t.also.isEmpty()) {
            List<String> answer = new ArrayList<>();
            answer.add("Not deleted, nothing is written: " + t.subject + " is still used.");
            answer.addAll(t.callers);
            if (!t.also.isEmpty()) {
                answer.add(t.callers.isEmpty() ? "Used by:" : "Also used by:");
                answer.addAll(t.also);
            }
            return Budget.lines(answer, budget, "Raise the budget");
        }
        RenamePlan plan = new RenamePlan(t.subject, "");
        String where;
        if (t.wholeFile) {
            plan.before.put(t.rel, text);
            plan.removed.add(t.rel);
            where = "its file " + t.rel;
        } else {
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (!removed.contains(i + 1)) kept.add(lines[i]);
            }
            plan.edit(t.rel, text, String.join("\n", kept), Set.of());
            int first = removed.iterator().next();
            int last = ((TreeSet<Integer>) removed).last();
            where = (first == last ? "line " + first : "lines " + first + "-" + last) + " of " + t.rel;
        }
        RenameStore.write(w.root(), w.out(), plan);
        RenameStore.writeProof(w.out(), RenameOp.touched(graph, plan), Map.of(), List.of());
        return "Deleted " + t.subject + ", " + where + ": nothing uses it. Looked at the compiled classes' calls,"
                + " javac's references and doc links, sources javac has not indexed, anchors, overrides,"
                + " framework annotations and strings.";
    }

    /** A method, every overload of it: unused when nothing calls, overrides or names it. */
    private static Target method(GraphService.Workspace w, CodeGraph g, GraphTools tools, String address)
            throws IOException {
        int dot = address.lastIndexOf('.');
        if (dot > 0) {
            String owner = address.substring(0, dot);
            String member = address.substring(dot + 1);
            String simple = owner.substring(owner.lastIndexOf('.') + 1);
            if (member.equals(simple) || member.equals("new") || member.equals("<init>")) {
                throw new RenameRefusal("Not deleted: " + address + " names constructors; delete takes a method,"
                        + " a field or a type.");
            }
        }
        List<String> ids = tools.members(address);
        if (ids.isEmpty()) throw new RenameRefusal("Not deleted: no method, field or type '" + address + "'.");
        if (ids.size() > 1) {
            List<String> shown = new ArrayList<>();
            for (String id : ids.subList(0, Math.min(ids.size(), 8))) {
                GraphNode n = g.node(id);
                if (n != null)
                    shown.add("  " + GraphQuery.clean(g.display(id)) + "  " + n.sourceFile() + ":" + Decl.line(n));
            }
            throw new RenameRefusal("'" + address + "' names " + ids.size() + " methods; qualify its type:\n"
                    + String.join("\n", shown));
        }
        String id = ids.getFirst();
        GraphNode n = g.node(id);
        if (n == null || Decl.line(n) <= 0) throw new RenameRefusal("Not deleted: " + address + " has no source line.");
        String name = bare(n.label());
        int extra = Decl.more(n).size();
        Target t = new Target(
                GraphQuery.clean(g.display(id)) + (extra == 0 ? "" : " (" + (extra + 1) + " overloads)"),
                n.sourceFile());
        t.spans.add(new int[] {Decl.line(n), Math.max(Decl.line(n), Decl.end(n))});
        for (int[] more : Decl.more(n)) t.spans.add(new int[] {more[0], Math.max(more[0], more[1])});
        boolean called = false;
        boolean anchored = false;
        boolean overrides = false;
        Set<String> sites = new TreeSet<>();
        for (GraphEdge e : g.incident(id)) {
            if (e.relation().equals(Relations.CALLS)
                    && e.target().equals(id)
                    && !e.source().equals(id)) {
                called = true;
                for (String site : e.sites()) sites.add(e.sourceFile() + ":" + site.replace("L", ""));
            } else if (e.relation().equals(Relations.DEFINES)
                    && e.source().equals(id)
                    && Anchors.is(g.node(e.target()))) {
                anchored = true;
            } else if (e.relation().equals(Relations.OVERRIDES)) {
                boolean mine = e.source().equals(id);
                String other = mine ? e.target() : e.source();
                GraphNode o = g.node(other);
                overrides |= mine;
                t.also.add("  " + (mine ? "overrides " : "overridden by ") + GraphQuery.clean(g.display(other))
                        + (o == null ? "" : "  " + o.sourceFile() + ":" + Decl.line(o)));
            }
        }
        if (called || anchored)
            t.callers.addAll(tools.callers(id, 1, Integer.MAX_VALUE).lines().toList());
        List<String> preamble = preamble(w.root(), n);
        List<String> annotations = annotations(Decl.annotations(n), preamble);
        boolean kotlinOverride = preamble.stream()
                .anyMatch(l -> Pattern.compile("\\boverride\\b").matcher(l).find());
        if (!overrides && (annotations.contains("Override") || kotlinOverride)) {
            t.also.add("  overrides a method declared outside the workspace ("
                    + (kotlinOverride ? "override" : "@Override") + "): a call to that one runs this one");
        }
        framework(t, lines(w.root(), n.sourceFile()), Decl.line(n), annotations, "it");
        if (name.equals("main")
                && preamble.stream()
                        .anyMatch(l -> l.contains("static") || l.strip().startsWith("fun main"))) {
            t.also.add("  an entry point: the JVM runs main");
        }
        named(w, g, t, name, sites);
        GraphNode owner = owner(g, n);
        Heirs heirs = owner == null ? null : heirs(g, owner);
        docLinks(w, g, t, name, owner == null ? null : simple(owner), heirs);
        reflective(t, name, preamble, heirs);
        return t;
    }

    /**
     * The strings that name method {@code id} as reflection would, one line each ({@code a string
     * names it  file:line  quote}), as a delete finds them: what {@code usages} lists below the calls,
     * since no compiled class records such a use.
     */
    static List<String> namedByString(GraphService.Workspace w, CodeGraph g, String id) throws IOException {
        GraphNode n = g.node(id);
        if (n == null || Decl.line(n) <= 0) return List.of();
        Target t = new Target("", n.sourceFile());
        GraphNode owner = owner(g, n);
        reflective(t, bare(n.label()), preamble(w.root(), n), owner == null ? null : heirs(g, owner));
        String[] text = lines(w.root(), n.sourceFile());
        if (text.length < Math.max(Decl.line(n), Decl.end(n))) return List.of();
        Set<Integer> declared = new TreeSet<>(widened(text, Decl.line(n), Math.max(Decl.line(n), Decl.end(n))));
        for (int[] more : Decl.more(n)) declared.addAll(widened(text, more[0], Math.max(more[0], more[1])));
        strings(w.root(), t, declared);
        return t.also;
    }

    /**
     * A method's name in a string literal names it, as {@code getMethod("name")} does; for a static
     * method only in a source naming its class or a class inheriting it.
     */
    private static void reflective(Target t, String name, List<String> preamble, @Nullable Heirs heirs) {
        t.strings.add(Pattern.compile("\"" + Pattern.quote(name) + "\""));
        t.literalOnly = true;
        if (heirs != null && preamble.stream().anyMatch(l -> STATIC.matcher(l).find())) {
            t.reflectedThrough = Pattern.compile("(?<![\\w$])(?:"
                    + String.join("|", heirs.names.stream().map(Pattern::quote).toList()) + ")(?![\\w$])");
        }
    }

    /** A Java field: unused when no source reads, writes or names it and it maps to no anchor. */
    private static Target field(GraphService.Workspace w, CodeGraph g, GraphTools tools, String address)
            throws IOException {
        int dot = address.lastIndexOf('.');
        String member = address.substring(dot + 1);
        GraphNode owner = oneType(g, address.substring(0, dot));
        String rel = owner.sourceFile();
        String qualified = String.valueOf(owner.extra().get(Linker.QUALIFIED_NAME));
        String text = RenameText.read(w.root(), rel);
        List<ReferenceIndex.Span> spans =
                text == null || !rel.endsWith(".java") ? null : ReferenceIndex.of(w.root(), g.modules(), rel, text);
        if (text == null || spans == null) {
            throw new RenameRefusal("Not deleted: " + address + " is declared in " + rel + ", which javac's index does"
                    + " not describe; a delete finds a field's declaration there, so it takes Java fields only.");
        }
        String symbol = "F " + qualified + "#" + member;
        ReferenceIndex.Span declared = spans.stream()
                .filter(s -> s.kind() == 'd' && s.symbol().equals(symbol))
                .findFirst()
                .orElseThrow(() -> new RenameRefusal("Not deleted: no field '" + address + "'."));
        String[] lines = text.split("\n", -1);
        int from = declared.line();
        int to = statementEnd(lines, from);
        for (ReferenceIndex.Span s : spans) {
            if (s.kind() == 'd'
                    && s.symbol().startsWith("F ")
                    && !s.symbol().equals(symbol)
                    && s.line() >= from
                    && s.line() <= to) {
                throw new RenameRefusal("Not deleted: " + address + " is declared in one statement with "
                        + RenameProof.show(s.symbol()) + " (" + rel + ":" + s.line() + "); split it first.");
            }
        }
        GraphUses.Subject subject = GraphUses.subject(GraphUses.Kind.FIELD, owner, member, false);
        Target t = new Target(GraphQuery.clean(g.display(owner.id())) + "." + member, rel);
        t.spans.add(new int[] {from, to});
        int[] cut = range(widened(lines, from, to));
        GraphUses.Found found = GraphUses.find(w, g, Scope.ALL, subject)
                .without(u -> u.file().equals(rel) && u.line() >= cut[0] && u.line() <= cut[1]);
        boolean anchored = false;
        for (GraphEdge e : g.incident(owner.id())) {
            if (e.source().equals(owner.id())
                    && ("field " + member).equals(e.context())
                    && Anchors.is(g.node(e.target()))) anchored = true;
        }
        if (!found.uses().isEmpty() || anchored) t.callers.addAll(GraphUses.render(g, w.root(), tools, found));
        List<String> written = new ArrayList<>();
        for (int i = Math.max(1, cut[0]); i <= from && i <= lines.length; i++) written.add(lines[i - 1]);
        String head = String.join("\n", written);
        int at = head.lastIndexOf(member);
        framework(t, lines, from, annotations(List.of(), List.of(at < 0 ? head : head.substring(0, at))), "it");
        if (member.equals("serialVersionUID")) t.also.add("  serialization reads serialVersionUID");
        t.strings.add(Pattern.compile("\"" + Pattern.quote(member) + "\""));
        t.literalOnly = true;
        return t;
    }

    /** A type: unused when nothing outside it uses, extends, calls into or names it. */
    private static Target type(GraphService.Workspace w, CodeGraph g, GraphTools tools, String address)
            throws IOException {
        GraphNode n = oneType(g, address);
        String rel = n.sourceFile();
        String qualified = String.valueOf(n.extra().get(Linker.QUALIFIED_NAME));
        int from = Decl.line(n);
        int to = Math.max(from, Decl.end(n));
        if (from <= 0) throw new RenameRefusal("Not deleted: " + address + " has no source line.");
        Target t = new Target(qualified.replace('$', '.'), rel);
        t.spans.add(new int[] {from, to});
        t.wholeFile = alone(g, n);
        String text = RenameText.read(w.root(), rel);
        String[] lines = text == null ? new String[0] : text.split("\n", -1);
        int[] cut = t.wholeFile ? new int[] {1, Integer.MAX_VALUE} : range(widened(lines, from, to));
        GraphUses.Subject subject = GraphUses.subject(GraphUses.Kind.TYPE, n, null, false);
        GraphUses.Found found = GraphUses.find(w, g, Scope.ALL, subject)
                .without(u -> u.file().equals(rel) && u.line() >= cut[0] && u.line() <= cut[1]);
        boolean anchored = false;
        for (GraphEdge e : g.incident(n.id())) {
            if (e.source().equals(n.id()) && Anchors.is(g.node(e.target()))) anchored = true;
        }
        if (!found.uses().isEmpty() || anchored) t.callers.addAll(GraphUses.render(g, w.root(), tools, found));
        framework(t, lines, from, annotations(Decl.annotations(n), preamble(w.root(), n)), "it");
        for (GraphNode m : g.nodes()) {
            if (!m.sourceFile().equals(rel) || m.id().equals(n.id()) || Decl.line(m) < from || Decl.line(m) > to)
                continue;
            framework(
                    t,
                    lines,
                    Decl.line(m),
                    annotations(Decl.annotations(m), preamble(w.root(), m)),
                    GraphQuery.clean(g.display(m.id())));
            if (bare(m.label()).equals("main") && "method".equals(Decl.kind(m))) {
                t.also.add("  an entry point: the JVM runs " + GraphQuery.clean(g.display(m.id())));
            }
            for (GraphEdge e : g.incident(m.id())) {
                if (!e.target().equals(m.id()) || !e.relation().equals(Relations.CALLS)) continue;
                GraphNode caller = g.node(e.source());
                if (caller == null
                        || caller.sourceFile().equals(rel)
                                && Decl.line(caller) >= cut[0]
                                && Decl.line(caller) <= cut[1]) continue;
                t.also.add("  " + GraphQuery.clean(g.display(caller.id())) + " calls "
                        + GraphQuery.clean(g.display(m.id())) + "  " + e.sourceFile() + ":"
                        + String.join(",", e.sites()).replace("L", ""));
            }
        }
        String dotted = qualified.replace('$', '.');
        t.strings.add(Pattern.compile("(?<![\\w$.])" + Pattern.quote(dotted) + "(?![\\w$])"));
        if (!dotted.equals(qualified)) {
            t.strings.add(Pattern.compile("(?<![\\w$.])" + Pattern.quote(qualified) + "(?![\\w$])"));
        }
        return t;
    }

    private static GraphNode oneType(CodeGraph g, String name) {
        List<GraphNode> types = GraphUses.types(g, Scope.ALL, name);
        if (types.isEmpty()) throw new RenameRefusal("Not deleted: no type '" + name + "' in the workspace.");
        if (types.size() > 1) {
            List<String> shown = new ArrayList<>();
            for (GraphNode t : types.subList(0, Math.min(types.size(), 8))) {
                shown.add("  "
                        + String.valueOf(t.extra().get(Linker.QUALIFIED_NAME)).replace('$', '.') + "  "
                        + t.sourceFile());
            }
            throw new RenameRefusal(
                    "'" + name + "' names " + types.size() + " types; qualify it:\n" + String.join("\n", shown));
        }
        return types.getFirst();
    }

    /** Whether {@code type} is all its file declares at the top: then the file goes with it. */
    private static boolean alone(CodeGraph g, GraphNode type) {
        GraphNode file = null;
        for (GraphEdge e : g.incident(type.id())) {
            if (e.target().equals(type.id()) && e.relation().equals(Relations.CONTAINS)) file = g.node(e.source());
        }
        if (file == null || Decl.kind(file) != null) return false;
        for (GraphEdge e : g.incident(file.id())) {
            if (e.source().equals(file.id())
                    && !e.target().equals(type.id())
                    && e.relation().equals(Relations.CONTAINS)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One line per annotation of {@code names} a framework may call the declaration through, at the
     * line of {@code lines} (the target's file) that writes it, at or above the declaration's {@code line}.
     */
    private static void framework(Target t, String[] lines, int line, List<String> names, String what) {
        for (String a : names) {
            if (!inert(a)) {
                t.also.add("  @" + a + " on " + what + ": a framework may call or create it by reflection  " + t.rel
                        + ":" + written(lines, line, a));
            }
        }
    }

    /**
     * The line writing annotation {@code simple} on the declaration at {@code line}: that line or one of
     * the annotation lines above it, else {@code line} itself.
     */
    static int written(String[] lines, int line, String simple) {
        Pattern at = Pattern.compile("@(?:[\\w$]+\\.)*" + Pattern.quote(simple) + "(?![\\w$])");
        for (int i = Math.min(line, lines.length); i >= 1 && i > line - 30; i--) {
            String code = lines[i - 1].strip();
            if (i < line
                    && (code.isEmpty()
                            || code.endsWith(";")
                            || code.endsWith("{")
                            || code.endsWith("}")
                            || code.endsWith("*/")
                            || code.startsWith("//"))) break;
            if (at.matcher(GraphUses.unquoted(lines[i - 1])).find()) return i;
        }
        return line;
    }

    /** Whether {@code line} is a comment line: a member's name in a string there is an example, not a use. */
    private static boolean comment(String line) {
        String code = line.strip();
        return code.startsWith("*") || code.startsWith("/*") || code.startsWith("//");
    }

    /** The lines of {@code rel}, none when it cannot be read. */
    private static String[] lines(Path root, String rel) {
        String text = RenameText.read(root, rel);
        return text == null ? new String[0] : text.split("\n", -1);
    }

    /** Whether the annotation of simple name {@code name} changes nothing at run time. */
    static boolean inert(String name) {
        return INERT.contains(name);
    }

    /** The simple names of {@code declared} annotations, else those written in {@code lines}. */
    static List<String> annotations(List<String> declared, List<String> lines) {
        Set<String> out = new TreeSet<>();
        for (String a : declared) out.add(a.substring(a.lastIndexOf('.') + 1));
        for (String line : lines) {
            String code = line.strip();
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue;
            Matcher m = ANNOTATION.matcher(GraphUses.unquoted(line));
            while (m.find()) out.add(m.group(1).substring(m.group(1).lastIndexOf('.') + 1));
        }
        return List.copyOf(out);
    }

    /** The declaration's own line and the annotation and modifier lines right above it. */
    private static List<String> preamble(Path root, GraphNode n) {
        String text = RenameText.read(root, n.sourceFile());
        int line = Decl.line(n);
        if (text == null || line < 1) return List.of();
        String[] lines = text.split("\n", -1);
        if (line > lines.length) return List.of();
        int from = line;
        while (from > 1 && lines[from - 2].strip().startsWith("@")) from--;
        return List.of(lines).subList(from - 1, line);
    }

    /**
     * The lines of sources the compiled classes do not describe (Kotlin, Groovy, Java with no classes)
     * that call {@code name}, outside comments and the lines the compiled classes already list.
     */
    private static void named(GraphService.Workspace w, CodeGraph g, Target t, String name, Set<String> listed) {
        Pattern call = Pattern.compile(
                "(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(|(?:\\.|::)" + Pattern.quote(name) + "(?![\\w$])");
        for (String rel : sources(g)) {
            boolean java = rel.endsWith(".java");
            if (java && w.uncompiled().stream().noneMatch(u -> u.holds(g.modules(), rel))) continue;
            String text = RenameText.read(w.root(), rel);
            if (text == null || !text.contains(name)) continue;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (rel.equals(t.rel) && inside(t, i + 1)) continue;
                String code = lines[i].strip();
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue;
                if (listed.contains(rel + ":" + (i + 1))) continue;
                if (!call.matcher(java ? GraphUses.unquoted(lines[i]) : lines[i])
                        .find()) continue;
                if (declares(lines[i], name)) continue;
                t.also.add("  " + rel + ":" + (i + 1) + " (inferred)  " + quote(lines[i]));
            }
        }
    }

    /** Whether {@code line} declares a method {@code name} rather than calling it. */
    private static boolean declares(String line, String name) {
        String q = Pattern.quote(name);
        return Pattern.compile("\\b(?:def|fun)\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?" + q + "\\s*\\(")
                        .matcher(line)
                        .find()
                || Pattern.compile("(?:^|[\\s(])(?!return\\b|new\\b|throw\\b|else\\b|yield\\b)[\\w$.<>\\[\\]?,]+\\s+"
                                + q + "\\s*\\([^)]*\\)\\s*(?:throws[^{;]*)?\\{?\\s*$")
                        .matcher(line)
                        .find();
    }

    /**
     * Doc comment links to method {@code name} of {@code owner} in Java sources: {@code Type#name} where
     * the type is the owner, and a bare {@code #name} in a file of the owner or of a type inheriting from
     * it, where javadoc resolves the bare link. A link to another type's method of the same name is not
     * a use. With no owner known, every link to the name counts.
     */
    private static void docLinks(
            GraphService.Workspace w,
            CodeGraph g,
            Target t,
            String name,
            @Nullable String simple,
            @Nullable Heirs heirs) {
        Pattern link = Pattern.compile("([\\w$.]*)#" + Pattern.quote(name) + "(?![\\w$])");
        for (String rel : sources(g)) {
            if (!rel.endsWith(".java")) continue;
            String text = RenameText.read(w.root(), rel);
            if (text == null || !text.contains("#" + name)) continue;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (rel.equals(t.rel) && inside(t, i + 1)) continue;
                String code = lines[i].strip();
                if (!(code.startsWith("*") || code.startsWith("/*") || code.startsWith("//"))) continue;
                Matcher m = link.matcher(lines[i]);
                boolean hit = false;
                while (m.find() && !hit) {
                    String type = m.group(1);
                    hit = simple == null
                            || heirs == null
                            || (type.isEmpty()
                                    ? heirs.files.contains(rel)
                                    : simple.equals(type.substring(type.lastIndexOf('.') + 1)));
                }
                if (hit) t.also.add("  a doc link  " + rel + ":" + (i + 1) + "  " + quote(lines[i]));
            }
        }
    }

    /** The type that declares member {@code n}, by its {@code method} or {@code contains} edge. */
    private static @Nullable GraphNode owner(CodeGraph g, GraphNode n) {
        for (GraphEdge e : g.incident(n.id())) {
            if (e.target().equals(n.id())
                    && (e.relation().equals(Relations.METHOD) || e.relation().equals(Relations.CONTAINS))) {
                GraphNode o = g.node(e.source());
                if (o != null && Decl.isType(o)) return o;
            }
        }
        return null;
    }

    /** The simple name of {@code type}, nested ones by their own name. */
    private static String simple(GraphNode type) {
        String q = String.valueOf(type.extra().getOrDefault(Linker.QUALIFIED_NAME, type.label()))
                .replace('$', '.');
        return q.substring(q.lastIndexOf('.') + 1);
    }

    /** A type and every workspace type that extends or implements it: their simple names and files. */
    private record Heirs(Set<String> names, Set<String> files) {}

    /**
     * {@code owner} and every workspace type that extends or implements it, directly or not, matched by
     * the simple name its declaration writes.
     */
    private static Heirs heirs(CodeGraph g, GraphNode owner) {
        Set<String> names = new TreeSet<>(Set.of(simple(owner)));
        Set<String> files = new TreeSet<>(Set.of(owner.sourceFile()));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (GraphNode n : g.nodes()) {
                if (!Decl.isType(n) || names.contains(simple(n))) continue;
                List<String> supers = new ArrayList<>(Decl.superclass(n));
                supers.addAll(Decl.interfaces(n));
                for (String s : supers) {
                    String raw =
                            s.contains("<") ? s.substring(0, s.indexOf('<')).strip() : s.strip();
                    if (names.contains(raw.substring(raw.lastIndexOf('.') + 1))) {
                        names.add(simple(n));
                        files.add(n.sourceFile());
                        grew = true;
                        break;
                    }
                }
            }
        }
        return new Heirs(names, files);
    }

    /**
     * The strings that name the symbol, as reflection, a services file or a config would: in a JVM
     * source a string literal, elsewhere any line of a file that is not prose; never in the lines deleted.
     */
    private static void strings(Path root, Target t, Set<Integer> removed) throws IOException {
        for (String rel : RenameText.files(root)) {
            if (RenameText.doc(rel) || t.wholeFile && rel.equals(t.rel)) continue;
            boolean source = RenameText.jvmSource(rel);
            if (t.literalOnly && !source && !RenameText.classList(rel) && !rel.endsWith(".xml")) continue;
            String text = RenameText.read(root, rel);
            if (text == null) continue;
            if (source
                    && t.reflectedThrough != null
                    && !rel.equals(t.rel)
                    && !t.reflectedThrough.matcher(text).find()) continue;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (rel.equals(t.rel) && removed.contains(i + 1)) continue;
                if (source && t.literalOnly && comment(lines[i])) continue;
                for (Pattern p : t.strings) {
                    Matcher m = p.matcher(lines[i]);
                    boolean hit = false;
                    while (m.find()) {
                        int at = m.start() + (t.literalOnly ? 1 : 0);
                        if (!source || RenameText.inString(lines[i], at)) hit = true;
                    }
                    if (hit) {
                        t.also.add("  a string names it  " + rel + ":" + (i + 1) + "  " + quote(lines[i]));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Fails when a line the delete removes also holds code of another declaration: a member written on
     * its enclosing type's line, a second member on the same line.
     */
    private static void shares(CodeGraph g, Target t, String[] lines, Set<Integer> removed) {
        int first = removed.iterator().next();
        int last = ((TreeSet<Integer>) removed).last();
        for (GraphNode n : g.nodes()) {
            if (!n.sourceFile().equals(t.rel) || Decl.kind(n) == null) continue;
            int a = Decl.line(n);
            int b = Math.max(a, Decl.end(n));
            if (a <= 0 || inside(t, a) && inside(t, b)) continue;
            boolean encloses = a < first && b > last;
            boolean apart = b < first || a > last;
            if (encloses || apart) continue;
            throw new RenameRefusal("Not deleted: " + t.subject + " shares line " + Math.max(a, first) + " of " + t.rel
                    + " with " + GraphQuery.clean(g.display(n.id())) + "; put it on lines of its own first.");
        }
        for (int[] span : t.spans) {
            String head = lines[span[0] - 1].strip();
            String tail = lines[span[1] - 1].strip();
            if (head.startsWith("}") || tail.contains("}") && !tail.endsWith("}") && !tail.endsWith("};")) {
                throw new RenameRefusal("Not deleted: " + t.subject + " shares its lines with other code in " + t.rel
                        + "; put it on lines of its own first.");
            }
        }
    }

    private static boolean inside(Target t, int line) {
        for (int[] s : t.spans) {
            if (line >= s[0] && line <= s[1]) return true;
        }
        return false;
    }

    /**
     * Lines {@code from} to {@code to} (1-based) with the annotations, doc comment and line comments
     * right above them, and one blank line beside them so the code around closes up.
     */
    static Set<Integer> widened(String[] lines, int from, int to) {
        int a = from;
        while (a > 1) {
            String prev = lines[a - 2].strip();
            if (prev.startsWith("@") || prev.startsWith("//")) {
                a--;
            } else if (prev.endsWith("*/")) {
                int k = a - 2;
                while (k >= 0 && !lines[k].contains("/*")) k--;
                if (k < 0) break;
                a = k + 1;
            } else {
                break;
            }
        }
        int b = Math.min(to, lines.length);
        Set<Integer> out = new TreeSet<>();
        for (int i = a; i <= b; i++) out.add(i);
        boolean blankAbove = a > 1 && lines[a - 2].isBlank();
        boolean blankBelow = b < lines.length && lines[b].isBlank() && b + 1 < lines.length;
        boolean closesBelow = b < lines.length && lines[b].strip().startsWith("}");
        if (blankAbove && (blankBelow || closesBelow)) out.add(a - 1);
        else if (blankBelow && a > 1 && lines[a - 2].strip().endsWith("{")) out.add(b + 1);
        return out;
    }

    private static int[] range(Set<Integer> lines) {
        return new int[] {lines.iterator().next(), ((TreeSet<Integer>) lines).last()};
    }

    /** The last line of the statement starting at {@code from}: its {@code ;} outside brackets and strings. */
    static int statementEnd(String[] lines, int from) {
        int depth = 0;
        for (int i = from - 1; i < lines.length; i++) {
            String code = GraphUses.unquoted(lines[i]);
            int comment = code.indexOf("//");
            if (comment >= 0) code = code.substring(0, comment);
            for (char c : code.toCharArray()) {
                if (c == '(' || c == '{' || c == '[') depth++;
                else if (c == ')' || c == '}' || c == ']') depth--;
                else if (c == ';' && depth <= 0) return i + 1;
            }
        }
        return from;
    }

    /** Every workspace source the graph read, root-relative. */
    private static List<String> sources(CodeGraph g) {
        Set<String> out = new TreeSet<>();
        for (GraphNode n : g.nodes()) {
            String rel = n.sourceFile();
            if (RenameText.jvmSource(rel) && !g.modules().resource(rel)) out.add(rel);
        }
        return List.copyOf(out);
    }

    /**
     * Why the classes cannot answer for every source yet, or {@code null} when they can: {@code not}
     * opens the answer ({@code Not deleted}), {@code verb} names the edit to run after the build.
     */
    static @Nullable String edited(List<GraphBuild.ClassIndex> indexes, String not, String verb) {
        Set<String> edited = new TreeSet<>();
        for (GraphBuild.ClassIndex c : indexes) edited.addAll(c.stale());
        if (edited.isEmpty()) return null;
        List<String> shown = new ArrayList<>(edited).subList(0, Math.min(edited.size(), 5));
        return not + ": " + edited.size() + (edited.size() == 1 ? " source" : " sources")
                + " edited since the last build (" + String.join(", ", shown)
                + (edited.size() > shown.size() ? ", …" : "")
                + "). The compiled classes are what find every use: build first, then " + verb + ".";
    }

    /** Undoes the last delete whose modules did not compile; the answer's line. */
    static String undo(Path root, Path out, String why) throws IOException {
        List<String> restored = RenameStore.undo(root, out);
        if (restored.isEmpty()) return "";
        return "Rolled back, nothing is changed (" + restored.size() + (restored.size() == 1 ? " file" : " files")
                + " restored): after the delete " + RenameProof.relative(root, why) + ".";
    }

    private static String quote(String line) {
        String s = line.strip();
        return s.length() > QUOTE_WIDTH ? s.substring(0, QUOTE_WIDTH) + "…" : s;
    }

    /** {@code .name()} → {@code name}. */
    private static String bare(String label) {
        String s = label.startsWith(".") ? label.substring(1) : label;
        return s.endsWith("()") ? s.substring(0, s.length() - 2) : s;
    }
}
