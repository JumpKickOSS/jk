// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static cc.jumpkick.graph.JavaText.before;
import static cc.jumpkick.graph.JavaText.line;
import static cc.jumpkick.graph.JavaText.lineEnd;
import static cc.jumpkick.graph.JavaText.lineNumber;
import static cc.jumpkick.graph.JavaText.lineStart;
import static cc.jumpkick.graph.MoveMember.canonical;
import static cc.jumpkick.graph.MoveMember.pkg;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphQuery;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.graph.MoveMember.FileEdit;
import cc.jumpkick.graph.MoveMember.Mark;
import cc.jumpkick.graph.MoveMember.Member;
import cc.jumpkick.graph.MoveMember.Op;
import cc.jumpkick.graph.MoveMember.Plan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;
import org.jspecify.annotations.Nullable;

/**
 * The {@code move} of an instance method to a type it can reach: through a field of the source type
 * that holds the target, or a parameter of the target type, the routes. A call through a field
 * {@code customer} becomes {@code order.customer.summary(order)}; through a parameter {@code c}, {@code
 * c.summary(order)}. The moved code reads that field or parameter as {@code this}, and what else it
 * used of the source type through a parameter of that type, added only when it needs one. Routes are
 * {@code f1}, {@code f2}, ... for fields and {@code p1}, ... for parameters. When exactly one route
 * reaches the target from every call, it moves at once and says why each other route was not taken;
 * otherwise it writes nothing and lists them for {@code via}. A method that overrides, implements or
 * is overridden is refused: moving it changes which method a call runs.
 */
final class InstanceMove {

    private InstanceMove() {}

    private static final Pattern THIS = Pattern.compile("\\bthis\\b");

    private static final Pattern SIMPLE = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");

    /** A class the moved code declares: its {@code this} and members would mean something else there. */
    private static final Pattern NESTED = Pattern.compile(
            "\\bnew\\s+[\\w$.]+\\s*(?:<[^;{}]*?>)?\\s*\\([^;{}]*\\)\\s*\\{|\\b(?:class|interface|enum|record)\\s+[A-Za-z_$]");

    /** The header of a named class body: a nested one's {@code this} is not its enclosing instance. */
    private static final Pattern CLASS = Pattern.compile("(?<![\\w$.])(?:class|interface|enum|record)\\s+[A-Za-z_$]");

    private static final Set<String> OBJECT = Set.of("equals", "hashCode", "toString", "clone", "finalize");

    /** Calls quoted under each route before the rest are counted. */
    private static final int SHOWN = 8;

    /**
     * One way to an instance of the target: a field of the source type, read through {@code getter}
     * where a call cannot see it, or the parameter at {@code param}.
     */
    private record Route(
            String id, @Nullable String field, @Nullable String getter, int param, String name) {}

    /**
     * One call of the method: its receiver ({@code recvStart} -1 when unqualified, {@code sep} the dot
     * before the name), its parentheses and each argument's {@code [start, end)}.
     */
    private record Call(
            FileEdit f, ReferenceIndex.Span s, int recvStart, int sep, int open, int close, List<int[]> args) {

        boolean unqualified() {
            return recvStart < 0;
        }

        String recv() {
            return unqualified() ? "this" : f.text.substring(recvStart, sep).strip();
        }

        String arg(int i) {
            return f.text.substring(args.get(i)[0], args.get(i)[1]);
        }
    }

    /** The moved code's edits for one route, whether it needs the source instance, and what it reads of it. */
    private static final class Landing {
        final List<Op> ops = new ArrayList<>();
        final Set<String> reads = new TreeSet<>();
        boolean source;
    }

    /** What the method's own declaration gives the move: its parentheses, body end, parameters and descriptor. */
    private record Head(int open, int close, int end, List<int[]> params, List<String> desc, String ret) {}

    static String move(Plan p, @Nullable String via, int budget) throws IOException {
        if (p.members.size() > 1) {
            return "Not moved, nothing is written: " + p.what + " is an instance method with overloads; an"
                    + " instance move takes one, so rename the others first.";
        }
        Member m = p.members.getFirst();
        String text = p.from.text;
        String code = code(text);
        int open = code.indexOf('(', m.name());
        int close = closing(code, open);
        int end = JavaText.methodEnd(text, m.name());
        if (close < 0 || end >= text.length() || text.charAt(end) != '}') {
            return "Not moved, nothing is written: " + p.what + " has no body to move.";
        }
        String refused = overrides(p, m);
        if (refused != null) {
            return "Not moved, nothing is written: " + p.what + " " + refused
                    + "; moving it would change which method a call runs.";
        }
        String sig = m.symbol().substring(m.symbol().indexOf('('));
        Head head = new Head(
                open,
                close,
                end,
                split(text, code, open, close, true),
                descriptors(sig.substring(1, sig.indexOf(')'))),
                sig.substring(sig.indexOf(')') + 1));
        if (head.params().size() != head.desc().size()) {
            return "Not moved, nothing is written: the parameters of " + p.what + " cannot be read; move it by hand.";
        }
        List<Route> routes = routes(p, head);
        if (routes.isEmpty()) {
            return "Not moved, nothing is written: " + p.what + " is an instance method, and neither a field of "
                    + simple(p.fromBin) + " nor a parameter of it holds a " + canonical(p.toBin) + " to call it on.";
        }
        hazards(p, m, head);
        List<Call> calls = new ArrayList<>();
        List<FileEdit> links = new ArrayList<>();
        List<ReferenceIndex.Span> linkSpans = new ArrayList<>();
        callers(p, m, calls, links, linkSpans);
        p.h.visibility(p.at, m, p.what, p.toBin, p.toType, p.outside, p.files);
        p.h.collisions(p.to, p.toBin, p.name, false);
        p.h.unindexed(p.w, p.g, p.fromType, p.name);
        p.strings.addAll(MoveHazards.strings(p.w, p.from, p.members, p.name));
        String src = sourceName(code.substring(m.name(), end + 1), p.fromBin);
        if (src == null) p.h.add("name", "  the code already uses the names a parameter of the source type could take");
        String name = src == null ? "source" : src;

        // A dry landing adds imports to the target as it names types; only the chosen one's stay.
        Set<String> imports = Set.copyOf(p.to.imports);
        Set<String> statics = Set.copyOf(p.to.staticImports);
        List<Landing> dry = new ArrayList<>();
        for (Route r : routes) dry.add(land(p, m, head, r, name, new MoveHazards()));
        p.to.imports.retainAll(imports);
        p.to.staticImports.retainAll(statics);
        List<Route> usable = routes.stream()
                .filter(r ->
                        calls.stream().allMatch(c -> blocked(p, head, c, r, dry.get(routes.indexOf(r)).source) == null))
                .toList();
        Route chosen = via == null
                ? usable.size() == 1 ? usable.getFirst() : null
                : routes.stream()
                        .filter(r -> r.id().equals(via.strip()))
                        .findFirst()
                        .orElse(null);
        boolean blocked = chosen != null && !usable.contains(chosen);
        if (chosen == null || blocked || !p.h.isEmpty()) {
            String header = via != null && chosen == null
                    ? "Not moved, nothing is written: " + via.strip() + " is not a route of " + p.what
                            + "; move it again with via= one of these:"
                    : chosen == null && usable.isEmpty()
                            ? "Not moved, nothing is written: " + p.what
                                    + " is an instance method, and no route reaches " + canonical(p.toBin)
                                    + " from every call of it:"
                            : chosen == null
                                    ? "Not moved, nothing is written: " + p.what + " is an instance method; "
                                            + usable.size() + " routes reach " + canonical(p.toBin)
                                            + ". Move it again with via=<route>:"
                                    : blocked
                                            ? "Not moved, nothing is written: " + chosen.id() + " does not reach "
                                                    + canonical(p.toBin) + " from every call of " + p.what + ":"
                                            : "Not moved, nothing is written: moving " + p.what + " to "
                                                    + canonical(p.toBin) + " through " + chosen.id()
                                                    + " would break or change:";
            return Budget.lines(listing(p, head, header, routes, dry, calls, name), budget, "Raise the budget");
        }
        Landing l = land(p, m, head, chosen, name, p.h);
        if (!p.h.isEmpty()) {
            List<String> out = new ArrayList<>();
            out.add("Not moved, nothing is written: moving " + p.what + " to " + canonical(p.toBin) + " through "
                    + chosen.id() + " would break or change:");
            out.addAll(p.h.lines());
            return Budget.lines(out, budget, "Raise the budget");
        }
        String moved = "M " + p.toBin + "#" + p.name + "(" + (l.source ? "L" + p.fromBin.replace('.', '/') + ";" : "")
                + String.join("", without(head.desc(), chosen.param())) + ")" + head.ret();
        p.symbols.clear();
        p.symbols.put(m.symbol(), moved);
        int start = lineStart(text, m.from());
        int stop = lineEnd(text, m.to());
        String block = MoveMember.apply(text.substring(start, stop), MoveMember.shifted(l.ops, -start), null);
        block = block.endsWith("\n") ? block.substring(0, block.length() - 1) : block;
        p.blocks.add(block);
        int at = MoveMember.mapped(l.ops, m.name()) - start;
        p.blockMarks.add(new Mark(lineNumber(block, at) - 1, moved, 'd'));
        for (Call c : calls) apply(p, head, c, chosen, l.source, moved);
        for (int i = 0; i < links.size(); i++) link(p, head, links.get(i), linkSpans.get(i), chosen, l.source, moved);
        List<String> answer = p.write();
        String example = calls.isEmpty() ? null : preview(p, head, calls.getFirst(), chosen, l.source);
        answer.add(
                1,
                "  through " + chosen.id() + ", " + label(p, chosen)
                        + (example == null ? "" : "; a call reads " + example) + ", and fails where "
                        + (chosen.field() != null ? "that field" : "that argument") + " is null");
        if (via == null) answer.addAll(2, skipped(p, head, routes, dry, calls));
        return Budget.lines(answer, budget, "Raise the budget");
    }

    /** Why the method may not move as an instance move (an override either way, a method of Object), or {@code null}. */
    private static @Nullable String overrides(Plan p, Member m) {
        String head = p.from.text.substring(lineStart(p.from.text, m.cutFrom()), m.name());
        if (head.contains("@Override")) return "overrides or implements a supertype's method (@Override)";
        if (OBJECT.contains(p.name)) return "is a method of Object";
        GraphTools tools = GraphService.tools(p.w, p.g, Scope.ALL);
        for (String id : tools.members(canonical(p.fromBin) + "." + p.name)) {
            for (GraphEdge e : p.g.incident(id)) {
                if (!e.relation().equals(Relations.OVERRIDES)) continue;
                boolean mine = e.source().equals(id);
                GraphNode o = p.g.node(mine ? e.target() : e.source());
                return (mine ? "overrides or implements " : "is overridden by ")
                        + GraphQuery.clean(p.g.display(mine ? e.target() : e.source()))
                        + (o == null ? "" : " (" + o.sourceFile() + ":" + Decl.line(o) + ")");
            }
        }
        return null;
    }

    /** The source type's instance fields of exactly the target type, then the method's parameters of it. */
    private static List<Route> routes(Plan p, Head head) {
        List<Route> out = new ArrayList<>();
        for (ReferenceIndex.Span s : p.from.spans) {
            if (s.kind() != 'd' || !s.symbol().startsWith("F " + p.fromBin + "#")) continue;
            if (JavaText.modifiers(p.from.text, s.start()).contains("static") || !typed(p.from, s, p.toBin)) continue;
            String field = ReferenceIndex.simpleName(s.symbol());
            out.add(new Route("f" + (out.size() + 1), field, getter(p, field), -1, ""));
        }
        String want = "L" + p.toBin.replace('.', '/') + ";";
        int fields = out.size();
        for (int i = 0; i < head.desc().size(); i++) {
            if (!head.desc().get(i).equals(want)) continue;
            int[] a = head.params().get(i);
            Matcher last = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$").matcher(p.from.text.substring(a[0], a[1]));
            if (last.find()) out.add(new Route("p" + (out.size() - fields + 1), null, null, i, last.group(1)));
        }
        return out;
    }

    /**
     * The source type's getter of {@code field}, {@code getField()} or {@code field()}, when all it does
     * is return the field and nothing overrides it; else {@code null}.
     */
    private static @Nullable String getter(Plan p, String field) {
        String type = "()L" + p.toBin.replace('.', '/') + ";";
        String text = p.from.text;
        for (String g : List.of("get" + Character.toUpperCase(field.charAt(0)) + field.substring(1), field)) {
            for (ReferenceIndex.Span s : p.from.spans) {
                if (s.kind() != 'd' || !s.symbol().equals("M " + p.fromBin + "#" + g + type)) continue;
                int open = text.indexOf('{', s.end());
                int end = JavaText.methodEnd(text, s.start());
                if (open < 0 || open > end) continue;
                String body = text.substring(open + 1, end);
                if (!body.matches("\\s*return\\s+(?:this\\s*\\.\\s*)?" + Pattern.quote(field) + "\\s*;\\s*")) continue;
                GraphTools tools = GraphService.tools(p.w, p.g, Scope.ALL);
                boolean overridden = false;
                for (String id : tools.members(canonical(p.fromBin) + "." + g)) {
                    for (GraphEdge e : p.g.incident(id)) {
                        overridden |= e.relation().equals(Relations.OVERRIDES)
                                && e.target().equals(id);
                    }
                }
                if (!overridden) return g;
            }
        }
        return null;
    }

    /** How a call reaches a route's field: {@code .field}, {@code .getField()} where it cannot see the field, or {@code null}. */
    private static @Nullable String reach(Plan p, Call c, Route r) {
        if (visible(p, c, declared(p, "F " + p.fromBin + "#" + r.field()))) return "." + r.field();
        if (r.getter() != null && visible(p, c, declared(p, "M " + p.fromBin + "#" + r.getter() + "()"))) {
            return "." + r.getter() + "()";
        }
        return null;
    }

    /** Whether a member of the source type with {@code mods} is visible where {@code c} is. */
    private static boolean visible(Plan p, Call c, Set<String> mods) {
        return mods.contains("public")
                || mods.contains("private") && c.f() == p.from
                || !mods.contains("private") && c.f().pkg.equals(pkg(p.fromBin))
                || mods.contains("protected") && c.unqualified();
    }

    /** Whether the field declared at {@code decl} has the type {@code toBin} as written, not an array or generic of it. */
    private static boolean typed(FileEdit f, ReferenceIndex.Span decl, String toBin) {
        for (ReferenceIndex.Span t : f.spans) {
            if (t.kind() == 'd' || !t.symbol().equals("T " + toBin) || t.end() > decl.start()) continue;
            if (f.text.substring(t.end(), decl.start()).isBlank()) return true;
        }
        return false;
    }

    /** What would change in the moved code wherever it lands: an entry point, a lock, super, a nested class, recursion. */
    private static void hazards(Plan p, Member m, Head head) {
        String text = p.from.text;
        String where = p.from.rel + ":";
        p.h.framework(text.substring(lineStart(text, m.cutFrom()), m.name()), p.name);
        if (p.name.equals("main")) p.h.add("entry point", "  an entry point: the JVM runs main by name");
        if (m.modifiers().contains("synchronized")) {
            p.h.add("lock", "  synchronized: it would lock the target instead of " + simple(p.fromBin));
        }
        String body = code(text).substring(head.close(), head.end() + 1);
        Matcher sup = Pattern.compile("\\bsuper\\b").matcher(body);
        if (sup.find()) {
            p.h.add("super", "  calls super  " + where + lineNumber(text, head.close() + sup.start()));
        }
        Matcher nested = NESTED.matcher(body);
        if (nested.find()) {
            p.h.add(
                    "nested",
                    "  declares a class, where this and members mean another instance  " + where
                            + lineNumber(text, head.close() + nested.start()));
        }
        for (ReferenceIndex.Span s : p.from.spans) {
            if (s.kind() != 'd' && s.symbol().equals(m.symbol()) && MoveMember.inside(p.members, s.line())) {
                p.h.add("recursion", "  calls itself  " + where + s.line());
            }
        }
    }

    /** Every use of the method outside it: calls to rewrite, doc links, and those a move cannot rewrite as hazards. */
    private static void callers(
            Plan p, Member m, List<Call> calls, List<FileEdit> links, List<ReferenceIndex.Span> linkSpans) {
        for (String rel : RenameProof.javaFiles(p.g)) {
            String t = RenameText.read(p.w.root(), rel);
            if (t == null || !t.contains(p.name)) continue;
            FileEdit f = MoveMember.edit(p.w, p.g, p.files, rel);
            if (f == null) {
                for (int i : JavaText.wordLines(t, p.name)) {
                    p.h.add(
                            "not indexed",
                            "  " + rel + ":" + i + " names it, and javac's index does not describe the file as it"
                                    + " stands  " + RenamePlan.clip(t.split("\n", -1)[i - 1]));
                }
                continue;
            }
            String code = null;
            for (ReferenceIndex.Span s : f.spans) {
                if (!s.symbol().equals(m.symbol()) || s.kind() == 'd') continue;
                if (f == p.from && MoveMember.inside(p.members, s.line())) continue;
                p.outside.merge(rel, 1, Integer::sum);
                String where = rel + ":" + s.line() + "  " + RenamePlan.clip(line(f.text, s.start()));
                if (s.kind() == 'j') {
                    int[] q = MoveMember.qualifier(f, s);
                    if (q != null && q[2] == 0) p.h.add("link", "  a doc link this move cannot rewrite  " + where);
                    links.add(f);
                    linkSpans.add(s);
                    continue;
                }
                if (code == null) code = code(f.text);
                int k = before(code, s.start());
                char c = k < 0 ? ' ' : code.charAt(k);
                int o = after(code, s.end());
                if (c == ':' || c == '>' && typeArguments(code, k) || o >= code.length() || code.charAt(o) != '(') {
                    p.h.add("reference", "  a method reference or type arguments this move cannot rewrite  " + where);
                    continue;
                }
                int cl = closing(code, o);
                int recv = c == '.' ? receiverStart(code, k) : -1;
                if (cl < 0 || c == '.' && recv < 0) {
                    p.h.add("receiver", "  a call this move cannot read  " + where);
                    continue;
                }
                if (c != '.' && nested(f, code, s.start(), p.fromBin)) {
                    p.h.add(
                            "nested",
                            "  a call from a nested or anonymous class, where this is another instance  " + where);
                    continue;
                }
                calls.add(new Call(f, s, recv, c == '.' ? k : -1, o, cl, split(f.text, code, o, cl, false)));
            }
        }
    }

    /**
     * The moved code's edits for route {@code r}: the route's field or parameter becomes {@code this},
     * the source type's instance members and {@code this} go through the parameter {@code src}, the
     * route's parameter leaves the signature, and {@code src} joins it when the code needs it.
     */
    private static Landing land(Plan p, Member m, Head head, Route r, String src, MoveHazards h) {
        Landing l = new Landing();
        String text = p.from.text;
        String code = code(text);
        int start = lineStart(text, m.from());
        int stop = lineEnd(text, m.to());
        String routeField = r.field() == null ? null : "F " + p.fromBin + "#" + r.field();
        MoveMember.Body b =
                new MoveMember.Body(p.at, h, p.from, p.to, p.fromBin, p.toBin, p.symbols, Set.of(), l.ops, false);
        Set<Integer> done = new HashSet<>();
        for (ReferenceIndex.Span s : p.from.spans) {
            if (s.start() < start || s.start() >= stop || s.symbol().equals(m.symbol())) continue;
            String sym = s.symbol();
            if (s.kind() == 'd' || s.kind() == 'j' || sym.startsWith("T ") || sym.contains("#<")) {
                b.name(s);
                continue;
            }
            String where = p.from.rel + ":" + s.line();
            String owner = sym.substring(2, sym.indexOf('#'));
            Set<String> mods = p.at.modifiers(sym);
            int k = before(text, s.start());
            boolean qualified = k >= 0 && ".:#>".indexOf(text.charAt(k)) >= 0;
            boolean imported = MoveImports.staticImport(text, canonical(owner), ReferenceIndex.simpleName(sym)) != null;
            boolean instance = mods == null ? !imported : !mods.contains("static");
            int self = qualified && text.charAt(k) == '.' ? self(code, k) : -1;
            if (sym.equals(routeField)) {
                if (s.kind() == 'w') h.add("state", "  assigns " + RenameProof.show(sym) + ", the route  " + where);
                else if (self >= 0) l.ops.add(new Op(self, s.end(), "this"));
                else if (!qualified) l.ops.add(new Op(s.start(), s.end(), "this"));
                if (self >= 0) done.add(self);
                continue;
            }
            boolean mine = owner.equals(p.fromBin);
            if (instance && !qualified && p.fromBin.startsWith(owner + "$")) {
                h.add("enclosing", "  uses " + RenameProof.show(sym) + " of the enclosing instance  " + where);
                continue;
            }
            if (instance && (mine || !owner.equals(p.toBin)) && (self >= 0 || !qualified)) {
                if (mine) h.access(p.at, sym, owner, p.toBin, where);
                l.reads.add(RenameProof.show(sym));
                l.source = true;
                if (self >= 0) {
                    l.ops.add(new Op(self, self + 4, src));
                    done.add(self);
                } else {
                    l.ops.add(new Op(s.start(), s.start(), src + "."));
                }
                continue;
            }
            if (instance && mine) {
                h.access(p.at, sym, owner, p.toBin, where);
                continue;
            }
            b.name(s);
        }
        Matcher self = THIS.matcher(code.substring(0, head.end() + 1));
        self.region(head.close(), head.end() + 1);
        while (self.find()) {
            if (done.contains(self.start())) continue;
            int k = before(code, self.start());
            if (k >= 0 && code.charAt(k) == '.') {
                h.add(
                        "enclosing",
                        "  uses an enclosing instance  " + p.from.rel + ":" + lineNumber(text, self.start()));
                continue;
            }
            l.ops.add(new Op(self.start(), self.end(), src));
            l.reads.add("this");
            l.source = true;
        }
        if (r.param() >= 0) parameter(p, m, head, r, code, l, h);
        int left = head.params().size() - (r.param() >= 0 ? 1 : 0);
        if (l.source) {
            l.ops.add(new Op(
                    head.open() + 1, head.open() + 1, p.at.ref(p.to, p.fromBin) + " " + src + (left == 0 ? "" : ", ")));
        }
        return l;
    }

    /** The route's parameter leaves the signature and its doc; each use of it in the code becomes {@code this}. */
    private static void parameter(Plan p, Member m, Head head, Route r, String code, Landing l, MoveHazards h) {
        String text = p.from.text;
        Matcher use =
                Pattern.compile("\\b" + Pattern.quote(r.name()) + "\\b").matcher(code.substring(0, head.end() + 1));
        use.region(head.close(), head.end() + 1);
        while (use.find()) {
            int k = before(code, use.start());
            if (k >= 0 && code.charAt(k) == '.') continue;
            int n = after(code, use.end());
            if (n < code.length() && code.charAt(n) == '(') continue;
            if (n + 1 < code.length() && code.charAt(n) == '=' && code.charAt(n + 1) != '=') {
                h.add(
                        "state",
                        "  assigns " + r.name() + ", the route  " + p.from.rel + ":" + lineNumber(text, use.start()));
                continue;
            }
            l.ops.add(new Op(use.start(), use.end(), "this"));
        }
        l.ops.add(drop(head.params(), r.param()));
        int start = lineStart(text, m.from());
        Matcher doc = Pattern.compile("(?m)^[ \\t]*\\*[ \\t]*@param[ \\t]+" + Pattern.quote(r.name())
                        + "\\b[^\\n]*\\n(?:[ \\t]*\\*(?![ \\t]*@|/)[^\\n]*\\n)*")
                .matcher(text.substring(0, m.name()));
        doc.region(start, m.name());
        if (doc.find()) l.ops.add(new Op(doc.start(), doc.end(), ""));
    }

    /** Why {@code c} cannot take route {@code r}, or {@code null}; {@code source}: the moved code needs the source instance. */
    private static @Nullable String blocked(Plan p, Head head, Call c, Route r, boolean source) {
        String recv = c.recv();
        if (recv.equals("super")) return "a super call";
        if (r.field() != null) {
            if (reach(p, c, r) == null) return simple(p.fromBin) + "." + r.field() + " is not visible here";
            if (source && !plain(recv)) return "its receiver would run twice";
            return null;
        }
        if (c.args().size() != head.params().size()) return "its arguments cannot be read";
        String ak = c.arg(r.param()).strip();
        if (ak.equals("null")) return "passes null for " + r.name();
        if (!source && !c.unqualified() && !plain(recv)) return "its receiver would no longer run";
        boolean before = !plain(recv);
        for (int i = 0; i < r.param(); i++) before |= !plain(c.arg(i).strip());
        if (!plain(ak) && before) return "its arguments would run in another order";
        return null;
    }

    /** The modifiers of the source type's member whose symbol starts {@code symbol}; none when it has none. */
    private static Set<String> declared(Plan p, String symbol) {
        for (ReferenceIndex.Span s : p.from.spans) {
            if (s.kind() == 'd' && s.symbol().startsWith(symbol)) {
                return JavaText.modifiers(p.from.text, s.start());
            }
        }
        return Set.of();
    }

    /** What {@code c} reads after the move through {@code r}. */
    private static String preview(Plan p, Head head, Call c, Route r, boolean source) {
        String recv = c.recv();
        List<String> args = new ArrayList<>();
        if (source) args.add(recv);
        for (int i = 0; i < c.args().size(); i++) {
            if (i != r.param()) args.add(c.arg(i).strip());
        }
        String lead = r.field() != null
                ? recv + Objects.requireNonNullElse(reach(p, c, r), "." + r.field())
                : wrap(c.arg(r.param()).strip());
        return RenamePlan.clip(lead + "." + p.name + "(" + String.join(", ", args) + ")");
    }

    /** The edits of one call for route {@code r}: the receiver, the dropped argument, the source instance passed. */
    private static void apply(Plan p, Head head, Call c, Route r, boolean source, String moved) {
        FileEdit f = c.f();
        int name = c.s().start();
        if (r.field() != null) {
            String via = Objects.requireNonNullElse(reach(p, c, r), "." + r.field());
            f.ops.add(c.unqualified() ? new Op(name, name, "this" + via + ".") : new Op(c.sep(), c.sep(), via));
        } else {
            int[] a = c.args().get(r.param());
            String lead = wrap(c.arg(r.param()).strip()) + ".";
            f.ops.add(c.unqualified() ? new Op(name, name, lead) : new Op(c.recvStart(), c.sep() + 1, lead));
            f.ops.add(drop(c.args(), r.param()));
            needs(f, a[0], a[1]);
            if (!c.unqualified()) needs(f, c.recvStart(), c.sep());
        }
        int left = c.args().size() - (r.param() >= 0 ? 1 : 0);
        if (source) f.ops.add(new Op(c.open() + 1, c.open() + 1, c.recv() + (left == 0 ? "" : ", ")));
        f.marks.add(new Mark(name, moved, c.s().kind()));
    }

    /** A doc link to the method names the target, and its parameter list, when it has one, the new parameters. */
    private static void link(
            Plan p, Head head, FileEdit f, ReferenceIndex.Span s, Route r, boolean source, String moved) {
        int hash = before(f.text, s.start());
        int[] q = MoveMember.qualifier(f, s);
        String target = p.at.ref(f, p.toBin);
        f.ops.add(q != null && q[2] == 1 ? new Op(q[0], q[1], target) : new Op(hash, hash, target));
        int o = after(f.text, s.end());
        int cl = o < f.text.length() && f.text.charAt(o) == '(' ? f.text.indexOf(')', o) : -1;
        if (cl > 0) {
            List<String> types = new ArrayList<>();
            for (String t : f.text.substring(o + 1, cl).split(",")) {
                if (!t.isBlank()) types.add(t.strip());
            }
            if (types.size() == head.params().size()) {
                List<String> now = new ArrayList<>();
                if (source) now.add(p.at.ref(f, p.fromBin));
                for (int i = 0; i < types.size(); i++) {
                    if (i != r.param()) now.add(types.get(i));
                }
                f.ops.add(new Op(o + 1, cl, String.join(", ", now)));
            }
        }
        f.marks.add(new Mark(s.start(), moved, s.kind()));
    }

    /** Why each route that some call cannot take was not the one moved through: the first such call and the count. */
    private static List<String> skipped(Plan p, Head head, List<Route> routes, List<Landing> dry, List<Call> calls) {
        List<String> out = new ArrayList<>();
        for (Route r : routes) {
            boolean source = dry.get(routes.indexOf(r)).source;
            List<Call> cannot = calls.stream()
                    .filter(c -> blocked(p, head, c, r, source) != null)
                    .toList();
            if (cannot.isEmpty()) continue;
            Call c = cannot.getFirst();
            out.add("  not through " + r.id() + ", " + label(p, r) + ": " + cannot.size() + " of " + calls.size()
                    + " calls cannot take it; " + c.f().rel + ":" + c.s().line() + "  "
                    + blocked(p, head, c, r, source));
        }
        return out;
    }

    /** The answer that writes nothing: each route with what each call reads through it, or why it cannot. */
    private static List<String> listing(
            Plan p, Head head, String header, List<Route> routes, List<Landing> dry, List<Call> calls, String src) {
        List<String> out = new ArrayList<>();
        out.add(header);
        Set<String> reads = new TreeSet<>();
        for (Route r : routes) {
            Landing l = dry.get(routes.indexOf(r));
            reads.addAll(l.reads);
            List<String> lines = new ArrayList<>();
            int blocked = 0;
            for (Call c : calls) {
                String why = blocked(p, head, c, r, l.source);
                if (why != null) blocked++;
                lines.add("    " + c.f().rel + ":" + c.s().line() + "  "
                        + (why == null ? preview(p, head, c, r, l.source) : "cannot: " + why));
            }
            out.add("  " + r.id() + "  through " + label(p, r)
                    + (blocked == 0 ? "" : " (" + blocked + " of " + calls.size() + " calls cannot)"));
            out.addAll(lines.subList(0, Math.min(lines.size(), SHOWN)));
            if (lines.size() > SHOWN) out.add("    +" + (lines.size() - SHOWN) + " more");
        }
        for (Route r : routes) {
            if (r.field() != null) reads.remove(simple(p.fromBin) + "." + r.field());
        }
        if (!reads.isEmpty()) {
            out.add("It reads " + String.join(", ", reads) + " of " + simple(p.fromBin) + ", passed in as " + src
                    + ".");
        }
        if (!p.h.isEmpty()) {
            if (!header.endsWith("would break or change:")) out.add("What would break or change:");
            out.addAll(p.h.lines());
        }
        return out;
    }

    private static String label(Plan p, Route r) {
        return r.field() != null
                ? "the field " + simple(p.fromBin) + "." + r.field()
                : "the parameter " + simple(p.toBin) + " " + r.name();
    }

    /** A parameter name for the source instance the moved code does not already use: {@code order}, else {@code source}. */
    private static @Nullable String sourceName(String code, String fromBin) {
        Set<String> words = new HashSet<>();
        Matcher w = WORD.matcher(code);
        while (w.find()) words.add(w.group());
        String simple = simple(fromBin);
        String own = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        for (String name : List.of(own, "source")) {
            if (!words.contains(name) && !SourceVersion.isKeyword(name)) return name;
        }
        return null;
    }

    /** Keeps the imports of the types named in {@code [from, to)} of {@code f}: an edit moves that text, it does not drop it. */
    private static void needs(FileEdit f, int from, int to) {
        for (ReferenceIndex.Span s : f.spans) {
            if (s.symbol().startsWith("T ") && s.start() >= from && s.end() <= to)
                f.needs.add(canonical(s.symbol().substring(2)));
        }
    }

    /** The edit that drops item {@code k} of a parameter or argument list with its comma. */
    private static Op drop(List<int[]> list, int k) {
        if (list.size() == 1) return new Op(list.getFirst()[0], list.getFirst()[1], "");
        if (k < list.size() - 1) return new Op(list.get(k)[0], list.get(k + 1)[0], "");
        return new Op(list.get(k - 1)[1], list.get(k)[1], "");
    }

    private static List<String> without(List<String> list, int k) {
        List<String> out = new ArrayList<>(list);
        if (k >= 0) out.remove(k);
        return out;
    }

    private static boolean plain(String expr) {
        return SIMPLE.matcher(expr).matches();
    }

    /** {@code expr} as a receiver: in parentheses unless it is a name or a chain of them. */
    private static String wrap(String expr) {
        return plain(expr) || expr.matches("[\\w$.]+\\([^()]*\\)") ? expr : "(" + expr + ")";
    }

    /** {@code a.b.Order$Line} → {@code Line}. */
    private static String simple(String binary) {
        String c = canonical(binary);
        return c.substring(c.lastIndexOf('.') + 1);
    }

    /** A JVM parameter descriptor list, {@code ILjava/lang/String;[J}, one entry per parameter. */
    static List<String> descriptors(String params) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            int s = i;
            while (params.charAt(i) == '[') i++;
            if (params.charAt(i) == 'L') i = params.indexOf(';', i);
            i++;
            out.add(params.substring(s, i));
        }
        return out;
    }

    /** {@code text} with every comment and literal blanked to spaces, lines kept: code to search for words and brackets. */
    static String code(String text) {
        StringBuilder b = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            int past = JavaText.past(text, i);
            if (past == i) {
                i++;
                continue;
            }
            for (int k = i; k < past; k++) {
                if (b.charAt(k) != '\n') b.setCharAt(k, ' ');
            }
            i = past;
        }
        return b.toString();
    }

    /** The offset of the bracket closing the one at {@code open} in blanked {@code code}, or -1. */
    static int closing(String code, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /** The bracket opening the one at {@code close}, or -1. */
    private static int opening(String code, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')' || c == ']' || c == '}') depth++;
            else if (c == '(' || c == '[' || c == '{') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * The items between the parentheses at {@code open} and {@code close} of blanked {@code code},
     * each {@code [start, end)} trimmed in {@code text}; {@code angles} nests every {@code <>}, for a
     * declaration's parameter types, else only type arguments do.
     */
    static List<int[]> split(String text, String code, int open, int close, boolean angles) {
        List<int[]> out = new ArrayList<>();
        if (text.substring(open + 1, close).isBlank()) return out;
        int depth = 0;
        int angle = 0;
        int from = open + 1;
        for (int i = open + 1; i <= close; i++) {
            char c = i == close ? ',' : code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == '<' && (angles || angle > 0 || opensTypeArguments(code, i))) angle++;
            else if (c == '>' && angle > 0) angle--;
            else if (c == ',' && depth == 0 && angle == 0) {
                int s = from;
                int e = i;
                while (s < e && Character.isWhitespace(text.charAt(s))) s++;
                while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
                out.add(new int[] {s, e});
                from = i + 1;
            }
        }
        return out;
    }

    /** The first non-blank offset at or after {@code offset}. */
    private static int after(String text, int offset) {
        int i = offset;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /** The start of the {@code this} token ending right before the dot at {@code dot}, unless something qualifies it; else -1. */
    private static int self(String code, int dot) {
        int e = before(code, dot);
        if (e < 3 || !code.startsWith("this", e - 3)) return -1;
        int s = e - 3;
        if (s > 0 && Character.isJavaIdentifierPart(code.charAt(s - 1))) return -1;
        int k = before(code, s);
        return k >= 0 && code.charAt(k) == '.' ? -1 : s;
    }

    /**
     * The start of the receiver before the dot at {@code dot}: a chain of names, calls, array reads,
     * parenthesized expressions and type arguments, with a {@code new} before it; -1 when it cannot
     * be read.
     */
    private static int receiverStart(String code, int dot) {
        int i = before(code, dot);
        while (i >= 0) {
            char c = code.charAt(i);
            int unit;
            if (c == ')' || c == ']') {
                int o = opening(code, i);
                if (o < 0) return -1;
                int k = before(code, o);
                if (c == ')' && k > 0 && code.charAt(k) == '>' && code.charAt(k - 1) != '-') {
                    int lt = angleOpen(code, k);
                    return lt < 0 ? -1 : created(code, before(code, lt));
                }
                if (k >= 0 && (Character.isJavaIdentifierPart(code.charAt(k)) || c == ']' && code.charAt(k) == ')')) {
                    i = k;
                    continue;
                }
                unit = o;
            } else if (Character.isJavaIdentifierPart(c)) {
                unit = i;
                while (unit > 0 && Character.isJavaIdentifierPart(code.charAt(unit - 1))) unit--;
            } else {
                return -1;
            }
            int k = before(code, unit);
            if (k >= 0 && code.charAt(k) == '.') {
                i = before(code, k);
                continue;
            }
            if (k >= 0 && code.charAt(k) == '>' && typeArguments(code, k)) {
                i = before(code, before(code, angleOpen(code, k)));
                continue;
            }
            if (k >= 2
                    && code.startsWith("new", k - 2)
                    && (k < 3 || !Character.isJavaIdentifierPart(code.charAt(k - 3)))) {
                return k - 2;
            }
            return unit;
        }
        return -1;
    }

    /** Whether the {@code >} at {@code gt} closes the explicit type arguments of a call, {@code a.<T>b()}. */
    private static boolean typeArguments(String code, int gt) {
        if (gt > 0 && code.charAt(gt - 1) == '-') return false;
        int lt = angleOpen(code, gt);
        int dot = lt < 0 ? -1 : before(code, lt);
        return dot >= 0 && code.charAt(dot) == '.';
    }

    /**
     * Whether the {@code <} at {@code lt} opens type arguments: after a dot, {@code a.<T>b()}, or
     * after the type a {@code new} names, {@code new Box<K, V>()}; else it compares.
     */
    private static boolean opensTypeArguments(String code, int lt) {
        int k = before(code, lt);
        return k >= 0 && (code.charAt(k) == '.' || created(code, k) >= 0);
    }

    /** The {@code <} matching the {@code >} at {@code gt} over what type arguments can hold, or -1. */
    private static int angleOpen(String code, int gt) {
        int depth = 0;
        for (int i = gt; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '>') depth++;
            else if (c == '<') {
                if (--depth == 0) return i;
            } else if (!Character.isJavaIdentifierPart(c) && !Character.isWhitespace(c) && ".,?[]@".indexOf(c) < 0) {
                return -1;
            }
        }
        return -1;
    }

    /** The start of the {@code new} before the type name that ends at {@code end}, or -1 when none is there. */
    private static int created(String code, int end) {
        int i = end;
        while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) {
            while (i > 0 && Character.isJavaIdentifierPart(code.charAt(i - 1))) i--;
            int k = before(code, i);
            if (k >= 0 && code.charAt(k) == '.') {
                i = before(code, k);
                continue;
            }
            boolean made = k >= 2
                    && code.startsWith("new", k - 2)
                    && (k < 3 || !Character.isJavaIdentifierPart(code.charAt(k - 3)));
            return made ? k - 2 : -1;
        }
        return -1;
    }

    /**
     * Whether offset {@code at} of {@code f} sits in a class nested in another that is not the source
     * type: an unqualified call there runs on that class's enclosing instance, not on {@code this}.
     */
    private static boolean nested(FileEdit f, String code, int at, String fromBin) {
        List<Integer> bodies = new ArrayList<>();
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '}') depth++;
            else if (c == '{' && depth > 0) depth--;
            else if (c == '{' && classBody(code, i)) bodies.add(i);
        }
        if (bodies.size() < 2) return false;
        int brace = bodies.getFirst();
        int head = statementStart(code, brace);
        for (ReferenceIndex.Span s : f.spans) {
            if (s.kind() == 'd' && s.symbol().equals("T " + fromBin) && s.start() >= head && s.start() < brace) {
                return false;
            }
        }
        return true;
    }

    /** Whether the brace at {@code brace} opens a class body, a named class's or an anonymous one's. */
    private static boolean classBody(String code, int brace) {
        int k = before(code, brace);
        if (k >= 0 && code.charAt(k) == ')') {
            int o = opening(code, k);
            int n = o < 0 ? -1 : before(code, o);
            if (n > 0 && code.charAt(n) == '>') {
                int lt = angleOpen(code, n);
                n = lt < 0 ? -1 : before(code, lt);
            }
            if (n >= 0 && created(code, n) >= 0) return true;
        }
        return CLASS.matcher(code.substring(statementStart(code, brace), brace)).find();
    }

    /** The offset just past the semicolon or brace before {@code at}. */
    private static int statementStart(String code, int at) {
        int i = at - 1;
        while (i >= 0 && ";{}".indexOf(code.charAt(i)) < 0) i--;
        return i + 1;
    }
}
