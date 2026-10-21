// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
            "(?<![\\w.$])(?:(@interface)|class|interface|enum|record|object|trait)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern FUNCTION =
            Pattern.compile("(?<![\\w.])(?:fun|def)\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*(?:<[^>]+>\\s+)?[\\w$.]+(?:<[^;{}()]*>)?(?:\\[\\])*\\s+([a-z_$][\\w$]*)\\s*\\(");
    private static final Pattern SUPERS =
            Pattern.compile("\\G[^{;]*?(?:\\bextends\\s+([\\w.<>, ]+?))?(?:\\bimplements\\s+([\\w.<>, ]+?))?\\s*\\{");
    private static final Pattern KOTLIN_SUPERS = Pattern.compile("\\G(?:\\([^)]*\\))?\\s*:\\s*([^{=]+)");
    private static final Pattern COORDINATE =
            Pattern.compile("[\"']([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+)(?::([A-Za-z0-9_.\\-+]+))?[\"']");
    private static final List<String> RATIONALE =
            List.of("NOTE:", "IMPORTANT:", "HACK:", "WHY:", "RATIONALE:", "TODO:", "FIXME:");

    static Fragment extract(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", null);
        SourceMask mask = SourceMask.of(text);
        String code = mask.masked;
        Lines lines = new Lines(text);
        int[] depth = mask.depths();
        int[] close = mask.closers();
        boolean kotlin = relPath.endsWith(".kt") || relPath.endsWith(".kts");
        boolean java = relPath.endsWith(".java");
        Matcher pkg = PACKAGE.matcher(code);
        String packageName = pkg.find() ? pkg.group(1) : "";
        Map<String, String> imported = imports(b, code, lines);
        List<Open> open = new ArrayList<>();
        Matcher type = TYPE.matcher(code);
        while (type.find()) {
            int at = type.start();
            String name = type.group(2);
            int brace = code.indexOf('{', type.end());
            Open parent = enclosing(open, at);
            String id = parent == null ? GraphIds.declared(relPath, name) : GraphIds.member(parent.id, name);
            String qualified = parent == null
                    ? (packageName.isEmpty() ? name : packageName + "." + name)
                    : parent.qualified + "$" + name;
            int line = lines.at(at);
            b.node(id, name, "code", line, Map.of(Linker.QUALIFIED_NAME, qualified));
            b.edge(parent == null ? b.fileId() : parent.id, id, Relations.CONTAINS, Confidence.EXTRACTED, line);
            supertypes(b, code, type.end(), kotlin, id, line, packageName, imported);
            if (brace >= 0 && code.substring(type.end(), brace).indexOf(';') < 0) {
                open.add(new Open(id, qualified, depth[brace] + 1, brace, close[brace]));
            }
        }
        functions(b, code, lines, depth, open);
        if (java) javaMethods(b, code, lines, depth, open);
        if (relPath.endsWith(".gradle") || relPath.endsWith(".gradle.kts")) coordinates(b, text, lines);
        for (SourceMask.Comment c : mask.comments) {
            String body = c.text().strip();
            while (body.startsWith("*")) body = body.substring(1).strip();
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

    /** A type whose body runs from {@code brace} to {@code close} at brace depth {@code depth}. */
    private record Open(String id, String qualified, int depth, int brace, int close) {}

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

    private static void supertypes(
            FragmentBuilder b,
            String code,
            int from,
            boolean kotlin,
            String id,
            int line,
            String packageName,
            Map<String, String> imported) {
        if (kotlin) {
            Matcher k = KOTLIN_SUPERS.matcher(code).region(from, code.length());
            if (!k.lookingAt()) return;
            for (String part : splitTypes(k.group(1))) {
                String relation = part.contains("(") ? Relations.INHERITS : Relations.IMPLEMENTS;
                superMention(b, id, part.replaceAll("\\(.*", "").strip(), line, relation, packageName, imported);
            }
            return;
        }
        Matcher s = SUPERS.matcher(code).region(from, code.length());
        if (!s.lookingAt()) return;
        if (s.group(1) != null) {
            for (String name : splitTypes(s.group(1))) {
                superMention(b, id, name, line, Relations.INHERITS, packageName, imported);
            }
        }
        if (s.group(2) != null) {
            for (String name : splitTypes(s.group(2))) {
                superMention(b, id, name, line, Relations.IMPLEMENTS, packageName, imported);
            }
        }
    }

    private static void superMention(
            FragmentBuilder b,
            String id,
            String name,
            int line,
            String relation,
            String packageName,
            Map<String, String> imported) {
        String simple = name.replaceAll("<.*", "").strip();
        if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(simple.lastIndexOf('.') + 1))) return;
        String qualified = simple.contains(".")
                ? simple
                : imported.getOrDefault(simple, packageName.isEmpty() ? simple : packageName + "." + simple);
        b.mention(id, Mention.Kind.CLASS, qualified, line, relation);
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

    private static void functions(FragmentBuilder b, String code, Lines lines, int[] depth, List<Open> open) {
        Matcher f = FUNCTION.matcher(code);
        while (f.find()) {
            Open owner = enclosing(open, f.start());
            int d = depth[f.start()];
            if (owner == null ? d != 0 : d != owner.depth) continue;
            String name = f.group(1);
            int line = lines.at(f.start());
            if (owner == null) {
                String id = b.declared(name, name + "()", "code", line, Map.of());
                b.edge(b.fileId(), id, Relations.CONTAINS, Confidence.EXTRACTED, line);
            } else {
                String id = b.node(GraphIds.member(owner.id, name), "." + name + "()", "code", line, Map.of());
                b.edge(owner.id, id, Relations.METHOD, Confidence.EXTRACTED, line);
            }
        }
    }

    private static void javaMethods(FragmentBuilder b, String code, Lines lines, int[] depth, List<Open> open) {
        Matcher m = JAVA_METHOD.matcher(code);
        while (m.find()) {
            int at = m.start(1);
            Open owner = enclosing(open, at);
            if (owner == null || depth[at] != owner.depth) continue;
            String name = m.group(1);
            if (name.equals("return") || name.equals("new") || name.equals("throw")) continue;
            int line = lines.at(at);
            String id = b.node(GraphIds.member(owner.id, name), "." + name + "()", "code", line, Map.of());
            b.edge(owner.id, id, Relations.METHOD, Confidence.EXTRACTED, line);
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
