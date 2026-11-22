// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static cc.jumpkick.graph.JavaText.afterQualifier;
import static cc.jumpkick.graph.JavaText.before;
import static cc.jumpkick.graph.JavaText.importLine;
import static cc.jumpkick.graph.JavaText.indent;
import static cc.jumpkick.graph.JavaText.line;
import static cc.jumpkick.graph.JavaText.lineNumber;
import static cc.jumpkick.graph.JavaText.lineStart;
import static cc.jumpkick.graph.JavaText.modifierLine;
import static cc.jumpkick.graph.JavaText.past;
import static cc.jumpkick.graph.MoveMember.canonical;
import static cc.jumpkick.graph.MoveMember.pkg;
import static cc.jumpkick.graph.MoveMember.qualified;

import cc.jumpkick.codegraph.Budget;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Scope;
import cc.jumpkick.graph.MoveMember.FileEdit;
import cc.jumpkick.graph.MoveMember.Op;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code extract-interface} operation: a new Java interface declaring chosen public instance
 * methods of a class (every overload of each; by default every public instance method that
 * overrides nothing), the class made to implement it with {@code @Override} on each, and the
 * variables of the class's type that call only those methods listed, as places that could take the
 * interface instead. The signatures are copied as written, with the imports their types need where
 * the interface lands. The edit goes through the rename's write path and undo record; the engine then
 * compiles the modules it touched ({@code phase=verify}), or undoes it whole, the new file included,
 * when they do not compile ({@code phase=undo}).
 */
final class ExtractInterface {

    private ExtractInterface() {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*");

    private static final List<String> KINDS = List.of("class", "enum", "record");

    /** Modifiers a class method may carry that an interface's abstract method does not. */
    private static final Set<String> DROPPED = Set.of("public", "final", "synchronized", "strictfp", "native");

    /** Methods every interface type has, as {@code name(params)}: a call of one needs nothing extracted. */
    private static final Set<String> OBJECT =
            Set.of("toString()", "hashCode()", "equals(Ljava/lang/Object;)", "getClass()");

    static GraphService.Reply run(GraphService.Workspace w, CodeGraph graph, GraphService.Request r)
            throws IOException {
        Map<String, String> options = r.options();
        String phase = options.get("phase");
        if ("verify".equals(phase)) return GraphService.Reply.of(w, RenameProof.verify(w.root(), w.out(), graph));
        if ("undo".equals(phase)) {
            List<String> restored = RenameStore.undo(w.root(), w.out());
            return GraphService.Reply.of(
                    w,
                    restored.isEmpty()
                            ? ""
                            : "Rolled back, nothing is changed (the new interface removed, " + restored.size()
                                    + (restored.size() == 1 ? " file" : " files") + " restored): after the edit "
                                    + RenameProof.relative(
                                            w.root(), options.getOrDefault("why", "the code does not compile"))
                                    + ".");
        }
        List<String> a = r.args();
        if (a.size() < 2 || a.get(0).isBlank() || a.get(1).isBlank()) {
            throw new IllegalArgumentException("needs the class and the name of the interface");
        }
        String methods = a.size() > 2 ? a.get(2) : "";
        try {
            return GraphService.Reply.of(
                    w, extract(w, graph, a.get(0).strip(), a.get(1).strip(), methods, r.budget()));
        } catch (RenameRefusal refused) {
            return GraphService.Reply.of(w, String.valueOf(refused.getMessage()));
        }
    }

    /**
     * One method: its symbol, name offset, first line (its annotations and modifiers), body's start,
     * {@code name(params)} and modifiers.
     */
    private record Method(String symbol, int name, int first, int body, String key, Set<String> mods) {}

    private static String extract(
            GraphService.Workspace w, CodeGraph g, String type, String iface, String chosen, int budget)
            throws IOException {
        String stale = SafeDelete.edited(w.indexes(), "Not extracted", "extract the interface");
        if (stale != null) throw new RenameRefusal(stale);
        GraphNode cls = oneType(g, type);
        String bin = qualified(cls);
        String simple = canonical(bin).substring(canonical(bin).lastIndexOf('.') + 1);
        if (!KINDS.contains(String.valueOf(Decl.kind(cls))) || !cls.sourceFile().endsWith(".java")) {
            throw new RenameRefusal("Not extracted: " + canonical(bin) + " is a " + Decl.kind(cls)
                    + "; extract-interface takes a Java class, enum or record.");
        }
        Map<String, FileEdit> files = new TreeMap<>();
        FileEdit from = MoveMember.edit(w, g, files, cls.sourceFile());
        if (from == null) {
            throw new RenameRefusal("Not extracted: javac's index does not describe " + cls.sourceFile()
                    + " as it stands; build first.");
        }
        String ifacePkg = iface.contains(".") ? iface.substring(0, iface.lastIndexOf('.')) : pkg(bin);
        String ifaceName = iface.substring(iface.lastIndexOf('.') + 1);
        for (String part : (ifacePkg + "." + ifaceName).split("\\.", -1)) {
            if (!part.isEmpty() && !NAME.matcher(part).matches() || part.isEmpty() && !ifacePkg.isEmpty()) {
                throw new RenameRefusal("Not extracted: '" + iface + "' is not a Java type name.");
            }
        }
        String ifaceBin = ifacePkg.isEmpty() ? ifaceName : ifacePkg + "." + ifaceName;
        Map<String, GraphNode> types = MoveMember.types(g);
        if (types.containsKey(ifaceBin)) {
            throw new RenameRefusal("Not extracted: " + ifaceBin + " already exists ("
                    + types.get(ifaceBin).sourceFile() + "); name another interface.");
        }
        String ifaceRel = sourceRoot(from, bin) + (ifacePkg.isEmpty() ? "" : ifacePkg.replace('.', '/') + "/")
                + ifaceName + ".java";
        if (Files.exists(w.root().resolve(ifaceRel))) {
            throw new RenameRefusal("Not extracted: " + ifaceRel + " already exists; name another interface.");
        }

        List<Method> methods = methods(from, bin, simple, chosen);
        MoveMember.Resolver at = new MoveMember.Resolver(w, g, files, types);
        FileEdit target = new FileEdit(ifaceRel, ifacePkg.isEmpty() ? "" : "package " + ifacePkg + ";\n", List.of());
        String step = step(from, cls);
        List<String> signatures = new ArrayList<>();
        for (Method m : methods) signatures.add(step + signature(at, from, target, m) + ";");

        int brace = header(from, bin, simple);
        String implemented = at.ref(from, ifaceBin);
        int declared = declaration(from, bin).end();
        // implements comes before a sealed class's permits.
        Matcher permits = Pattern.compile("\\bpermits\\b").matcher(from.text.substring(declared, brace));
        int insert = before(from.text, permits.find() ? declared + permits.start() : brace) + 1;
        boolean has = Pattern.compile("\\bimplements\\b")
                .matcher(from.text.substring(declared, insert))
                .find();
        from.ops.add(new Op(insert, insert, (has ? ", " : " implements ") + implemented));
        int overrides = 0;
        for (Method m : methods) {
            if (from.text.substring(lineStart(from.text, m.first()), m.name()).contains("@Override")) continue;
            int lineAt = lineStart(from.text, m.first());
            from.ops.add(new Op(lineAt, lineAt, indent(line(from.text, lineAt)) + "@Override\n"));
            overrides++;
        }
        if (!from.imports.isEmpty()) from.ops.add(MoveImports.op(from));

        List<String> places = places(w, g, files, from.rel, bin, methods);

        Map<Op, Integer> starts = new HashMap<>();
        String after = MoveMember.apply(from.text, from.ops, starts);
        Set<Integer> changed = new TreeSet<>();
        for (Op op : from.ops) {
            int s = starts.getOrDefault(op, 0);
            int first = lineNumber(after, s);
            int last = lineNumber(after, s + Math.max(0, op.text().length() - 1));
            for (int i = first; i <= last; i++) changed.add(i);
        }
        String body = ifaceText(target, Decl.vis(cls), ifacePkg.equals(pkg(bin)), ifaceName, signatures);
        RenamePlan plan = new RenamePlan(simple, ifaceName);
        plan.edit(from.rel, from.text, after, changed);
        plan.after.put(ifaceRel, body);
        plan.lines.put(ifaceRel, new TreeSet<>());
        RenameStore.write(w.root(), w.out(), plan);
        RenameStore.writeProof(w.out(), RenameOp.touched(g, plan), Map.of(), List.of());

        Set<String> names = new LinkedHashSet<>();
        for (Method m : methods) names.add(m.key().substring(0, m.key().indexOf('(')));
        List<String> answer = new ArrayList<>();
        answer.add("Extracted " + ifaceBin + " from " + simple + ": " + methods.size()
                + (methods.size() == 1 ? " method" : " methods") + " (" + String.join(", ", names) + "); " + simple
                + " implements it" + (overrides == 0 ? "" : ", " + overrides + " @Override") + ".");
        answer.add("  " + ifaceRel + "  new");
        answer.add("  " + from.rel + ":" + RenamePlan.numbers(changed));
        if (places.isEmpty()) {
            answer.add("No variable of type " + simple + " calls only these methods; nothing else could take "
                    + ifaceName + " as it stands.");
        } else {
            answer.add("Could take " + ifaceName + " instead of " + simple + ", calling only its methods ("
                    + places.size() + (places.size() == 1 ? " line):" : " lines):"));
            answer.addAll(places);
        }
        return Budget.lines(answer, budget, "Raise the budget");
    }

    /** The one workspace type {@code name} names. */
    private static GraphNode oneType(CodeGraph g, String name) {
        List<GraphNode> found = GraphUses.types(g, Scope.ALL, name.replace('#', '.'));
        if (found.isEmpty()) throw new RenameRefusal("Not extracted: no type '" + name + "' in the workspace.");
        if (found.size() > 1) {
            List<String> shown = new ArrayList<>();
            for (GraphNode t : found.subList(0, Math.min(found.size(), 8))) {
                shown.add("  " + canonical(qualified(t)) + "  " + t.sourceFile());
            }
            throw new RenameRefusal(
                    "'" + name + "' names " + found.size() + " types; qualify it:\n" + String.join("\n", shown));
        }
        return found.getFirst();
    }

    /** The directory {@code f}'s package directories start under: {@code src/main/java/}. */
    private static String sourceRoot(FileEdit f, String bin) {
        String dir = f.rel.contains("/") ? f.rel.substring(0, f.rel.lastIndexOf('/') + 1) : "";
        String pkgDir = pkg(bin).isEmpty() ? "" : pkg(bin).replace('.', '/') + "/";
        if (!dir.endsWith(pkgDir)) {
            throw new RenameRefusal("Not extracted: " + f.rel + " is not under a directory named for its package "
                    + pkg(bin) + "; move it there first.");
        }
        return dir.substring(0, dir.length() - pkgDir.length());
    }

    /** The class's declaration in its own file's index. */
    private static ReferenceIndex.Span declaration(FileEdit f, String bin) {
        for (ReferenceIndex.Span s : f.spans) {
            if (s.kind() == 'd' && s.symbol().equals("T " + bin)) return s;
        }
        throw new RenameRefusal(
                "Not extracted: javac's index does not declare " + canonical(bin) + " in " + f.rel + "; build first.");
    }

    /** The offset of the {@code {} that opens the class's body; a generic class is refused. */
    private static int header(FileEdit f, String bin, String simple) {
        String t = f.text;
        int i = declaration(f, bin).end();
        int k = i;
        while (k < t.length() && Character.isWhitespace(t.charAt(k))) k++;
        if (k < t.length() && t.charAt(k) == '<') {
            throw new RenameRefusal(
                    "Not extracted: " + simple + " declares type parameters; extract its interface" + " by hand.");
        }
        int depth = 0;
        while (i < t.length()) {
            int p = past(t, i);
            if (p != i) {
                i = p;
                continue;
            }
            char c = t.charAt(i);
            if (c == '(' || c == '<') depth++;
            else if (c == ')' || c == '>') depth--;
            else if (c == '{' && depth == 0) return i;
            i++;
        }
        throw new RenameRefusal("Not extracted: no body found for " + simple + " in " + f.rel + ".");
    }

    /** The methods to extract, in the order the class declares them. */
    private static List<Method> methods(FileEdit f, String bin, String simple, String chosen) {
        String t = f.text;
        String[] lines = t.split("\n", -1);
        Map<String, List<Method>> byName = new LinkedHashMap<>();
        for (ReferenceIndex.Span s : f.spans) {
            if (s.kind() != 'd' || !s.symbol().startsWith("M " + bin + "#")) continue;
            String sig = s.symbol().substring(s.symbol().indexOf('#') + 1);
            String name = sig.substring(0, sig.indexOf('('));
            if (name.startsWith("<") || name.startsWith("lambda$") || !t.startsWith(name, s.start())) continue;
            int first = s.line();
            while (first > 1 && modifierLine(lines[first - 2])) first--;
            int body = bodyStart(t, s.start());
            String key = name + sig.substring(sig.indexOf('('), sig.indexOf(')') + 1);
            byName.computeIfAbsent(name, n -> new ArrayList<>())
                    .add(new Method(s.symbol(), s.start(), first, body, key, JavaText.modifiers(t, s.start())));
        }
        List<Method> out = new ArrayList<>();
        if (chosen.isBlank()) {
            byName.values()
                    .forEach(ms -> ms.forEach(m -> {
                        Set<String> mm = m.mods();
                        boolean overrides =
                                t.substring(lineStart(t, m.first()), m.name()).contains("@Override");
                        if (mm.contains("public") && !mm.contains("static") && !overrides) out.add(m);
                    }));
            if (out.isEmpty()) {
                throw new RenameRefusal("Not extracted: " + simple + " has no public instance method that overrides"
                        + " nothing; name the methods to extract.");
            }
        } else {
            for (String raw : chosen.split(",")) {
                String name = raw.strip();
                if (name.isEmpty()) continue;
                name = name.substring(name.lastIndexOf('.') + 1).replace("()", "");
                if (name.contains("#")) name = name.substring(name.indexOf('#') + 1);
                List<Method> ms = byName.get(name);
                if (ms == null)
                    throw new RenameRefusal("Not extracted: " + simple + " declares no method '" + name + "'.");
                for (Method m : ms) {
                    Set<String> mm = m.mods();
                    if (mm.contains("static")) {
                        throw new RenameRefusal("Not extracted: " + simple + "." + name + " is static; an interface"
                                + " declares instance methods.");
                    }
                    if (!mm.contains("public")) {
                        throw new RenameRefusal("Not extracted: " + simple + "." + name + " is not public; an"
                                + " interface's methods are, so make it public first.");
                    }
                    if (!out.contains(m)) out.add(m);
                }
            }
            if (out.isEmpty()) throw new RenameRefusal("Not extracted: no method named to extract.");
            out.sort((x, y) -> Integer.compare(x.name(), y.name()));
        }
        return out;
    }

    /** The offset of the {@code {} (or {@code ;}) after the parameters and throws of the method named at {@code name}. */
    private static int bodyStart(String t, int name) {
        int i = t.indexOf('(', name);
        int depth = 0;
        while (i >= 0 && i < t.length()) {
            int p = past(t, i);
            if (p != i) {
                i = p;
                continue;
            }
            char c = t.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if ((c == '{' || c == ';') && depth == 0) return i;
            i++;
        }
        return t.length();
    }

    /**
     * The method's signature as the interface declares it: type parameters, return type, name,
     * parameters and throws as written, its annotations and class-only modifiers dropped, each type
     * named as the interface's file must name it.
     */
    private static String signature(MoveMember.Resolver at, FileEdit from, FileEdit target, Method m) {
        String t = from.text;
        int i = lineStart(t, m.first());
        while (i < m.name()) {
            while (i < m.name() && Character.isWhitespace(t.charAt(i))) i++;
            if (t.charAt(i) == '@') {
                i++;
                while (i < m.name() && (Character.isJavaIdentifierPart(t.charAt(i)) || t.charAt(i) == '.')) i++;
                int k = i;
                while (k < m.name() && Character.isWhitespace(t.charAt(k))) k++;
                if (k < m.name() && t.charAt(k) == '(') i = closing(t, k) + 1;
                continue;
            }
            Matcher w = NAME.matcher(t).region(i, m.name());
            if (w.lookingAt() && DROPPED.contains(w.group())) {
                i = w.end();
                continue;
            }
            break;
        }
        int start = i;
        List<Op> ops = new ArrayList<>();
        for (ReferenceIndex.Span s : from.spans) {
            if (s.start() < start
                    || s.start() >= m.body()
                    || s.kind() == 'd'
                    || !s.symbol().startsWith("T ")) continue;
            if (afterQualifier(t, s.start())) continue;
            String bin = s.symbol().substring(2);
            String ref = at.ref(target, bin);
            if (!ref.equals(t.substring(s.start(), s.end()))) ops.add(new Op(s.start() - start, s.end() - start, ref));
        }
        String sig = MoveMember.apply(t.substring(start, m.body()), ops, null);
        return sig.strip().replaceAll("\\s+", " ");
    }

    /** The offset of the {@code )} that closes the {@code (} at {@code open}. */
    private static int closing(String t, int open) {
        int depth = 0;
        int i = open;
        while (i < t.length()) {
            int p = past(t, i);
            if (p != i) {
                i = p;
                continue;
            }
            char c = t.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
            i++;
        }
        return t.length() - 1;
    }

    /** The indent one member step past the class's own: what its members are indented by beyond its brace. */
    private static String step(FileEdit f, GraphNode cls) {
        String member = "";
        for (ReferenceIndex.Span s : f.spans) {
            if (s.kind() == 'd' && !s.symbol().startsWith("T ")) {
                member = indent(line(f.text, s.start()));
                break;
            }
        }
        String brace = indent(line(f.text, lineStart(f.text, Decl.end(cls))));
        String step = member.startsWith(brace) ? member.substring(brace.length()) : "";
        return step.isEmpty() ? "    " : step;
    }

    private static String ifaceText(
            FileEdit target, @Nullable String vis, boolean samePackage, String name, List<String> signatures) {
        StringBuilder s = new StringBuilder(target.text);
        if (!target.text.isEmpty()) s.append('\n');
        if (!target.imports.isEmpty()) {
            for (String i : target.imports) s.append("import ").append(i).append(";\n");
            s.append('\n');
        }
        // A class seen only in its package gets an interface seen only there; any other is public.
        s.append(samePackage && !"public".equals(vis) ? "" : "public ")
                .append("interface ")
                .append(name)
                .append(" {\n\n");
        s.append(String.join("\n\n", signatures)).append("\n}\n");
        return s.toString();
    }

    /**
     * The variables of the class's type, outside its own file, through which nothing but the
     * extracted methods (and {@link Object}'s) is called and which are not passed on: each could be
     * declared as the interface. A variable that is only assigned to or from one of the same name
     * counts with it.
     */
    private static List<String> places(
            GraphService.Workspace w,
            CodeGraph g,
            Map<String, FileEdit> files,
            String own,
            String bin,
            List<Method> methods) {
        Set<String> keys = new TreeSet<>(OBJECT);
        for (Method m : methods) keys.add(m.key());
        String simple = canonical(bin).substring(canonical(bin).lastIndexOf('.') + 1);
        Set<String> out = new LinkedHashSet<>();
        for (String rel : RenameProof.javaFiles(g)) {
            if (rel.equals(own)) continue;
            String text = RenameText.read(w.root(), rel);
            if (text == null || !text.contains(simple)) continue;
            FileEdit f = MoveMember.edit(w, g, files, rel);
            if (f == null) continue;
            Map<String, List<int[]>> vars = new LinkedHashMap<>();
            for (ReferenceIndex.Span s : f.spans) {
                if (s.kind() != 'r' || !s.symbol().equals("T " + bin) || importLine(f.text, s.start())) continue;
                int[] v = variable(f.text, s.end());
                if (v != null)
                    vars.computeIfAbsent(f.text.substring(v[0], v[1]), k -> new ArrayList<>())
                            .add(new int[] {v[0], s.line()});
            }
            if (vars.isEmpty()) continue;
            String code = masked(f.text);
            for (Map.Entry<String, List<int[]>> e : vars.entrySet()) {
                Set<Integer> declared = new TreeSet<>();
                for (int[] d : e.getValue()) declared.add(d[0]);
                if (!onlyCalls(f, code, e.getKey(), declared, keys)) continue;
                for (int[] d : e.getValue())
                    out.add("  " + rel + ":" + d[1] + "  " + RenamePlan.clip(line(f.text, d[0])));
            }
        }
        return List.copyOf(out);
    }

    /** {@code [start, end)} of the variable a type name ending at {@code end} declares; {@code null} for any other use. */
    private static int @Nullable [] variable(String t, int end) {
        int i = end;
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
        Matcher m = NAME.matcher(t).region(i, t.length());
        if (!m.lookingAt()) return null;
        int k = m.end();
        while (k < t.length() && Character.isWhitespace(t.charAt(k))) k++;
        // A name followed by ( is a method the type is the return type of.
        if (k < t.length() && t.charAt(k) == '(') return null;
        return new int[] {m.start(), m.end()};
    }

    /**
     * Whether every use of {@code name} in {@code f}, its declarations aside, calls one of {@code keys}
     * or assigns it to or from a variable of the same name.
     */
    private static boolean onlyCalls(FileEdit f, String code, String name, Set<Integer> declared, Set<String> keys) {
        Matcher m = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])")
                .matcher(code);
        while (m.find()) {
            if (declared.contains(m.start())) continue;
            int k = m.end();
            while (k < code.length() && Character.isWhitespace(code.charAt(k))) k++;
            if (k < code.length() && code.charAt(k) == '=' && (k + 1 >= code.length() || code.charAt(k + 1) != '=')) {
                continue;
            }
            int b = before(code, m.start());
            if (b >= 0 && code.charAt(b) == '=' && (b == 0 || "=!<>".indexOf(code.charAt(b - 1)) < 0)) {
                int lhsEnd = before(code, b) + 1;
                int lhsStart = lhsEnd;
                while (lhsStart > 0 && Character.isJavaIdentifierPart(code.charAt(lhsStart - 1))) lhsStart--;
                if (code.substring(lhsStart, lhsEnd).equals(name)) continue;
                return false;
            }
            if (k >= code.length() || code.charAt(k) != '.') return false;
            int c = k + 1;
            while (c < code.length() && Character.isWhitespace(code.charAt(c))) c++;
            String called = null;
            for (ReferenceIndex.Span s : f.spans) {
                if (s.start() == c && s.symbol().startsWith("M ")) {
                    String sig = s.symbol().substring(s.symbol().indexOf('#') + 1);
                    called = sig.substring(0, sig.indexOf(')') + 1);
                    break;
                }
            }
            if (called == null || !keys.contains(called)) return false;
        }
        return true;
    }

    /** {@code text} with its comments and literals blanked, newlines kept, so offsets still match. */
    private static String masked(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            int p = past(text, i);
            if (p == i) {
                i++;
                continue;
            }
            for (int k = i; k < p; k++) {
                if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
            }
            i = p;
        }
        return out.toString();
    }
}
