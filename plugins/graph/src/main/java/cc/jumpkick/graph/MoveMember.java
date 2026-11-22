// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static cc.jumpkick.graph.JavaText.afterQualifier;
import static cc.jumpkick.graph.JavaText.before;
import static cc.jumpkick.graph.JavaText.importLine;
import static cc.jumpkick.graph.JavaText.indent;
import static cc.jumpkick.graph.JavaText.line;
import static cc.jumpkick.graph.JavaText.lineEnd;
import static cc.jumpkick.graph.JavaText.lineNumber;
import static cc.jumpkick.graph.JavaText.lineStart;
import static cc.jumpkick.graph.JavaText.methodEnd;
import static cc.jumpkick.graph.JavaText.modifierLine;
import static cc.jumpkick.graph.JavaText.modifiers;
import static cc.jumpkick.graph.JavaText.statementOf;
import static cc.jumpkick.graph.JavaText.wordLines;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.host.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code move} operation: moves a static Java method (every overload) or static field to
 * another workspace class, enum or record, and edits every use javac's reference index binds to it:
 * a qualifier becomes the new type, an unqualified use is qualified, a static import names the new
 * type, an import the old type no longer needs goes, and the moved code gets the imports and
 * qualifiers it needs where it lands. Anything that would break or change behaviour without a
 * compile error (instance state, an override, a member one side cannot see, a use javac did not
 * index) writes nothing and is listed; a string naming the member is listed and left. An instance
 * method moves through a field or parameter of the target type ({@link InstanceMove}). The {@code
 * pull-up} operation moves an instance member to the class its type extends: its uses bind there as
 * written, and another subclass that declares the method gets {@code @Override}. The edit goes
 * through the rename's write path and undo record; the engine then compiles the modules it touched
 * and checks that the moved symbols have the references the old ones had ({@code phase=verify}), or
 * undoes it whole ({@code phase=undo}).
 */
final class MoveMember {

    private MoveMember() {}

    static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");

    private static final List<String> KINDS = List.of("class", "enum", "record");

    /** A local or anonymous class's binary name: it moves with the code that declares it. */
    private static final Pattern LOCAL = Pattern.compile("\\$\\d");

    /** One edit of a file's text: {@code [start, end)} becomes {@code text}. */
    record Op(int start, int end, String text) {}

    /**
     * A moved symbol's name, recorded under {@code symbol} after the move: at {@code at}, an offset of
     * its file, or a line of the moved code counted from 0; {@code kind} as the index has it.
     */
    record Mark(int at, String symbol, char kind) {}

    /** One file the move edits: its text and index, the edits, the imports to add, the moved names it keeps. */
    static final class FileEdit {
        final String rel;
        final String text;
        final List<ReferenceIndex.Span> spans;
        final String pkg;
        final List<Op> ops = new ArrayList<>();
        final Set<String> imports = new TreeSet<>();
        final Set<String> staticImports = new TreeSet<>();
        final List<Mark> marks = new ArrayList<>();

        /** Types {@link Resolver#ref} named through an import the file already had: that import stays. */
        final Set<String> needs = new TreeSet<>();

        /** Offsets of lines rewritten whose references may be any count after the move (an import line). */
        final List<Integer> free = new ArrayList<>();

        FileEdit(String rel, String text, List<ReferenceIndex.Span> spans) {
            this.rel = rel;
            this.text = text;
            this.spans = spans;
            Matcher m = PACKAGE.matcher(text);
            this.pkg = m.find() ? m.group(1) : "";
        }
    }

    /** One moved declaration: its symbol, name offset, the lines it takes and the lines moved with it. */
    record Member(String symbol, int name, int from, int to, int cutFrom, int cutTo, Set<String> modifiers) {}

    static GraphService.Reply run(GraphService.Workspace w, CodeGraph graph, GraphService.Request r)
            throws IOException {
        Path root = w.root();
        Path out = w.out();
        Map<String, String> options = r.options();
        String phase = options.get("phase");
        if ("verify".equals(phase)) {
            String left = left(out);
            String proven = RenameProof.verify(root, out, graph, true);
            return GraphService.Reply.of(w, proven.startsWith("Proven") ? proven + left : proven);
        }
        if ("undo".equals(phase)) {
            left(out);
            return GraphService.Reply.of(w, undo(root, out, options.getOrDefault("why", "the code does not compile")));
        }
        List<String> a = r.args();
        boolean upOnly = "pull-up".equals(r.op());
        String target = a.size() < 2 || a.get(1).isBlank() ? null : a.get(1).strip();
        if (a.isEmpty() || a.get(0).isBlank() || target == null && !upOnly) {
            throw new IllegalArgumentException("needs the member to move and the type to move it to");
        }
        // Type#member, as javadoc writes it, is Type.member.
        String address = a.get(0).strip().replace('#', '.');
        try {
            return GraphService.Reply.of(w, move(w, graph, address, target, upOnly, options.get("via"), r.budget()));
        } catch (RenameRefusal refused) {
            return GraphService.Reply.of(w, String.valueOf(refused.getMessage()));
        }
    }

    /**
     * Moves {@code address} to {@code target}, or with {@code upOnly} (null: the class its type
     * extends); an instance method goes through the route {@code via} names.
     */
    private static String move(
            GraphService.Workspace w,
            CodeGraph g,
            String address,
            @Nullable String target,
            boolean upOnly,
            @Nullable String via,
            int budget)
            throws IOException {
        String stale = SafeDelete.edited(w.indexes(), "Not moved", "move");
        if (stale != null) throw new RenameRefusal(stale);
        Plan p = new Plan(w, g, address, target);
        if (upOnly && !p.up) {
            boolean statics = p.members.stream().anyMatch(m -> m.modifiers().contains("static"));
            return "Not pulled up, nothing is written: "
                    + (statics
                            ? p.what + " is static; a pull-up takes an instance member, a move a static one."
                            : canonical(p.fromBin) + " does not extend " + canonical(p.toBin)
                                    + "; a pull-up moves a member to the class its type extends.");
        }
        if (!upOnly && p.up) {
            return "Not moved, nothing is written: " + canonical(p.toBin) + " is the class " + canonical(p.fromBin)
                    + " extends; a pull-up moves " + p.what + " there (jk code pull-up, MCP pull_up).";
        }
        if (!p.up && !p.members.stream().allMatch(m -> m.modifiers().contains("static"))) {
            if (!p.field) return InstanceMove.move(p, via, budget);
            p.h.instance(w, g, p.from, p.fromBin, p.members, true);
            List<String> answer = new ArrayList<>();
            answer.add("Not moved, nothing is written: " + p.what + " is an instance field; a move takes a method"
                    + " or a static field, a pull-up an instance member to the class its type extends."
                    + " What ties it to " + canonical(p.fromBin) + ":");
            answer.addAll(p.h.lines());
            return Budget.lines(answer, budget, "Raise the budget");
        }
        p.land();
        p.uses();
        p.check();
        if (!p.h.isEmpty()) {
            List<String> answer = new ArrayList<>();
            answer.add("Not moved, nothing is written: " + (p.up ? "pulling up " : "moving ") + p.what + " to "
                    + canonical(p.toBin) + " would break or change:");
            answer.addAll(p.h.lines());
            return Budget.lines(answer, budget, "Raise the budget");
        }
        return Budget.lines(p.write(), budget, "Raise the budget");
    }

    /** One move as it is worked out: the two types, the members, the edits of each file, the hazards. */
    static final class Plan {
        final GraphService.Workspace w;
        final CodeGraph g;
        final Map<String, FileEdit> files = new TreeMap<>();
        final Map<String, GraphNode> types;
        final GraphNode fromType;
        final GraphNode toType;
        final String fromBin;
        final String toBin;
        final String name;
        final FileEdit from;
        final FileEdit to;
        final List<Member> members;
        final boolean field;
        final String what;

        /** A pull-up: instance members to the class the source type extends, whose uses bind there unedited. */
        final boolean up;

        /** Each moved symbol → the same symbol on the target. */
        final Map<String, String> symbols = new LinkedHashMap<>();

        final MoveHazards h = new MoveHazards();
        final Resolver at;

        /** The moved code, edited for where it lands, one block per declaration. */
        final List<String> blocks = new ArrayList<>();

        /** The moved symbols' names in {@link #blocks}, by line counted from the first block's. */
        final List<Mark> blockMarks = new ArrayList<>();

        /** The uses of the moved symbols left outside the moved code, by file. */
        final Map<String, Integer> outside = new TreeMap<>();

        /** The strings that spell the member's name, as {@code rel:line  text}: listed, left as they are. */
        final List<String> strings = new ArrayList<>();

        /** A null {@code target} is the class the member's type extends. */
        Plan(GraphService.Workspace w, CodeGraph g, String address, @Nullable String target) {
            this.w = w;
            this.g = g;
            if (address.contains("(")) {
                throw new RenameRefusal("Not moved: a move takes Type.method and moves every overload of it;"
                        + " pass the name without parameters.");
            }
            int dot = address.lastIndexOf('.');
            if (dot <= 0 || dot == address.length() - 1) {
                throw new RenameRefusal("Not moved: a move takes Type.member, a method or a field.");
            }
            types = types(g);
            fromType = oneType(g, address.substring(0, dot));
            toType = target == null ? parent(w.root(), g, fromType) : oneType(g, target);
            name = address.substring(dot + 1);
            fromBin = qualified(fromType);
            toBin = qualified(toType);
            String subject = fromBin.substring(fromBin.lastIndexOf('.') + 1).replace('$', '.') + "." + name;
            if (fromBin.equals(toBin)) {
                throw new RenameRefusal("Not moved: " + subject + " is already in " + canonical(toBin) + ".");
            }
            for (GraphNode t : List.of(fromType, toType)) {
                if (!KINDS.contains(String.valueOf(Decl.kind(t)))
                        || !t.sourceFile().endsWith(".java")) {
                    throw new RenameRefusal("Not moved: " + canonical(qualified(t)) + " is a " + Decl.kind(t)
                            + "; a move takes a member of a Java class, enum or record to another.");
                }
            }
            FileEdit a = edit(w, g, files, fromType.sourceFile());
            FileEdit b = edit(w, g, files, toType.sourceFile());
            if (a == null || b == null) {
                throw new RenameRefusal("Not moved: javac's index does not describe "
                        + (a == null ? fromType.sourceFile() : toType.sourceFile()) + " as it stands; build first.");
            }
            from = a;
            to = b;
            members = members(from, fromBin, name, address);
            field = members.getFirst().symbol().startsWith("F ");
            what = field
                    ? subject
                    : subject + "()" + (members.size() == 1 ? "" : " (" + members.size() + " overloads)");
            for (Member m : members)
                symbols.put(m.symbol(), m.symbol().replace(" " + fromBin + "#", " " + toBin + "#"));
            at = new Resolver(w, g, files, types);
            up = members.stream().noneMatch(m -> m.modifiers().contains("static")) && extended(fromType, toBin);
        }

        /** The moved code edited for where it lands: {@link #blocks} and {@link #blockMarks}. */
        void land() {
            Set<Integer> qualifiers = new TreeSet<>();
            for (ReferenceIndex.Span s : from.spans) {
                int[] q = symbols.containsKey(s.symbol()) ? qualifier(from, s) : null;
                if (q != null) qualifiers.add(q[1]);
            }
            int blockLine = 0;
            for (Member m : members) {
                int start = lineStart(from.text, m.from());
                int end = lineEnd(from.text, m.to());
                List<Op> ops = new ArrayList<>();
                Body b = new Body(at, h, from, to, fromBin, toBin, symbols, qualifiers, ops, up);
                for (ReferenceIndex.Span s : from.spans) {
                    if (s.start() < start || s.start() >= end) continue;
                    String moved = symbols.get(s.symbol());
                    if (moved != null) blockMarks.add(new Mark(blockLine + s.line() - m.from(), moved, s.kind()));
                    b.name(s);
                }
                String block = apply(from.text.substring(start, end), shifted(ops, -start), null);
                block = block.endsWith("\n") ? block.substring(0, block.length() - 1) : block;
                blocks.add(block);
                blockLine += block.split("\n", -1).length + 1;
                String head = from.text.substring(lineStart(from.text, m.cutFrom()), m.name());
                h.framework(head, name);
                if (up) h.pulled(from, m, head, block, toBin);
            }
            if (up && !field) h.overrides(w, g, fromBin, name, toBin);
            if (!field && name.equals("main")) h.add("entry point", "  an entry point: the JVM runs main by name");
        }

        /** Every use of the moved symbols outside the moved code, edited to name the target. */
        void uses() {
            for (String rel : RenameProof.javaFiles(g)) {
                String text = RenameText.read(w.root(), rel);
                if (text == null || !text.contains(name)) continue;
                FileEdit f = edit(w, g, files, rel);
                if (f == null) {
                    for (int i : wordLines(text, name)) {
                        h.add(
                                "not indexed",
                                "  " + rel + ":" + i + " names it, and javac's index does not describe"
                                        + " the file as it stands  " + RenamePlan.clip(text.split("\n", -1)[i - 1]));
                    }
                    continue;
                }
                if (!up) MoveImports.statics(f, fromBin, toBin, name);
                for (ReferenceIndex.Span s : f.spans) {
                    String moved = symbols.get(s.symbol());
                    if (moved == null || f == from && inside(members, s.line())) continue;
                    if (importLine(f.text, s.start())) continue;
                    // A pulled-up member is inherited: each use binds to it where it lands, as written.
                    if (up) f.marks.add(new Mark(s.start(), moved, s.kind()));
                    else use(at, h, f, toBin, moved, s);
                    if (s.kind() != 'd') outside.merge(f.rel, 1, Integer::sum);
                }
            }
        }

        /** The hazards no single use shows: sources javac did not index, strings, visibility, clashes, hiding. */
        void check() throws IOException {
            if (up) {
                for (String s : MoveHazards.strings(w, from, members, name))
                    h.add("string", "  a string names it (reflection?)  " + s);
                h.collisions(to, toBin, name, field);
                reach();
                if (!field) overriders();
                return;
            }
            h.unindexed(w, g, fromType, name);
            strings.addAll(MoveHazards.strings(w, from, members, name));
            h.visibility(at, members.getFirst(), what, toBin, toType, outside, files);
            h.collisions(to, toBin, name, field);
            h.hiding(g, fromType, name, field, types);
        }

        /**
         * The files that use a package-private or protected member and would not see it in a target in
         * another package: a protected one stays visible to a file declaring a subclass of the target.
         */
        void reach() {
            Set<String> mods = members.getFirst().modifiers();
            if (mods.contains("public") || mods.contains("private")) return;
            boolean prot = mods.contains("protected");
            Set<String> heirs = new TreeSet<>();
            if (prot) {
                for (String bin : subtypes(types, Set.of(toBin))) {
                    GraphNode t = types.get(bin);
                    if (t != null) heirs.add(t.sourceFile());
                }
            }
            for (String rel : outside.keySet()) {
                FileEdit f = files.get(rel);
                if (f == null || f.pkg.equals(pkg(toBin)) || heirs.contains(rel)) continue;
                h.add(
                        "visibility",
                        "  " + what + " is " + (prot ? "protected" : "package-private") + "; " + rel
                                + " uses it and would not see it in " + canonical(toBin));
            }
        }

        /** The {@code @Override} annotations {@link #overriders} adds. */
        int overrides;

        /**
         * The other subclasses of the target that declare the pulled-up method: one with its parameters
         * now overrides it and gets {@code @Override}; one with other parameters is an overload a call
         * may now resolve away from.
         */
        void overriders() {
            Set<String> params = new TreeSet<>();
            for (Member m : members) params.add(params(m.symbol()));
            Set<String> mine = subtypes(types, Set.of(fromBin));
            for (String bin : subtypes(types, Set.of(toBin))) {
                if (bin.equals(toBin) || mine.contains(bin)) continue;
                GraphNode t = types.get(bin);
                if (t == null || !t.sourceFile().endsWith(".java")) continue;
                FileEdit f = edit(w, g, files, t.sourceFile());
                if (f == null) {
                    h.add(
                            "not indexed",
                            "  javac's index does not describe " + t.sourceFile() + ", which extends "
                                    + canonical(toBin) + ", as it stands");
                    continue;
                }
                String[] lines = f.text.split("\n", -1);
                for (ReferenceIndex.Span s : f.spans) {
                    if (s.kind() != 'd' || !s.symbol().startsWith("M " + bin + "#" + name + "(")) continue;
                    String where = f.rel + ":" + s.line();
                    if (!params.contains(params(s.symbol()))) {
                        h.add(
                                "overload",
                                "  " + RenameProof.show(s.symbol()) + " takes other parameters; a call"
                                        + " may pick the pulled-up method instead  " + where);
                        continue;
                    }
                    int first = s.line();
                    while (first > 1 && modifierLine(lines[first - 2])) first--;
                    if (f.text.substring(lineStart(f.text, first), s.start()).contains("@Override")) continue;
                    int at = lineStart(f.text, first);
                    f.ops.add(new Op(at, at, indent(lines[first - 1]) + "@Override\n"));
                    overrides++;
                }
            }
        }

        /** Cuts the members out, puts them before the target's closing brace, fixes imports, writes; the answer. */
        List<String> write() throws IOException {
            for (Member m : members) {
                from.ops.add(new Op(lineStart(from.text, m.cutFrom()), lineEnd(from.text, m.cutTo()), ""));
            }
            int end = Decl.end(toType);
            int closing = lineStart(to.text, end);
            if (end <= 0 || !line(to.text, closing).strip().startsWith("}")) {
                throw new RenameRefusal("Not moved: " + canonical(toBin) + "'s closing brace shares its line with code"
                        + " in " + to.rel + "; put it on a line of its own first.");
            }
            String fromIndent = indent(line(from.text, members.getFirst().name()));
            String braceIndent = indent(line(from.text, lineStart(from.text, Decl.end(fromType))));
            String toIndent = memberIndent(to.text, end, fromIndent, braceIndent);
            String above = closing == 0 ? "{" : line(to.text, closing - 1).strip();
            String lead = above.isEmpty() || above.endsWith("{") ? "" : "\n";
            String block = reindent(String.join("\n\n", blocks), fromIndent, toIndent);
            Op insert = new Op(closing, closing, lead + block + "\n");
            to.ops.add(insert);
            MoveImports.remove(files);

            RenamePlan plan = new RenamePlan(what, canonical(toBin));
            List<String[]> refs = new ArrayList<>();
            int imports = 0;
            for (FileEdit f : files.values()) {
                if (!f.imports.isEmpty() || !f.staticImports.isEmpty()) {
                    imports += f.imports.size() + f.staticImports.size();
                    f.ops.add(MoveImports.op(f));
                }
                if (f.ops.isEmpty()) {
                    for (Mark m : f.marks) refs.add(ref(m.symbol(), f.rel, lineNumber(f.text, m.at())));
                    continue;
                }
                Map<Op, Integer> starts = new HashMap<>();
                String after = apply(f.text, f.ops, starts);
                plan.edit(f.rel, f.text, after, changed(f, after, starts, refs));
                for (int o : f.free) refs.add(ref("*", f.rel, lineNumber(after, mapped(f.ops, o))));
                for (Mark m : f.marks) refs.add(ref(m.symbol(), f.rel, lineNumber(after, mapped(f.ops, m.at()))));
                if (f == to) {
                    int base = lineNumber(after, starts.getOrDefault(insert, 0)) + lead.length();
                    for (Mark m : blockMarks) refs.add(ref(m.symbol(), f.rel, base + m.at()));
                }
            }
            long uses = blockMarks.stream().filter(m -> m.kind() != 'd').count();
            for (FileEdit f : files.values())
                uses += f.marks.stream().filter(m -> m.kind() != 'd').count();
            RenameStore.write(w.root(), w.out(), plan);
            RenameStore.writeProof(w.out(), RenameOp.touched(g, plan), symbols, refs);
            left(w.out(), strings);
            List<String> answer = new ArrayList<>();
            answer.add((up ? "Pulled up " : "Moved ") + what + " to " + canonical(toBin) + ": " + uses
                    + (uses == 1 ? " use" : " uses") + " and " + imports + (imports == 1 ? " import" : " imports")
                    + (overrides == 0 ? "" : ", " + overrides + " @Override") + " in " + plan.after.size()
                    + (plan.after.size() == 1 ? " file" : " files") + "; each use javac bound.");
            plan.lines.forEach(
                    (rel, lines) -> answer.add("  " + rel + (lines.isEmpty() ? "" : ":" + RenamePlan.numbers(lines))));
            if (!strings.isEmpty()) {
                answer.add("Strings that spell " + name + ", left as they are (reflection?):");
                for (String s : strings) answer.add("  " + s);
            }
            return answer;
        }

        /** The lines of {@code after} each edit of {@code f} touched; an import line it wrote may hold any reference. */
        private static Set<Integer> changed(FileEdit f, String after, Map<Op, Integer> starts, List<String[]> refs) {
            Set<Integer> changed = new TreeSet<>();
            for (Op op : f.ops) {
                int s = starts.getOrDefault(op, 0);
                if (op.text().isEmpty()) {
                    changed.add(lineNumber(after, Math.min(s, Math.max(0, after.length() - 1))));
                    continue;
                }
                int first = lineNumber(after, s);
                int last = lineNumber(after, s + op.text().length() - 1);
                for (int i = first; i <= last; i++) changed.add(i);
                if (op.text().startsWith("import") || op.text().contains("\nimport ")) {
                    for (int i = first; i <= last; i++) refs.add(ref("*", f.rel, i));
                }
            }
            return changed;
        }

        private static String[] ref(String symbol, String rel, int line) {
            return new String[] {symbol, rel, String.valueOf(line)};
        }
    }

    /** How a file names a type, adding the import it needs. */
    record Resolver(GraphService.Workspace w, CodeGraph g, Map<String, FileEdit> files, Map<String, GraphNode> types) {

        /** What {@code f} writes for the type {@code binary}: its simple name, imported when it must be, else its full name. */
        String ref(FileEdit f, String binary) {
            String canonical = canonical(binary);
            String simple = canonical.substring(canonical.lastIndexOf('.') + 1);
            String top = top(binary);
            GraphNode n = types.get(top);
            if (n != null && n.sourceFile().equals(f.rel))
                return canonical.substring(
                        pkg(binary).isEmpty() ? 0 : pkg(binary).length() + 1);
            if (top.equals(binary) && pkg(binary).equals(f.pkg)) return simple;
            if (top.equals(binary) && pkg(binary).equals("java.lang")) return simple;
            Matcher m = IMPORT.matcher(f.text);
            while (m.find()) {
                if (m.group(1) != null) continue;
                String imported = m.group(2);
                if (imported.equals(canonical)
                        || imported.equals(canonical.substring(0, canonical.lastIndexOf('.')) + ".*")) {
                    f.needs.add(canonical);
                    return simple;
                }
                if (imported.endsWith("." + simple)) return canonical;
            }
            for (String i : f.imports) {
                if (!i.equals(canonical) && i.endsWith("." + simple)) return canonical;
            }
            f.imports.add(canonical);
            return simple;
        }

        /** The visibility of the member {@code symbol}, read off its declaration; {@code null} outside the workspace. */
        @Nullable
        Set<String> modifiers(String symbol) {
            String owner = symbol.substring(2, symbol.indexOf('#'));
            GraphNode n = types.get(top(owner));
            if (n == null) return null;
            GraphNode declaring = types.get(owner);
            String kind = declaring == null ? "" : String.valueOf(Decl.kind(declaring));
            // An interface's members are public, its fields static; an enum constant is both.
            if (kind.equals("interface") || kind.equals("annotation")) {
                return symbol.startsWith("F ") ? Set.of("public", "static") : Set.of("public");
            }
            FileEdit f = edit(w, g, files, n.sourceFile());
            if (f == null) return null;
            for (ReferenceIndex.Span s : f.spans) {
                if (s.kind() != 'd' || !s.symbol().equals(symbol)) continue;
                Set<String> mods = JavaText.modifiers(f.text, s.start());
                return mods.isEmpty() && kind.equals("enum") && symbol.startsWith("F ")
                        ? Set.of("public", "static")
                        : mods;
            }
            return null;
        }
    }

    /**
     * The edits of the moved code where it lands, one name at a time: a moved symbol is kept (a type
     * qualifier on it becomes the target), a member of the source type or a type enclosing it is
     * qualified, a type gets its import in the target, and a member the target cannot see or does not
     * know unqualified is a hazard. {@code qualifiers} are the ends of the type qualifiers of moved
     * symbols, rewritten with them.
     */
    record Body(
            Resolver at,
            MoveHazards h,
            FileEdit from,
            FileEdit to,
            String fromBin,
            String toBin,
            Map<String, String> symbols,
            Set<Integer> qualifiers,
            List<Op> ops,
            boolean up) {

        void name(ReferenceIndex.Span s) {
            String text = from.text;
            String where = from.rel + ":" + s.line();
            int[] qualifier = qualifier(from, s);
            if (symbols.containsKey(s.symbol())) {
                if (qualifier != null && qualifier[2] == 1)
                    ops.add(new Op(qualifier[0], qualifier[1], at.ref(to, toBin)));
                return;
            }
            if (s.kind() == 'd') return;
            if (s.symbol().startsWith("T ")) {
                String bin = s.symbol().substring(2);
                if (qualifiers.contains(s.end()) || afterQualifier(text, s.start())) return;
                if (bin.equals(toBin)
                        || bin.startsWith(toBin + "$")
                        || LOCAL.matcher(bin).find()) return;
                h.access(at, s.symbol(), bin, toBin, where);
                String ref = at.ref(to, bin);
                if (!ref.equals(text.substring(s.start(), s.end()))) ops.add(new Op(s.start(), s.end(), ref));
                return;
            }
            String owner = s.symbol().substring(2, s.symbol().indexOf('#'));
            Set<String> mods = at.modifiers(s.symbol());
            boolean instance = mods != null && !mods.contains("static");
            if (up && instance && owner.equals(fromBin) && !inherited(this, s.symbol())) {
                h.add(
                        "state",
                        "  uses " + RenameProof.show(s.symbol()) + ", which " + canonical(toBin) + " does not have  "
                                + where);
                return;
            }
            if (mods != null) h.access(at, s.symbol(), owner, toBin, where);
            boolean mine = owner.equals(fromBin) || fromBin.startsWith(owner + "$");
            int k = before(text, s.start());
            if (mine && qualifier == null && s.kind() == 'j' && k >= 0 && text.charAt(k) == '#') {
                // {@link #helper} in the moved doc comment still means the source type's helper.
                ops.add(new Op(k, k, at.ref(to, owner)));
                return;
            }
            if (qualifier != null || afterQualifier(text, s.start())) return;
            if (owner.equals(toBin) || toBin.startsWith(owner + "$")) return;
            if (up && (instance || mods == null)) return;
            if (mine) {
                if (mods != null && !mods.contains("static")) {
                    h.add("instance", "  uses " + RenameProof.show(s.symbol()) + ", an instance member  " + where);
                    return;
                }
                h.access(at, "T " + owner, owner, toBin, where);
                ops.add(new Op(s.start(), s.start(), at.ref(to, owner) + "."));
                return;
            }
            String imported = MoveImports.staticImport(text, canonical(owner), ReferenceIndex.simpleName(s.symbol()));
            if (imported != null) {
                to.staticImports.add(imported);
            } else if (!up) {
                h.add(
                        "unqualified",
                        "  uses " + RenameProof.show(s.symbol()) + " unqualified, which " + canonical(toBin)
                                + " does not see  " + where);
            }
        }
    }

    /**
     * Whether a method of the source type the pulled-up code calls is one the target has too: the
     * target declares it, or the source type's declaration overrides it ({@code @Override}).
     */
    private static boolean inherited(Body b, String symbol) {
        if (!symbol.startsWith("M ")) return false;
        String there = "M " + b.toBin() + symbol.substring(symbol.indexOf('#'));
        for (ReferenceIndex.Span s : b.to().spans) {
            if (s.kind() == 'd' && s.symbol().equals(there)) return true;
        }
        for (ReferenceIndex.Span s : b.from().spans) {
            if (s.kind() != 'd' || !s.symbol().equals(symbol)) continue;
            String[] lines = b.from().text.split("\n", -1);
            int first = s.line();
            while (first > 1 && modifierLine(lines[first - 2])) first--;
            return b.from()
                    .text
                    .substring(lineStart(b.from().text, first), s.start())
                    .contains("@Override");
        }
        return false;
    }

    /** {@code M a.B#name(params)ret} → {@code (params)}. */
    private static String params(String symbol) {
        String sig = symbol.substring(symbol.indexOf('#'));
        int open = sig.indexOf('(');
        int close = sig.indexOf(')');
        return open < 0 || close < 0 ? sig : sig.substring(open, close + 1);
    }

    /** Whether {@code type} extends {@code binary}, as its declaration writes the name. */
    private static boolean extended(GraphNode type, String binary) {
        String canonical = canonical(binary);
        String simple = canonical.substring(canonical.lastIndexOf('.') + 1);
        for (String e : Decl.superclass(type)) {
            String raw = e.contains("<") ? e.substring(0, e.indexOf('<')).strip() : e.strip();
            if (raw.equals(canonical) || raw.equals(binary) || raw.equals(simple) || canonical.endsWith("." + raw))
                return true;
        }
        return false;
    }

    /** {@code roots} and every workspace type that extends one of them, directly or not. */
    static Set<String> subtypes(Map<String, GraphNode> types, Set<String> roots) {
        Set<String> out = new TreeSet<>(roots);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, GraphNode> t : types.entrySet()) {
                if (out.contains(t.getKey())) continue;
                for (String r : List.copyOf(out)) {
                    if (extended(t.getValue(), r)) {
                        out.add(t.getKey());
                        grew = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** One use of a moved symbol outside the moved code: the qualifier becomes the target, or the target is added. */
    private static void use(Resolver at, MoveHazards h, FileEdit f, String toBin, String moved, ReferenceIndex.Span s) {
        f.marks.add(new Mark(s.start(), moved, s.kind()));
        if (s.kind() == 'd') return;
        int[] q = qualifier(f, s);
        String where = f.rel + ":" + s.line() + "  " + RenamePlan.clip(line(f.text, s.start()));
        if (q != null && q[2] == 1) {
            f.ops.add(new Op(q[0], q[1], at.ref(f, toBin)));
        } else if (q != null) {
            h.add("instance", "  reaches it through an instance, not its type  " + where);
        } else if (s.kind() == 'j' && f.text.charAt(before(f.text, s.start())) == '#') {
            f.ops.add(new Op(before(f.text, s.start()), before(f.text, s.start()), at.ref(f, toBin)));
        } else if (afterQualifier(f.text, s.start())) {
            h.add("generic", "  a use this move cannot rewrite (type arguments or an expression before it)  " + where);
        } else if (!f.text.contains(
                "import static " + canonical(s.symbol().substring(2, s.symbol().indexOf('#'))) + "."
                        + ReferenceIndex.simpleName(s.symbol()) + ";")) {
            f.ops.add(new Op(s.start(), s.start(), at.ref(f, toBin) + "."));
        }
    }

    /**
     * The qualifier before the name at {@code s}, as {@code {start, end, type}}: {@code type} 1 when a
     * type javac resolved ({@code Order.}, {@code org.acme.Order::}, {@code Order#}), 0 for an
     * expression; {@code null} when the name is unqualified.
     */
    static int @Nullable [] qualifier(FileEdit f, ReferenceIndex.Span s) {
        String t = f.text;
        int k = before(t, s.start());
        if (k < 0) return null;
        int sep;
        if (t.charAt(k) == '.' && (k == 0 || t.charAt(k - 1) != '.')) sep = k;
        else if (t.charAt(k) == ':' && k > 0 && t.charAt(k - 1) == ':') sep = k - 1;
        else if (t.charAt(k) == '#' && s.kind() == 'j' && k > 0 && Character.isJavaIdentifierPart(t.charAt(k - 1)))
            sep = k;
        else return null;
        int end = before(t, sep) + 1;
        for (ReferenceIndex.Span q : f.spans) {
            if (q.end() == end && q.symbol().startsWith("T ")) {
                int from = q.start();
                while (from > 0 && (Character.isJavaIdentifierPart(t.charAt(from - 1)) || t.charAt(from - 1) == '.'))
                    from--;
                return new int[] {from, end, 1};
            }
        }
        return new int[] {end, end, 0};
    }

    /** The declarations named {@code name} in the source type, one per overload, with the lines each takes. */
    private static List<Member> members(FileEdit from, String fromBin, String name, String address) {
        List<ReferenceIndex.Span> declared = from.spans.stream()
                .filter(s -> s.kind() == 'd')
                .filter(s -> s.symbol().startsWith("M " + fromBin + "#" + name + "(")
                        || s.symbol().equals("F " + fromBin + "#" + name))
                .toList();
        if (declared.isEmpty()) throw new RenameRefusal("Not moved: no method or field '" + address + "'.");
        if (declared.stream().map(s -> s.symbol().charAt(0)).distinct().count() > 1) {
            throw new RenameRefusal("Not moved: '" + address + "' names a method and a field; rename one first.");
        }
        String[] lines = from.text.split("\n", -1);
        List<Member> out = new ArrayList<>();
        for (ReferenceIndex.Span s : declared) {
            Set<String> mods = modifiers(from.text, s.start());
            if (s.symbol().startsWith("F ") && mods.isEmpty()) {
                throw new RenameRefusal(
                        "Not moved: " + address + " is an enum constant; a move takes a method or a field.");
            }
            int to = s.symbol().startsWith("F ")
                    ? SafeDelete.statementEnd(lines, s.line())
                    : lineNumber(from.text, methodEnd(from.text, s.start()));
            for (ReferenceIndex.Span o : from.spans) {
                if (s.symbol().startsWith("F ")
                        && o.kind() == 'd'
                        && o.symbol().startsWith("F ")
                        && !o.symbol().equals(s.symbol())
                        && statementOf(from.text, o.start()) == statementOf(from.text, s.start())) {
                    throw new RenameRefusal("Not moved: " + address + " is declared in one statement with "
                            + RenameProof.show(o.symbol()) + " (" + from.rel + ":" + o.line() + "); split it first.");
                }
            }
            int first = s.line();
            while (first > 1 && modifierLine(lines[first - 2])) first--;
            String head = lines[first - 1].strip();
            String tail = lines[to - 1].strip();
            if (head.startsWith("}") || !(tail.endsWith("}") || tail.endsWith(";"))) {
                throw new RenameRefusal("Not moved: " + address + " shares its lines with other code in " + from.rel
                        + "; put it on lines of its own first.");
            }
            Set<Integer> cut = SafeDelete.widened(lines, first, to);
            int cutFrom = ((TreeSet<Integer>) cut).first();
            int cutTo = ((TreeSet<Integer>) cut).last();
            int blockFrom = cutFrom;
            while (blockFrom < first && lines[blockFrom - 1].isBlank()) blockFrom++;
            out.add(new Member(s.symbol(), s.start(), blockFrom, to, cutFrom, cutTo, mods));
        }
        out.sort(Comparator.comparingInt(Member::from));
        return out;
    }

    /** The file's edit, created on first use; {@code null} when javac's index does not describe it as it stands. */
    static @Nullable FileEdit edit(GraphService.Workspace w, CodeGraph g, Map<String, FileEdit> files, String rel) {
        FileEdit f = files.get(rel);
        if (f != null) return f;
        String text = RenameText.read(w.root(), rel);
        if (text == null) return null;
        List<ReferenceIndex.Span> spans = ReferenceIndex.of(w.root(), g.modules(), rel, text);
        if (spans == null) return null;
        f = new FileEdit(rel, text, spans);
        files.put(rel, f);
        return f;
    }

    /** {@code text} with {@code ops} applied; {@code starts}, when given, gets where each op's text begins in the result. */
    static String apply(String text, List<Op> ops, @Nullable Map<Op, Integer> starts) {
        List<Op> sorted = new ArrayList<>(ops);
        sorted.sort(Comparator.comparingInt(Op::start).thenComparingInt(Op::end));
        StringBuilder out = new StringBuilder(text.length() + 256);
        int at = 0;
        for (Op op : sorted) {
            if (op.start() < at) throw new RenameRefusal("Not moved: two edits overlap; move it by hand.");
            out.append(text, at, op.start());
            if (starts != null) starts.put(op, out.length());
            out.append(op.text());
            at = op.end();
        }
        out.append(text, at, text.length());
        return out.toString();
    }

    /** Where {@code offset} of the text before {@code ops} stands after them. */
    static int mapped(List<Op> ops, int offset) {
        int out = offset;
        for (Op op : ops) {
            if (op.end() <= offset) out += op.text().length() - (op.end() - op.start());
        }
        return out;
    }

    static List<Op> shifted(List<Op> ops, int by) {
        return ops.stream()
                .map(o -> new Op(o.start() + by, o.end() + by, o.text()))
                .toList();
    }

    /** {@code block} with each line's leading {@code from} indent replaced by {@code to}. */
    private static String reindent(String block, String from, String to) {
        if (from.equals(to)) return block;
        List<String> out = new ArrayList<>();
        for (String l : block.split("\n", -1)) out.add(l.startsWith(from) ? to + l.substring(from.length()) : l);
        return String.join("\n", out);
    }

    /** The indent of the target's members: its closing brace's, plus the step the source's members take past its own. */
    private static String memberIndent(String text, int closingLine, String memberIndent, String braceIndent) {
        String brace = indent(line(text, lineStart(text, closingLine)));
        String step = memberIndent.startsWith(braceIndent) ? memberIndent.substring(braceIndent.length()) : "    ";
        return brace + (step.isEmpty() ? "    " : step);
    }

    static boolean inside(List<Member> members, int line) {
        for (Member m : members) {
            if (line >= m.cutFrom() && line <= m.cutTo()) return true;
        }
        return false;
    }

    /** Every workspace type by its binary name. */
    static Map<String, GraphNode> types(CodeGraph g) {
        Map<String, GraphNode> out = new HashMap<>();
        for (GraphNode n : g.nodes()) {
            if (Decl.isType(n) && n.extra().get(Linker.QUALIFIED_NAME) instanceof String q) out.putIfAbsent(q, n);
        }
        return out;
    }

    private static GraphNode oneType(CodeGraph g, String name) {
        List<GraphNode> types = GraphUses.types(g, Scope.ALL, name);
        if (types.isEmpty()) throw new RenameRefusal("Not moved: no type '" + name + "' in the workspace.");
        if (types.size() > 1) {
            List<String> shown = new ArrayList<>();
            for (GraphNode t : types.subList(0, Math.min(types.size(), 8))) {
                shown.add("  " + canonical(qualified(t)) + "  " + t.sourceFile());
            }
            throw new RenameRefusal(
                    "'" + name + "' names " + types.size() + " types; qualify it:\n" + String.join("\n", shown));
        }
        return types.getFirst();
    }

    /**
     * The workspace class {@code type} extends, as its declaration names it: qualified, or a simple
     * name resolved as javac does, through the file's imports before its own package.
     */
    private static GraphNode parent(Path root, CodeGraph g, GraphNode type) {
        String self = canonical(qualified(type));
        List<String> ext = Decl.superclass(type);
        if (ext.isEmpty()) throw new RenameRefusal("Not pulled up: " + self + " extends no class.");
        String raw = ext.getFirst().replaceFirst("<.*", "").strip();
        List<GraphNode> named = GraphUses.types(g, Scope.ALL, raw);
        if (named.isEmpty()) {
            throw new RenameRefusal("Not pulled up: " + self + " extends " + raw + ", which is not in the workspace.");
        }
        if (named.size() > 1) {
            String text = RenameText.read(root, type.sourceFile());
            Matcher imported = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\." + Pattern.quote(raw) + "\\s*;")
                    .matcher(text == null ? "" : text);
            String meant = (imported.find() ? imported.group(1) : pkg(qualified(type))) + "." + raw;
            for (GraphNode t : named) if (canonical(qualified(t)).equals(meant)) return t;
        }
        return oneType(g, raw);
    }

    static String qualified(GraphNode type) {
        return String.valueOf(type.extra().get(Linker.QUALIFIED_NAME));
    }

    /** {@code a.b.Outer$Inner} → {@code a.b.Outer.Inner}. */
    static String canonical(String binary) {
        return binary.replace('$', '.');
    }

    /** The top-level type of a binary name: {@code a.b.Outer$Inner} → {@code a.b.Outer}. */
    static String top(String binary) {
        int d = binary.indexOf('$');
        return d < 0 ? binary : binary.substring(0, d);
    }

    /** The package of a binary name. */
    static String pkg(String binary) {
        String top = top(binary);
        int d = top.lastIndexOf('.');
        return d < 0 ? "" : top.substring(0, d);
    }

    /** Where a move records the strings it left: they end its proven answer with "Not done". */
    private static final String LEFT = "move-left";

    /** Records {@code strings} for the check after the move, or forgets the last move's when there are none. */
    private static void left(Path out, List<String> strings) throws IOException {
        Path file = RenameStore.dir(out).resolve(LEFT);
        if (strings.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, String.join("\n", strings) + "\n");
    }

    /**
     * The line a proven move ends with while a string it listed still spells the name, read once and
     * forgotten; {@code ""} when there is none.
     */
    private static String left(Path out) throws IOException {
        Path file = RenameStore.dir(out).resolve(LEFT);
        if (!Files.isRegularFile(file)) return "";
        List<String> places = new ArrayList<>();
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int gap = l.indexOf("  ");
            if (!l.isBlank()) places.add(gap < 0 ? l : l.substring(0, gap));
        }
        Files.deleteIfExists(file);
        if (places.isEmpty()) return "";
        List<String> shown = places.subList(0, Math.min(places.size(), 3));
        return "\nNot done: a string still spells the moved name at " + String.join(", ", shown)
                + (places.size() > shown.size() ? " and " + (places.size() - shown.size()) + " more" : "")
                + "; change each one that means the moved member.";
    }

    /** Undoes the last move whose modules did not compile; the answer's line. */
    static String undo(Path root, Path out, String why) throws IOException {
        List<String> restored = RenameStore.undo(root, out);
        if (restored.isEmpty()) return "";
        return "Rolled back, nothing is changed (" + restored.size() + (restored.size() == 1 ? " file" : " files")
                + " restored): after the move " + RenameProof.relative(root, why) + ".";
    }
}
