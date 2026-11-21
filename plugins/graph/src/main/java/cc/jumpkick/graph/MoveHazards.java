// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static cc.jumpkick.graph.JavaText.column;
import static cc.jumpkick.graph.JavaText.line;
import static cc.jumpkick.graph.JavaText.lineEnd;
import static cc.jumpkick.graph.JavaText.lineNumber;
import static cc.jumpkick.graph.JavaText.lineStart;
import static cc.jumpkick.graph.JavaText.modifiers;
import static cc.jumpkick.graph.JavaText.wordLines;
import static cc.jumpkick.graph.MoveMember.canonical;
import static cc.jumpkick.graph.MoveMember.inside;
import static cc.jumpkick.graph.MoveMember.pkg;
import static cc.jumpkick.graph.MoveMember.qualified;
import static cc.jumpkick.graph.MoveMember.top;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphQuery;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.graph.MoveMember.FileEdit;
import cc.jumpkick.graph.MoveMember.Member;
import cc.jumpkick.graph.MoveMember.Resolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What would break or change, without a compile error, if a member moved: instance state, an
 * override or a hidden static, a member one side would not see, a clash in the target, an
 * annotation a framework reads, a use javac did not index, a string naming it. Each hazard is one
 * line, grouped by kind; any hazard and the move writes nothing.
 */
final class MoveHazards {

    /** Lines of each hazard kind quoted before the rest are counted. */
    private static final int SHOWN = 8;

    private final Map<String, Set<String>> byKind = new LinkedHashMap<>();

    /** A hazard when {@code symbol}, declared in {@code owner}, is not visible from {@code toBin}. */
    void access(Resolver at, String symbol, String owner, String toBin, String where) {
        String vis;
        if (symbol.startsWith("T ")) {
            GraphNode n = at.types().get(owner);
            if (n == null) return;
            vis = String.valueOf(Decl.vis(n));
        } else {
            Set<String> mods = at.modifiers(symbol);
            if (mods == null) return;
            vis = visibility(mods);
        }
        boolean sees =
                switch (vis) {
                    case "private" -> top(owner).equals(top(toBin));
                    case "package", "protected" -> pkg(owner).equals(pkg(toBin));
                    default -> true;
                };
        if (!sees) {
            add(
                    "visibility",
                    "  uses " + RenameProof.show(symbol) + ", which is " + word(vis) + " and " + canonical(toBin)
                            + " would not see  " + where);
        }
    }

    private static String visibility(Set<String> modifiers) {
        for (String v : List.of("public", "protected", "private")) {
            if (modifiers.contains(v)) return v;
        }
        return "package";
    }

    private static String word(String visibility) {
        return visibility.equals("package") ? "package-private" : visibility;
    }

    /** What ties an instance member to its type: the instance state it reads, its overrides, the calls through an instance. */
    void instance(
            GraphService.Workspace w, CodeGraph g, FileEdit from, String fromBin, List<Member> members, boolean field)
            throws IOException {
        Map<String, Set<String>> declared = new HashMap<>();
        for (ReferenceIndex.Span s : from.spans) {
            if (s.kind() == 'd' && s.symbol().contains("#")) declared.put(s.symbol(), modifiers(from.text, s.start()));
        }
        for (Member m : members) {
            int start = lineStart(from.text, m.from());
            int end = lineEnd(from.text, m.to());
            for (ReferenceIndex.Span s : from.spans) {
                if (s.start() < start
                        || s.start() >= end
                        || s.kind() == 'd'
                        || !s.symbol().contains("#")) continue;
                Set<String> mods = declared.get(s.symbol());
                if (!s.symbol().substring(2).startsWith(fromBin + "#") || mods == null || mods.contains("static"))
                    continue;
                if (s.symbol().equals(m.symbol())) continue;
                add("state", "  instance state: " + RenameProof.show(s.symbol()) + "  " + from.rel + ":" + s.line());
            }
            String body = from.text.substring(start, end);
            Matcher self = Pattern.compile("\\bthis\\b").matcher(body);
            while (self.find()) {
                if (RenameText.inString(line(body, self.start()), column(body, self.start()))) continue;
                add("this", "  uses this  " + from.rel + ":" + (m.from() + lineNumber(body, self.start()) - 1));
            }
            String head = from.text.substring(lineStart(from.text, m.cutFrom()), m.name());
            if (head.contains("@Override")) add("overrides", "  overrides a supertype's method (@Override)");
        }
        if (!field) {
            GraphTools tools = GraphService.tools(w, g, Scope.ALL);
            for (String id : tools.members(canonical(fromBin) + "."
                    + ReferenceIndex.simpleName(members.getFirst().symbol()))) {
                for (GraphEdge e : g.incident(id)) {
                    if (!e.relation().equals(Relations.OVERRIDES)) continue;
                    boolean mine = e.source().equals(id);
                    GraphNode o = g.node(mine ? e.target() : e.source());
                    add(
                            "overrides",
                            "  " + (mine ? "overrides " : "overridden by ")
                                    + GraphQuery.clean(g.display(mine ? e.target() : e.source()))
                                    + (o == null ? "" : "  " + o.sourceFile() + ":" + Decl.line(o)));
                }
            }
        }
        Set<String> symbols = new TreeSet<>();
        for (Member m : members) symbols.add(m.symbol());
        int calls = 0;
        List<String> shown = new ArrayList<>();
        for (String rel : RenameProof.javaFiles(g)) {
            String text = RenameText.read(w.root(), rel);
            if (text == null) continue;
            List<ReferenceIndex.Span> spans = ReferenceIndex.of(w.root(), g.modules(), rel, text);
            if (spans == null) continue;
            for (ReferenceIndex.Span s : spans) {
                if (s.kind() == 'd' || s.kind() == 'j' || !symbols.contains(s.symbol())) continue;
                if (rel.equals(from.rel) && inside(members, s.line())) continue;
                calls++;
                shown.add("  " + rel + ":" + s.line() + "  " + RenamePlan.clip(line(text, s.start())));
            }
        }
        if (calls > 0) {
            add(
                    "calls",
                    "  " + calls + (calls == 1 ? " use reaches" : " uses reach") + " it through an instance of "
                            + canonical(fromBin) + ":");
            shown.forEach(l -> add("calls", l));
        }
    }

    /** Each non-inert annotation on the moved member: a framework may find it on its type by reflection. */
    void framework(String head, String name) {
        for (String a : SafeDelete.annotations(List.of(), List.of(head.split("\n")))) {
            if (!SafeDelete.inert(a)) {
                add("framework", "  @" + a + " on " + name + ": a framework may look for it on its type by reflection");
            }
        }
    }

    /** Lines of sources javac did not index (Kotlin, Groovy, Java with no classes) that name the member. */
    void unindexed(GraphService.Workspace w, CodeGraph g, GraphNode fromType, String name) {
        String owner = canonical(qualified(fromType));
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        Set<String> rels = new TreeSet<>();
        for (GraphNode n : g.nodes()) {
            String rel = n.sourceFile();
            if (RenameText.jvmSource(rel)
                    && !rel.endsWith(".java")
                    && !g.modules().resource(rel)) rels.add(rel);
        }
        for (String rel : rels) {
            String text = RenameText.read(w.root(), rel);
            if (text == null || !text.contains(name) || !text.contains(simple)) continue;
            String[] lines = text.split("\n", -1);
            for (int i : wordLines(text, name)) {
                add(
                        "not indexed",
                        "  " + rel + ":" + i + " (inferred) names it; javac did not compile it, so the move"
                                + " cannot edit it  " + RenamePlan.clip(lines[i - 1]));
            }
        }
    }

    /** String literals spelling the member's name in JVM sources outside the moved lines: reflection breaks on a move. */
    void strings(GraphService.Workspace w, CodeGraph g, FileEdit from, List<Member> members, String name)
            throws IOException {
        String quoted = "\"" + name + "\"";
        for (String rel : RenameText.files(w.root())) {
            if (!RenameText.jvmSource(rel)) continue;
            String text = RenameText.read(w.root(), rel);
            if (text == null || !text.contains(quoted)) continue;
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (rel.equals(from.rel) && inside(members, i + 1)) continue;
                int at = lines[i].indexOf(quoted);
                if (at >= 0 && RenameText.inString(lines[i], at + 1)) {
                    add(
                            "string",
                            "  a string names it (reflection?)  " + rel + ":" + (i + 1) + "  "
                                    + RenamePlan.clip(lines[i]));
                }
            }
        }
    }

    /** Callers the target's or the member's visibility would shut out once it moves. */
    void visibility(
            Resolver at,
            Member member,
            String what,
            String toBin,
            GraphNode toType,
            Map<String, Integer> outside,
            Map<String, FileEdit> files) {
        String vis = visibility(member.modifiers());
        String typeVis = Objects.requireNonNullElse(Decl.vis(toType), "public");
        for (String rel : outside.keySet()) {
            FileEdit f = files.get(rel);
            if (f == null) continue;
            GraphNode own = null;
            for (GraphNode n : at.types().values()) {
                if (n.sourceFile().equals(rel)) own = n;
            }
            String top = own == null ? "" : top(qualified(own));
            boolean sees =
                    switch (vis) {
                        case "private" -> top.equals(top(toBin));
                        case "package", "protected" -> f.pkg.equals(pkg(toBin));
                        default -> true;
                    };
            if (!sees) {
                add(
                        "visibility",
                        "  " + what + " is " + word(vis) + "; " + rel + " uses it and would not see it in "
                                + canonical(toBin));
            }
            if (!typeVis.equals("public") && !f.pkg.equals(pkg(toBin)) && !top.equals(top(toBin))) {
                add(
                        "visibility",
                        "  " + canonical(toBin) + " is " + word(typeVis) + "; " + rel + " uses " + what
                                + " and would not see it there");
            }
        }
    }

    /** A member of the same name and kind already in the target: a clash, or an overload that changes which one a call picks. */
    void collisions(FileEdit to, String toBin, String name, boolean field) {
        for (ReferenceIndex.Span s : to.spans) {
            if (s.kind() != 'd') continue;
            boolean same = field
                    ? s.symbol().equals("F " + toBin + "#" + name)
                    : s.symbol().startsWith("M " + toBin + "#" + name + "(");
            if (same) {
                add("clash", "  " + canonical(toBin) + " already declares " + name + "  " + to.rel + ":" + s.line());
            }
        }
    }

    /** A static method hidden by a subtype's, or hiding a supertype's: which one runs depends on the type a call names. */
    void hiding(CodeGraph g, GraphNode fromType, String name, boolean field, Map<String, GraphNode> types) {
        if (field) return;
        String bin = qualified(fromType);
        String canonical = canonical(bin);
        String simple = canonical.substring(canonical.lastIndexOf('.') + 1);
        for (GraphNode t : types.values()) {
            List<String> ext = Decl.superclass(t);
            boolean sub = ext.contains(canonical) || ext.contains(bin) || ext.contains(simple);
            boolean sup = Decl.superclass(fromType).stream()
                    .anyMatch(e -> e.equals(canonical(qualified(t))) || e.equals(qualified(t)));
            if (!sub && !sup) continue;
            for (GraphNode n : g.nodes()) {
                if (!n.sourceFile().equals(t.sourceFile()) || !"method".equals(Decl.kind(n))) continue;
                String label = n.label().startsWith(".") ? n.label().substring(1) : n.label();
                if (!label.equals(name + "()")) continue;
                String id = GraphQuery.clean(g.display(n.id()));
                if (!id.startsWith(GraphQuery.clean(g.display(t.id())) + ".")) continue;
                add("hiding", "  " + (sub ? "hidden by " : "hides ") + id + "  " + n.sourceFile() + ":" + Decl.line(n));
            }
        }
    }

    /** Adds {@code line} under {@code kind}; a line already there is said once. */
    void add(String kind, String line) {
        byKind.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(line);
    }

    boolean isEmpty() {
        return byKind.isEmpty();
    }

    List<String> lines() {
        List<String> out = new ArrayList<>();
        byKind.forEach((kind, lines) -> {
            int n = 0;
            for (String l : lines) {
                if (n++ == SHOWN) {
                    out.add("  +" + (lines.size() - SHOWN) + " more");
                    break;
                }
                out.add(l);
            }
        });
        return out;
    }
}
