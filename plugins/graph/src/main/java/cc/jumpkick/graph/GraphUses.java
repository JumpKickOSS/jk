// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphQuery;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.ImpactLines;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.codegraph.extract.Extractors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Every use of a type, an annotation, a field or a constructor, in the shape {@code callers} answers
 * a method with: each use under the declaration holding it, {@code Owner.member()} at {@code
 * file:line} with the line quoted, main sources before tests, counted, and a field's writes told
 * from its reads.
 *
 * <p>Java uses come from the reference index javac writes during the compile, so each is at its
 * exact line. A source the index does not describe as it stands (Kotlin, Groovy, an edited or
 * never-compiled Java file) is searched for the name instead, and its matches are listed {@code
 * (inferred)}: the answer says it is complete only when no such source spells the name. Constructor
 * calls come from the compiled classes, as method calls do, in every language.
 */
final class GraphUses {

    private GraphUses() {}

    /** The most uses of one declaration quoted; the rest stay line numbers. */
    private static final int QUOTED = 2;

    private static final int QUOTE_WIDTH = 160;

    /** How many lines above a declaration its annotations may start. */
    private static final int ANNOTATION_REACH = 8;

    /** What an address names: a type (an annotation is one), a field, or a type's constructors. */
    private enum Kind {
        TYPE,
        FIELD,
        CONSTRUCTOR
    }

    private record Subject(
            Kind kind,
            GraphNode type,
            String qualified,
            @Nullable String member,
            boolean annotation) {

        String symbol() {
            return kind == Kind.FIELD ? "F " + qualified + "#" + member : "T " + qualified;
        }

        String simpleName() {
            if (member != null) return member;
            return qualified.substring(Math.max(qualified.lastIndexOf('.'), qualified.lastIndexOf('$')) + 1);
        }
    }

    /**
     * One use: in {@code file} at {@code line}, under declaration {@code holder} (a graph node id, or
     * {@code null} for a line outside every declaration), written there when {@code write}, a doc
     * link when {@code doc}, matched by name rather than resolved when {@code inferred}.
     */
    private record Use(String file, int line, @Nullable String holder, boolean write, boolean doc, boolean inferred) {}

    /**
     * The answer for {@code address} when it names a type, an annotation, a field or a constructor,
     * with the uncompiled sets it may miss uses in; {@code null} when it names a method, or nothing a
     * type declares, which {@code callers} answers.
     */
    static GraphService.@Nullable Reply answer(
            GraphService.Workspace w, CodeGraph g, Scope scope, GraphTools tools, String address, int budget) {
        String wanted = address.strip();
        boolean annotation = wanted.startsWith("@");
        if (annotation) wanted = wanted.substring(1);
        if (wanted.endsWith("()")) wanted = wanted.substring(0, wanted.length() - 2);
        if (wanted.isEmpty()) return null;
        List<GraphNode> types = types(g, scope, wanted);
        Subject subject;
        if (!types.isEmpty()) {
            if (types.size() > 1) return new GraphService.Reply(ambiguous(wanted, types), List.of());
            subject = subject(Kind.TYPE, types.getFirst(), null, annotation);
        } else {
            int dot = wanted.lastIndexOf('.');
            if (dot <= 0 || dot == wanted.length() - 1) return null;
            String member = wanted.substring(dot + 1);
            List<GraphNode> owners = types(g, scope, wanted.substring(0, dot));
            if (owners.isEmpty()) return null;
            GraphNode owner = owners.getFirst();
            String simple = simple(qualified(owner));
            if (member.equals(simple) || member.equals("<init>") || member.equals("new")) {
                if (owners.size() > 1)
                    return new GraphService.Reply(ambiguous(wanted.substring(0, dot), owners), List.of());
                return GraphService.Reply.of(w, constructors(w, g, scope, owner, budget));
            }
            if (!tools.members(wanted).isEmpty()) return null;
            if (owners.size() > 1)
                return new GraphService.Reply(ambiguous(wanted.substring(0, dot), owners), List.of());
            if (!declaresField(w, g, owner, member)) return null;
            subject = subject(Kind.FIELD, owner, member, false);
        }
        // Every source the index does not describe is searched by name, those of a module with no
        // compiled classes included, so no note sends the agent to search them again.
        return new GraphService.Reply(uses(w, g, scope, tools, subject, budget), List.of());
    }

    private static Subject subject(Kind kind, GraphNode type, @Nullable String member, boolean annotation) {
        boolean isAnnotation = annotation || "annotation".equals(Decl.kind(type));
        return new Subject(kind, type, qualified(type), member, isAnnotation);
    }

    /** The workspace types {@code name} names: a simple, nested ({@code Outer.Inner}) or qualified name. */
    private static List<GraphNode> types(CodeGraph g, Scope scope, String name) {
        String dotted = name.replace('$', '.');
        List<GraphNode> out = new ArrayList<>();
        for (GraphNode n : g.nodes()) {
            if (!Decl.isType(n) || !(n.extra().get(Linker.QUALIFIED_NAME) instanceof String q)) continue;
            String d = q.replace('$', '.');
            if (d.equals(dotted) || d.endsWith("." + dotted)) out.add(n);
        }
        out.sort(Comparator.comparing(GraphNode::id));
        List<GraphNode> scoped = out.stream().filter(scope::contains).toList();
        return scoped.isEmpty() ? out : scoped;
    }

    private static String qualified(GraphNode type) {
        return String.valueOf(type.extra().get(Linker.QUALIFIED_NAME));
    }

    private static String simple(String qualified) {
        return qualified.substring(Math.max(qualified.lastIndexOf('.'), qualified.lastIndexOf('$')) + 1);
    }

    private static String ambiguous(String name, List<GraphNode> types) {
        List<String> lines = new ArrayList<>();
        lines.add("'" + name + "' names " + types.size() + " types; qualify it:");
        for (GraphNode t : types.subList(0, Math.min(types.size(), 10))) {
            lines.add("  " + qualified(t).replace('$', '.') + "  " + t.sourceFile() + ":" + Decl.line(t));
        }
        return String.join("\n", lines);
    }

    /**
     * Whether {@code owner} declares field {@code name}: its source's index says so, or, for a source
     * the index does not describe, the source spells the name.
     */
    private static boolean declaresField(GraphService.Workspace w, CodeGraph g, GraphNode owner, String name) {
        String rel = owner.sourceFile();
        String text = read(w.root(), rel);
        if (text == null) return false;
        List<ReferenceIndex.Span> spans =
                rel.endsWith(".java") ? ReferenceIndex.of(w.root(), g.modules(), rel, text) : null;
        if (spans == null) return word(name).matcher(text).find();
        String symbol = "F " + qualified(owner) + "#" + name;
        return spans.stream().anyMatch(s -> s.kind() == 'd' && s.symbol().equals(symbol));
    }

    /** The uses of a type, an annotation or a field, from the index where it holds them, else by name. */
    private static String uses(
            GraphService.Workspace w, CodeGraph g, Scope scope, GraphTools tools, Subject s, int budget) {
        Modules modules = g.modules();
        String home = prefix(modules, s.type().sourceFile());
        Set<String> reaching = w.reach().reaching(List.of(home), modules);
        Set<String> stale = new HashSet<>();
        for (GraphBuild.ClassIndex c : w.indexes()) stale.addAll(c.stale());
        Set<String> files = new TreeSet<>();
        for (GraphNode n : g.nodes()) {
            String rel = n.sourceFile();
            if (rel.isEmpty() || !Extractors.isJvmSource(rel) || rel.contains("/resources/")) continue;
            // A source of no source set (a fixture, a sample) compiles into nothing a build links.
            if (ClassSources.set(modules, rel) == null) continue;
            if (reaching != null && !reaching.contains(prefix(modules, rel))) continue;
            if (scope.containsPath(rel)) files.add(rel);
        }
        String symbol = s.symbol();
        String needle = "\n=" + symbol + "\n";
        Pattern word = word(s.simpleName());
        Map<String, List<Path>> dirs = new HashMap<>();
        List<Use> uses = new ArrayList<>();
        Set<String> guessed = new TreeSet<>();
        Map<String, List<Holder>> holders = holders(g, files);
        for (String rel : files) {
            boolean indexed = rel.endsWith(".java")
                    && !stale.contains(rel)
                    && w.uncompiled().stream().noneMatch(u -> u.holds(modules, rel));
            String text = null;
            if (indexed) {
                String prefix = prefix(modules, rel);
                Path file = ReferenceIndex.file(
                        dirs.computeIfAbsent(prefix, p -> ReferenceIndex.dirs(w.root(), p)),
                        rel.substring(prefix.length()));
                String index = file == null ? null : readPath(file);
                if (index != null && index.startsWith(ReferenceIndex.HEADER)) {
                    if (!index.contains(needle)) continue;
                    String source = read(w.root(), rel);
                    List<ReferenceIndex.Span> spans = source == null
                            ? null
                            : ReferenceIndex.of(index.lines().toList(), source);
                    if (source != null && spans != null) {
                        exact(spans, symbol, rel, source, holders.getOrDefault(rel, List.of()), s, uses);
                        continue;
                    }
                    text = source;
                }
            }
            if (text == null) text = read(w.root(), rel);
            if (text == null || !word.matcher(text).find()) continue;
            int before = uses.size();
            inferred(text, word, rel, holders.getOrDefault(rel, List.of()), s, uses);
            if (uses.size() > before) guessed.add(rel);
        }
        return Budget.lines(
                render(g, w.root(), tools, s, uses, guessed),
                budget,
                "Raise the budget, or narrow with scope, modules or source_set");
    }

    /** {@code symbol}'s uses in an indexed file; an import is kept only when the file has no other use. */
    private static void exact(
            List<ReferenceIndex.Span> spans,
            String symbol,
            String rel,
            String text,
            List<Holder> holders,
            Subject s,
            List<Use> uses) {
        List<String> lines = text.lines().toList();
        List<Use> mine = new ArrayList<>();
        List<Use> imports = new ArrayList<>();
        for (ReferenceIndex.Span sp : spans) {
            if (!sp.symbol().equals(symbol)) continue;
            if (sp.kind() == 'd') {
                // A field declared with an initializer is written there, as the compiled classes say.
                if (s.kind() == Kind.FIELD && initialized(text, sp.end())) {
                    mine.add(new Use(rel, sp.line(), holder(holders, sp.line(), lines), true, false, false));
                }
                continue;
            }
            Use u = new Use(
                    rel, sp.line(), holder(holders, sp.line(), lines), sp.kind() == 'w', sp.kind() == 'j', false);
            boolean imported = sp.line() <= lines.size()
                    && lines.get(sp.line() - 1).stripLeading().startsWith("import ");
            (imported ? imports : mine).add(u);
        }
        uses.addAll(mine.isEmpty() ? imports : mine);
    }

    /** Whether the declared name ending at {@code end} is followed by {@code =}, its initializer. */
    static boolean initialized(String text, int end) {
        int i = end;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i < text.length() && text.charAt(i) == '=' && (i + 1 == text.length() || text.charAt(i + 1) != '=');
    }

    /** The lines of a source the index does not describe that spell the name, outside comments where it can tell. */
    private static void inferred(
            String text, Pattern word, String rel, List<Holder> holders, Subject s, List<Use> uses) {
        List<String> lines = text.lines().toList();
        // The declaring file names the declaration once: its first line that reads as one is not a use.
        String declaration = rel.equals(s.type().sourceFile()) ? s.simpleName() : null;
        List<Use> mine = new ArrayList<>();
        List<Use> imports = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String code = line.strip();
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue;
            // A Kotlin or Groovy string template is code; a Java string is not.
            if (!word.matcher(rel.endsWith(".java") ? unquoted(line) : line).find()) continue;
            if (declaration != null && declares(line, declaration, s.kind())) {
                declaration = null;
                continue;
            }
            Use u = new Use(rel, i + 1, holder(holders, i + 1, lines), false, false, true);
            (code.startsWith("import ") ? imports : mine).add(u);
        }
        uses.addAll(mine.isEmpty() ? imports : mine);
    }

    /** {@code line} with the contents of its string and character literals blanked: a name in one is no use. */
    static String unquoted(String line) {
        StringBuilder out = new StringBuilder(line.length());
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                    out.append("  ");
                    continue;
                }
                if (c == quote) quote = 0;
                out.append(quote == 0 ? c : ' ');
            } else {
                if (c == '"' || c == '\'') quote = c;
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Whether {@code line} of the declaring file is the declaration itself rather than a use. */
    private static boolean declares(String line, String name, Kind kind) {
        if (kind == Kind.TYPE) {
            return Pattern.compile("\\b(class|interface|enum|record|object|trait)\\s+" + Pattern.quote(name) + "\\b")
                    .matcher(line)
                    .find();
        }
        return Pattern.compile("\\b(val|var|def)\\s+" + Pattern.quote(name) + "\\b|[\\w>\\]]\\s+" + Pattern.quote(name)
                        + "\\s*(=[^=]|;|$)")
                .matcher(line)
                .find();
    }

    /** A declaration's span in its file: the node and its first and last lines. */
    private record Holder(String id, int from, int to) {}

    /** The declarations of each of {@code files}, smallest span first, so the innermost holding a line wins. */
    private static Map<String, List<Holder>> holders(CodeGraph g, Set<String> files) {
        Map<String, List<Holder>> out = new HashMap<>();
        for (GraphNode n : g.nodes()) {
            if (!files.contains(n.sourceFile())) continue;
            String kind = Decl.kind(n);
            if (kind == null) continue;
            int from = Decl.line(n);
            int to = Decl.end(n);
            List<Holder> list = out.computeIfAbsent(n.sourceFile(), k -> new ArrayList<>());
            if (from > 0 && to >= from) list.add(new Holder(n.id(), from, to));
            for (int[] more : Decl.more(n)) {
                if (more[0] > 0 && more[1] >= more[0]) list.add(new Holder(n.id(), more[0], more[1]));
            }
        }
        out.values().forEach(l -> l.sort(Comparator.comparingInt((Holder h) -> h.to() - h.from())));
        return out;
    }

    /**
     * The innermost declaration holding {@code line}; for an annotation line above a declaration,
     * the declaration it annotates, which starts within a few lines after it.
     */
    private static @Nullable String holder(List<Holder> holders, int line, List<String> lines) {
        if (line <= lines.size() && lines.get(line - 1).stripLeading().startsWith("@")) {
            Holder next = null;
            for (Holder h : holders) {
                if (h.from() > line
                        && h.from() - line <= ANNOTATION_REACH
                        && (next == null || h.from() < next.from())) {
                    next = h;
                }
            }
            if (next != null) return next.id();
        }
        for (Holder h : holders) {
            if (h.from() <= line && line <= h.to()) return h.id();
        }
        return null;
    }

    private static List<String> render(
            CodeGraph g, Path root, GraphTools tools, Subject s, List<Use> uses, Set<String> guessed) {
        String subject = display(g, s);
        boolean field = s.kind() == Kind.FIELD;
        List<Use> code = uses.stream().filter(u -> !u.doc()).toList();
        long docs = uses.size() - code.size();
        long main = code.stream().filter(u -> !test(g, u)).count();
        long writes = code.stream().filter(Use::write).count();
        List<String> lines = new ArrayList<>();
        lines.add("Uses of " + subject + ": " + main + " in main sources, " + (code.size() - main) + " in tests"
                + (field
                        ? " (" + writes + (writes == 1 ? " write, " : " writes, ") + (code.size() - writes) + " read"
                                + (code.size() - writes == 1 ? ")" : "s)")
                        : "")
                + (docs == 0 ? "." : ", and " + docs + (docs == 1 ? " doc link." : " doc links.")));
        if (guessed.isEmpty()) {
            lines.add("Complete: javac resolved each Java use at its line, and no Kotlin, Groovy or edited source"
                    + " spells the name; no search needed.");
        } else {
            lines.add("Not complete: " + guessed.size() + (guessed.size() == 1 ? " source" : " sources")
                    + " javac has not indexed as they stand (Kotlin, Groovy, edited or uncompiled Java) spell the"
                    + " name; those lines are matched by name and marked (inferred). Java uses elsewhere are exact.");
        }
        // What else the change reaches, the places no compiler checks first (a field's anchors, a
        // type's); the Java uses follow, which the build checks, then a field's accessors and tests.
        ImpactLines impact = field && s.member() != null
                ? tools.fieldImpact(s.type().id(), s.member())
                : s.kind() == Kind.TYPE ? tools.typeImpact(s.type().id()) : new ImpactLines(List.of(), List.of());
        lines.addAll(impact.before());
        if (code.isEmpty() && docs == 0) {
            lines.add(guessed.isEmpty() ? "No Java source uses it." : "Nothing resolved uses it.");
            lines.addAll(impact.after());
            return lines;
        }
        Map<String, List<String>> text = new HashMap<>();
        for (boolean inTest : List.of(false, true)) {
            List<Use> set = uses.stream().filter(u -> test(g, u) == inTest).toList();
            if (set.isEmpty()) continue;
            lines.add(inTest ? "test:" : "main:");
            if (field) {
                group(lines, g, root, text, set.stream().filter(Use::write).toList(), "writes");
                group(
                        lines,
                        g,
                        root,
                        text,
                        set.stream().filter(u -> !u.write() && !u.doc()).toList(),
                        "reads");
            } else {
                group(lines, g, root, text, set.stream().filter(u -> !u.doc()).toList(), null);
            }
            group(lines, g, root, text, set.stream().filter(Use::doc).toList(), "doc links");
        }
        lines.addAll(impact.after());
        return lines;
    }

    /** One group of uses, one line per holding declaration and file, exact before inferred, quoted. */
    private static void group(
            List<String> lines,
            CodeGraph g,
            Path root,
            Map<String, List<String>> text,
            List<Use> set,
            @Nullable String title) {
        if (set.isEmpty()) return;
        String indent = "  ";
        if (title != null) {
            lines.add("  " + title + ":");
            indent = "    ";
        }
        Map<String, List<Use>> by = new TreeMap<>();
        for (Use u : set) {
            String name = u.holder() == null ? u.file() : name(g, u.holder());
            by.computeIfAbsent((u.inferred() ? "1" : "0") + name + "\t" + u.file(), k -> new ArrayList<>())
                    .add(u);
        }
        boolean doc = "doc links".equals(title);
        for (Map.Entry<String, List<Use>> e : by.entrySet()) {
            List<Use> at = e.getValue();
            Use first = at.getFirst();
            String key = e.getKey();
            String name = key.substring(1, key.indexOf('\t'));
            List<Integer> numbers =
                    at.stream().map(Use::line).distinct().sorted().toList();
            lines.add(indent + (first.holder() == null ? "" : name + "  ") + first.file() + ":"
                    + String.join(",", numbers.stream().map(String::valueOf).toList())
                    + (first.inferred() ? " (inferred)" : ""));
            if (doc) continue;
            List<String> source = text.computeIfAbsent(first.file(), f -> {
                String body = read(root, f);
                return body == null ? List.of() : body.lines().toList();
            });
            for (int n : numbers.subList(0, Math.min(numbers.size(), QUOTED))) {
                if (n < 1 || n > source.size()) continue;
                String line = source.get(n - 1).strip();
                if (line.length() > QUOTE_WIDTH) line = line.substring(0, QUOTE_WIDTH) + "…";
                lines.add(indent + "    L" + n + "| " + line);
            }
        }
    }

    /**
     * How an answer names declaration {@code id}: as {@code callers} does, and a constant, which its
     * type contains rather than declares as a method, with its type too ({@code Limits.MAX}).
     */
    private static String name(CodeGraph g, String id) {
        String shown = GraphQuery.clean(g.display(id));
        if (!shown.startsWith(".")) return shown;
        for (GraphEdge e : g.incident(id)) {
            if (e.target().equals(id) && e.relation().equals(Relations.CONTAINS)) {
                GraphNode owner = g.node(e.source());
                if (owner != null && Decl.isType(owner)) return GraphQuery.clean(g.display(owner.id())) + shown;
            }
        }
        return shown.substring(1);
    }

    private static String display(CodeGraph g, Subject s) {
        String type = GraphQuery.clean(g.display(s.type().id()));
        return switch (s.kind()) {
            case FIELD -> type + "." + s.member() + " (field)";
            case TYPE -> s.annotation() ? "@" + type + " (annotation)" : type + " (" + kindOf(s.type()) + ")";
            case CONSTRUCTOR -> type + " (constructors)";
        };
    }

    private static String kindOf(GraphNode type) {
        String kind = Decl.kind(type);
        return kind == null ? "type" : kind;
    }

    /**
     * Every call of {@code type}'s constructors, from the compiled classes as {@code callers} reads a
     * method's: a {@code new}, a subclass constructor's {@code super(…)}, a method reference.
     */
    private static String constructors(GraphService.Workspace w, CodeGraph g, Scope scope, GraphNode type, int budget) {
        Map<String, GraphEdge> by = new LinkedHashMap<>();
        for (GraphEdge e : g.incident(type.id())) {
            if (!e.target().equals(type.id()) || !e.relation().equals(Relations.CALLS)) continue;
            if (e.source().equals(type.id()) || !scope.contains(g.node(e.source()))) continue;
            // A call with no line is code the compiler generated (a value class's equals boxing itself).
            if (e.sites().isEmpty()) continue;
            by.merge(e.source(), e, GraphEdge::withSitesOf);
        }
        String subject = GraphQuery.clean(g.display(type.id()));
        List<Map.Entry<String, GraphEdge>> main = new ArrayList<>();
        List<Map.Entry<String, GraphEdge>> test = new ArrayList<>();
        for (Map.Entry<String, GraphEdge> e : by.entrySet()) {
            boolean inTest = g.modules().sourceSet(e.getValue().sourceFile()) == Modules.SourceSet.TEST;
            (inTest ? test : main).add(e);
        }
        Comparator<Map.Entry<String, GraphEdge>> order = Comparator.comparing(e -> g.display(e.getKey()));
        main.sort(order);
        test.sort(order);
        List<String> lines = new ArrayList<>();
        lines.add("Callers of " + subject + "'s constructors: " + main.size() + " in main sources, " + test.size()
                + " in tests.");
        lines.add(
                w.uncompiled().isEmpty()
                        ? "Complete from the compiled classes; no search needed unless a note below says otherwise. A new,"
                                + " a subclass constructor's super(…) and a method reference all count."
                        : "Not complete: the note below names the modules with no compiled classes.");
        Map<String, List<String>> text = new HashMap<>();
        for (List<Map.Entry<String, GraphEdge>> set : List.of(main, test)) {
            if (set.isEmpty()) continue;
            lines.add(set == main ? "main:" : "test:");
            for (Map.Entry<String, GraphEdge> e : set) {
                GraphEdge edge = e.getValue();
                List<String> sites =
                        edge.sites().stream().map(site -> site.replace("L", "")).toList();
                lines.add("  " + GraphQuery.clean(g.display(e.getKey())) + "  " + edge.sourceFile() + ":"
                        + String.join(",", sites));
                List<String> source = text.computeIfAbsent(edge.sourceFile(), f -> {
                    String body = read(w.root(), f);
                    return body == null ? List.of() : body.lines().toList();
                });
                int quoted = 0;
                for (String site : sites) {
                    if (quoted == QUOTED) break;
                    int n;
                    try {
                        n = Integer.parseInt(site.contains("-") ? site.substring(0, site.indexOf('-')) : site);
                    } catch (NumberFormatException notALine) {
                        continue;
                    }
                    if (n < 1 || n > source.size()) continue;
                    String line = source.get(n - 1).strip();
                    if (line.length() > QUOTE_WIDTH) line = line.substring(0, QUOTE_WIDTH) + "…";
                    lines.add("      L" + n + "| " + line);
                    quoted++;
                }
            }
        }
        if (by.isEmpty()) lines.add("Nothing in the compiled classes calls them.");
        return Budget.lines(lines, budget, "Raise the budget, or narrow with scope, modules or source_set");
    }

    private static boolean test(CodeGraph g, Use u) {
        return g.modules().sourceSet(u.file()) == Modules.SourceSet.TEST;
    }

    private static String prefix(Modules modules, String rel) {
        Modules.Module m = modules.of(rel);
        return m == null ? "" : m.prefix();
    }

    private static Pattern word(String name) {
        return Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])");
    }

    private static @Nullable String read(Path root, String rel) {
        try {
            Path file = root.resolve(rel).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) return null;
            return Files.readString(file);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static @Nullable String readPath(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
