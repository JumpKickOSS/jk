// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Java, Kotlin and Groovy source (Gradle scripts included) read at the declaration level: package,
 * imports, every type with its nesting, Kotlin and Groovy functions, Java methods declared in a type
 * body, supertypes, dependency coordinates and rationale comments. Compiled classes add the
 * call and reference edges; this pass gives the declaration lines and covers a tree that does not
 * compile yet.
 */
final class JvmSourceScanner {

    private JvmSourceScanner() {}

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?(?:\\s+as\\s+\\w+)?\\s*;?\\s*$");
    private static final Pattern TYPE = Pattern.compile(
            "(?<![\\w.$])(@interface|class|interface|enum|record|object|trait)\\s+(?!(?:class|interface|object)\\b)([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FUNCTION =
            Pattern.compile("(?<![\\w.])(?:fun|def)\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?([A-Za-z_]\\w*)\\s*\\(");
    /**
     * An annotation as a declaration spells it: {@code @Nullable}, {@code @a.b.Nullable}, {@code @Size(max = 3)}.
     * Java lets one sit between the modifiers and before the type ({@code static @Nullable String name()}),
     * so every declaration pattern takes them wherever a modifier may stand.
     */
    private static final String ANNOTATION = "@[\\w$.]+(?:\\([^)]*\\))?";

    private static final Pattern JAVA_METHOD = Pattern.compile("(?m)^[ \\t]*(?:(?:" + ANNOTATION
            + "|public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
            + "(?:<[^>]+>\\s+)?(?:" + ANNOTATION + "\\s+)*"
            + "[\\w$.]+(?:<[^;{}()]*>)?(?:\\[\\])*\\s+([a-z_$][\\w$]*)\\s*\\(");
    private static final Pattern SUPERS =
            Pattern.compile("\\G[^{;]*?(?:\\bextends\\s+([\\w.<>, ]+?))?(?:\\bimplements\\s+([\\w.<>, ]+?))?\\s*\\{");
    private static final Pattern KOTLIN_SUPERS = Pattern.compile("\\G(?:\\([^)]*\\))?\\s*:\\s*([^{=]+)");
    private static final Pattern COORDINATE =
            Pattern.compile("[\"']([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)(?::([A-Za-z0-9_.\\-+]+))?[\"']");
    private static final List<String> RATIONALE =
            List.of("NOTE:", "IMPORTANT:", "HACK:", "WHY:", "RATIONALE:", "TODO:", "FIXME:");

    static Fragment extract(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", null);
        SourceMask mask = SourceMask.of(text, false);
        String code = mask.masked;
        Lines lines = new Lines(text);
        int[] depth = mask.depths();
        int[] close = mask.closers();
        boolean kotlin = relPath.endsWith(".kt") || relPath.endsWith(".kts");
        boolean java = relPath.endsWith(".java");
        String memberVis = java ? "package" : "public";
        Matcher pkg = PACKAGE.matcher(code);
        String packageName = pkg.find() ? pkg.group(1) : "";
        Map<String, String> imported = imports(b, code, lines);
        Map<String, String> local = declaredTypes(code, packageName, depth, close);
        List<Open> open = new ArrayList<>();
        Matcher type = TYPE.matcher(code);
        while (type.find()) {
            int at = type.start();
            String name = type.group(2);
            int brace = code.indexOf('{', type.end());
            Open parent = enclosing(open, at);
            String id = GraphIds.declared(relPath, name);
            String qualified = parent == null
                    ? (packageName.isEmpty() ? name : packageName + "." + name)
                    : parent.qualified + "$" + name;
            int line = lines.at(at);
            b.node(id, name, "code", line, Map.of(Linker.QUALIFIED_NAME, qualified));
            DeclScan.Preamble pre = DeclScan.preamble(code, at);
            String kind = typeKind(type.group(1), pre.modifiers());
            boolean body = brace >= 0 && code.substring(type.end(), brace).indexOf(';') < 0;
            String fallback = parent != null && parent.implicitPublic ? "public" : memberVis;
            Map<String, Object> facts = new LinkedHashMap<>(DeclScan.facts(
                    kind,
                    Decl.visibility(pre.modifiers(), fallback),
                    body ? lines.at(close[brace]) : line,
                    pre.annotations()));
            b.edge(parent == null ? b.fileId() : parent.id, id, Relations.CONTAINS, Confidence.EXTRACTED, line);
            Supers supers = new Supers(new ArrayList<>(), new ArrayList<>());
            supertypes(
                    b,
                    code,
                    type.end(),
                    kotlin,
                    kind.equals("interface"),
                    id,
                    line,
                    packageName,
                    imported,
                    local,
                    supers);
            if (!supers.ext().isEmpty()) facts.put(Decl.EXT, List.copyOf(supers.ext()));
            if (!supers.impl().isEmpty()) facts.put(Decl.IMPL, List.copyOf(supers.impl()));
            String doc = DeclScan.docSummary(text, code, pre.start(), at);
            if (doc != null) facts.put(Decl.DOC, doc);
            b.declare(id, line, facts);
            if (java && kind.equals("record")) components(b, code, lines, type.end(), id);
            if (body) {
                boolean implicitPublic = java && (kind.equals("interface") || kind.equals("annotation"));
                open.add(new Open(id, qualified, depth[brace] + 1, brace, close[brace], implicitPublic));
            }
        }
        functions(b, text, code, lines, depth, close, open);
        if (java) {
            javaMethods(b, text, code, lines, depth, close, open);
            constructors(b, text, code, lines, depth, close, open);
        }
        if (java || kotlin) constants(b, text, code, lines, depth, open, java ? JAVA_CONSTANT : KOTLIN_CONSTANT);
        constantReferences(b, code, lines, open, packageName, imported);
        if (relPath.endsWith(".gradle") || relPath.endsWith(".gradle.kts")) coordinates(b, text, lines);
        for (SourceMask.Comment c : mask.comments) {
            String body = SourceMask.body(c);
            for (String prefix : RATIONALE) {
                if (body.startsWith(prefix)) {
                    Open target = enclosing(open, c.offset());
                    b.rationale(body, lines.at(c.offset()), target == null ? b.fileId() : target.id);
                    break;
                }
            }
        }
        return b.build();
    }

    /**
     * A record's components as its accessors: one public method node each, at the component's line,
     * on the ids the compiled accessors use. {@code from} is just past the record's name.
     */
    private static void components(FragmentBuilder b, String code, Lines lines, int from, String owner) {
        int paren = code.indexOf('(', from);
        if (paren < 0 || code.substring(from, paren).contains("{")) return;
        int depth = 0;
        int start = paren + 1;
        for (int i = paren + 1; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '(' || ch == '<') depth++;
            if ((ch == ')' || ch == '>') && depth > 0) {
                depth--;
                continue;
            }
            if ((ch == ',' && depth == 0) || ch == ')') {
                component(b, code, lines, start, i, owner);
                if (ch == ')') return;
                start = i + 1;
            }
        }
    }

    private static final Pattern COMPONENT_NAME = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$");

    private static void component(FragmentBuilder b, String code, Lines lines, int start, int end, String owner) {
        Matcher name = COMPONENT_NAME.matcher(code.substring(start, end));
        if (!name.find()) return;
        int line = lines.at(start + name.start(1));
        String id = b.node(GraphIds.member(owner, name.group(1)), "." + name.group(1) + "()", "code", line, Map.of());
        b.declare(id, line, DeclScan.facts("method", "public", line, List.of()));
        b.edge(owner, id, Relations.METHOD, Confidence.EXTRACTED, line);
    }

    /** Every type {@code code} declares: simple name to qualified name, a nested one joined by {@code $}. */
    private static Map<String, String> declaredTypes(String code, String packageName, int[] depth, int[] close) {
        Map<String, String> out = new HashMap<>();
        List<Open> open = new ArrayList<>();
        Matcher type = TYPE.matcher(code);
        while (type.find()) {
            String name = type.group(2);
            Open parent = enclosing(open, type.start());
            String qualified = parent == null
                    ? (packageName.isEmpty() ? name : packageName + "." + name)
                    : parent.qualified + "$" + name;
            out.putIfAbsent(name, qualified);
            int brace = code.indexOf('{', type.end());
            if (brace >= 0 && code.substring(type.end(), brace).indexOf(';') < 0) {
                open.add(new Open("", qualified, depth[brace] + 1, brace, close[brace], false));
            }
        }
        return out;
    }

    /** A type whose body runs from {@code brace} to {@code close} at brace depth {@code depth}. */
    private record Open(String id, String qualified, int depth, int brace, int close, boolean implicitPublic) {}

    /** The declaration kind a type keyword and its modifiers spell: {@code enum class} is an enum. */
    static String typeKind(String keyword, List<String> modifiers) {
        if (keyword.equals("@interface")) return "annotation";
        if (keyword.equals("class")) {
            if (modifiers.contains("enum")) return "enum";
            if (modifiers.contains("annotation")) return "annotation";
        }
        return keyword;
    }

    /** Facts for a member whose parameters open at {@code paren}. */
    private static Map<String, Object> member(
            String text,
            String code,
            int[] close,
            Lines lines,
            int at,
            int paren,
            String kind,
            String fallback,
            boolean expressions) {
        DeclScan.Preamble pre = DeclScan.preamble(code, at);
        int end = DeclScan.memberEnd(code, close, paren, expressions);
        Map<String, Object> facts = new LinkedHashMap<>(DeclScan.facts(
                kind,
                Decl.visibility(pre.modifiers(), fallback),
                end < 0 ? lines.at(at) : lines.at(end),
                pre.annotations()));
        String doc = DeclScan.docSummary(text, code, pre.start(), at);
        if (doc != null) facts.put(Decl.DOC, doc);
        return facts;
    }

    private static Map<String, String> imports(FragmentBuilder b, String code, Lines lines) {
        Map<String, String> bySimpleName = new HashMap<>();
        Matcher m = IMPORT.matcher(code);
        while (m.find()) {
            String target = m.group(2);
            if (m.group(3) != null) continue;
            if (m.group(1) != null) target = target.substring(0, Math.max(0, target.lastIndexOf('.')));
            if (target.isEmpty()) continue;
            bySimpleName.put(target.substring(target.lastIndexOf('.') + 1), target);
            b.mention(b.fileId(), Mention.Kind.CLASS, target, lines.at(m.start()), Relations.IMPORTS);
        }
        return bySimpleName;
    }

    /** A type's superclass and interfaces as written, qualified through its imports where they can be. */
    private record Supers(List<String> ext, List<String> impl) {}

    private static void supertypes(
            FragmentBuilder b,
            String code,
            int from,
            boolean kotlin,
            boolean iface,
            String id,
            int line,
            String packageName,
            Map<String, String> imported,
            Map<String, String> local,
            Supers supers) {
        if (kotlin) {
            Matcher k = KOTLIN_SUPERS.matcher(code).region(from, code.length());
            if (!k.lookingAt()) return;
            for (String part : splitTypes(k.group(1))) {
                boolean superclass = part.contains("(");
                String q = superMention(
                        b,
                        id,
                        part.replaceAll("\\(.*", "").strip(),
                        line,
                        superclass ? Relations.INHERITS : Relations.IMPLEMENTS,
                        packageName,
                        imported,
                        local);
                if (q != null) (superclass ? supers.ext() : supers.impl()).add(q);
            }
            return;
        }
        Matcher s = SUPERS.matcher(code).region(from, code.length());
        if (!s.lookingAt()) return;
        if (s.group(1) != null) {
            for (String name : splitTypes(s.group(1))) {
                String q = superMention(b, id, name, line, Relations.INHERITS, packageName, imported, local);
                if (q != null) (iface ? supers.impl() : supers.ext()).add(q);
            }
        }
        if (s.group(2) != null) {
            for (String name : splitTypes(s.group(2))) {
                String q = superMention(b, id, name, line, Relations.IMPLEMENTS, packageName, imported, local);
                if (q != null) supers.impl().add(q);
            }
        }
    }

    /** Mentions a supertype; returns its qualified name, or {@code null} for what is not a type name. */
    private static @Nullable String superMention(
            FragmentBuilder b,
            String id,
            String name,
            int line,
            String relation,
            String packageName,
            Map<String, String> imported,
            Map<String, String> local) {
        String simple = name.replaceAll("<.*", "").strip();
        if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(simple.lastIndexOf('.') + 1))) return null;
        // A type this file declares shadows an import of the same simple name, as Java scoping does.
        String qualified = simple.contains(".")
                ? simple
                : local.getOrDefault(
                        simple,
                        imported.getOrDefault(simple, packageName.isEmpty() ? simple : packageName + "." + simple));
        b.mention(id, Mention.Kind.CLASS, qualified, line, relation);
        return qualified;
    }

    /** Top-level type names in a comma list, generic arguments dropped. */
    private static List<String> splitTypes(String list) {
        StringBuilder flat = new StringBuilder();
        int angle = 0;
        int paren = 0;
        for (char c : list.toCharArray()) {
            if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            if (c == ',' && angle == 0 && paren == 0) flat.append('\u0000');
            else if (angle == 0) flat.append(c);
        }
        return Arrays.stream(flat.toString().split("\u0000"))
                .map(String::strip)
                .filter(p -> !p.isEmpty())
                .toList();
    }

    private static void functions(
            FragmentBuilder b, String text, String code, Lines lines, int[] depth, int[] close, List<Open> open) {
        Matcher f = FUNCTION.matcher(code);
        while (f.find()) {
            Open owner = enclosing(open, f.start());
            int d = depth[f.start()];
            if (owner == null ? d != 0 : d != owner.depth) continue;
            String name = f.group(1);
            int line = lines.at(f.start());
            Map<String, Object> facts = member(
                    text,
                    code,
                    close,
                    lines,
                    f.start(),
                    f.end() - 1,
                    owner == null ? "function" : "method",
                    "public",
                    true);
            if (owner == null) {
                String id = b.declared(name, name + "()", "code", line, Map.of());
                b.declare(id, line, facts);
                b.edge(b.fileId(), id, Relations.CONTAINS, Confidence.EXTRACTED, line);
            } else {
                String id = b.node(GraphIds.member(owner.id, name), "." + name + "()", "code", line, Map.of());
                b.declare(id, line, facts);
                b.edge(owner.id, id, Relations.METHOD, Confidence.EXTRACTED, line);
            }
        }
    }

    private static void javaMethods(
            FragmentBuilder b, String text, String code, Lines lines, int[] depth, int[] close, List<Open> open) {
        Matcher m = JAVA_METHOD.matcher(code);
        while (m.find()) {
            int at = m.start(1);
            Open owner = enclosing(open, at);
            if (owner == null || depth[at] != owner.depth) continue;
            String name = m.group(1);
            if (name.equals("return") || name.equals("new") || name.equals("throw")) continue;
            int line = lines.at(at);
            String id = b.node(GraphIds.member(owner.id, name), "." + name + "()", "code", line, Map.of());
            b.declare(id, line, member(text, code, close, lines, at, m.end() - 1, "method", fallback(owner), false));
            b.edge(owner.id, id, Relations.METHOD, Confidence.EXTRACTED, line);
        }
    }

    private static String fallback(Open owner) {
        return owner.implicitPublic ? "public" : "package";
    }

    private static final Pattern CONSTRUCTOR = Pattern.compile("(?m)^[ \\t]*(?:(?:" + ANNOTATION
            + "|public|protected|private)\\s+)*(?:<[^>]+>\\s+)?([A-Z][\\w$]*)\\s*\\(");

    /** A Java constructor is a member named after its type: {@code .Outer()}. */
    private static void constructors(
            FragmentBuilder b, String text, String code, Lines lines, int[] depth, int[] close, List<Open> open) {
        Matcher m = CONSTRUCTOR.matcher(code);
        while (m.find()) {
            int at = m.start(1);
            Open owner = enclosing(open, at);
            String name = m.group(1);
            if (owner == null || depth[at] != owner.depth || !owner.qualified.endsWith(name)) continue;
            int line = lines.at(at);
            String id = b.node(GraphIds.member(owner.id, name), "." + name + "()", "code", line, Map.of());
            b.declare(
                    id, line, member(text, code, close, lines, at, m.end() - 1, "constructor", fallback(owner), false));
            b.edge(owner.id, id, Relations.METHOD, Confidence.EXTRACTED, line);
        }
    }

    private static final Pattern JAVA_CONSTANT = Pattern.compile("(?m)^[ \\t]*(?:" + ANNOTATION + "\\s+)*"
            + "(?:(?:public|protected|private)\\s+)?(?:static\\s+final|final\\s+static)\\s+(?:" + ANNOTATION
            + "\\s+)*[\\w$.<>\\[\\]?, ]+?\\s+([A-Z][A-Z0-9_]*)\\s*[=;]");
    private static final Pattern KOTLIN_CONSTANT = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|internal|protected|private)\\s+)?const\\s+val\\s+([A-Z][A-Z0-9_]*)\\b");

    /**
     * A named constant ({@code static final MAX_NIGHTS}, Kotlin {@code const val}) is a member of its
     * type: a rule's value is often where a rule is decided. A Kotlin top-level or companion
     * constant belongs to the file or the enclosing class.
     */
    private static void constants(
            FragmentBuilder b, String text, String code, Lines lines, int[] depth, List<Open> open, Pattern pattern) {
        Matcher m = pattern.matcher(code);
        while (m.find()) {
            int at = m.start(1);
            Open owner = enclosing(open, at);
            int companion = pattern == KOTLIN_CONSTANT ? 1 : 0;
            if (owner == null ? depth[at] != 0 : depth[at] < owner.depth || depth[at] > owner.depth + companion) {
                continue;
            }
            String name = m.group(1);
            int line = lines.at(at);
            DeclScan.Preamble pre = DeclScan.preamble(code, at);
            int semi = code.indexOf(';', m.end() - 1);
            int newline = code.indexOf('\n', m.end());
            int end = pattern == JAVA_CONSTANT && semi >= 0 ? semi : (newline < 0 ? code.length() - 1 : newline);
            Map<String, Object> facts = new LinkedHashMap<>(DeclScan.facts(
                    "constant",
                    Decl.visibility(pre.modifiers(), owner == null ? "public" : fallback(owner)),
                    lines.at(end),
                    pre.annotations()));
            String doc = DeclScan.docSummary(text, code, pre.start(), at);
            if (doc != null) facts.put(Decl.DOC, doc);
            String id = owner == null
                    ? b.declared(name, name, "code", line, Map.of())
                    : b.node(GraphIds.member(owner.id, name), "." + name, "code", line, Map.of());
            b.declare(id, line, facts);
            b.edge(owner == null ? b.fileId() : owner.id, id, Relations.CONTAINS, Confidence.EXTRACTED, line);
        }
    }

    /** {@code Type.NAME} with an upper-case name: a constant read through its type, not a call. */
    private static final Pattern CONSTANT_REF =
            Pattern.compile("(?<![\\w$.])((?:[a-z_][\\w$]*\\.)*[A-Z][\\w$]*)\\.([A-Z][A-Z0-9_]*)\\b(?!\\s*\\()");

    /**
     * A constant used through its type ({@code Greet.PREFIX}) references it from the enclosing type.
     * The compiled facts cannot say so: javac copies a compile-time constant into the class that
     * uses it and leaves no trace of where it came from, so the source is the only witness.
     */
    private static void constantReferences(
            FragmentBuilder b,
            String code,
            Lines lines,
            List<Open> open,
            String packageName,
            Map<String, String> imported) {
        Matcher m = CONSTANT_REF.matcher(code);
        while (m.find()) {
            String type = m.group(1);
            String simple = type.substring(type.lastIndexOf('.') + 1);
            String qualified = type.contains(".")
                    ? type
                    : imported.getOrDefault(simple, packageName.isEmpty() ? simple : packageName + "." + simple);
            Open owner = enclosing(open, m.start());
            b.mention(
                    owner == null ? b.fileId() : owner.id,
                    Mention.Kind.MEMBER,
                    qualified + "#" + m.group(2),
                    lines.at(m.start()),
                    Relations.REFERENCES,
                    "constant");
        }
    }

    private static void coordinates(FragmentBuilder b, String text, Lines lines) {
        Matcher m = COORDINATE.matcher(text);
        while (m.find()) {
            String group = m.group(1);
            if (!group.contains(".")
                    || m.group(3) == null
                            && !text.substring(Math.max(0, m.start() - 40), m.start())
                                    .contains("(")) {
                continue;
            }
            Dependencies.declare(b, b.fileId(), group, m.group(2), lines.at(m.start()));
        }
    }

    /** The innermost type whose body holds {@code offset}; types are in source order. */
    private static @Nullable Open enclosing(List<Open> open, int offset) {
        Open best = null;
        for (Open o : open) {
            if (o.brace >= offset) break;
            if (o.close > offset) best = o;
        }
        return best;
    }
}
