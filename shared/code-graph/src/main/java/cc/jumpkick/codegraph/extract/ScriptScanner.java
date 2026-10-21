// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.GraphIds;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript, TypeScript and Vue single-file components read at the module level: imports and
 * re-exports (relative ones to the file, packages to an npm node), top-level declarations, class
 * methods, Angular {@code @Component} templates and styles, and JSX/template component use. Used
 * where no front-end toolchain is available to parse the module properly.
 */
final class ScriptScanner {

    private ScriptScanner() {}

    private static final Pattern FROM = Pattern.compile("\\b(?:import|export)\\b[^;]*?\\bfrom\\b");
    private static final Pattern BARE_IMPORT = Pattern.compile("\\bimport\\b");
    private static final Pattern CALL_IMPORT = Pattern.compile("\\b(?:require|import)\\s*\\(");
    private static final Pattern DECLARATION =
            Pattern.compile("(?<![\\w$.])(?:export\\s+(?:default\\s+)?)?(?:declare\\s+)?(?:abstract\\s+)?(?:async\\s+)?"
                    + "(class|interface|enum|type|function\\*?|const|let|var)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|private|protected|static|async|readonly|abstract|override|get|set)\\s+)*"
                    + "#?([A-Za-z_$][\\w$]*)\\s*(?:<[^>()]*>)?\\s*\\([^)]*\\)\\s*(?::[^{;]+)?\\{");
    private static final Pattern COMPONENT_FILE =
            Pattern.compile("\\b(templateUrl|styleUrl|styleUrls)\\s*:\\s*(\\[[^\\]]*]|[\"'][^\"']+[\"'])");
    private static final Pattern SELECTOR = Pattern.compile("\\bselector\\s*:\\s*[\"']([^\"']+)[\"']");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern JSX = Pattern.compile("<([A-Z][\\w$]*)[\\s/>]");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script>");
    private static final Set<String> NOT_METHODS =
            Set.of("if", "for", "while", "switch", "catch", "function", "return", "constructor", "with", "do");

    static Fragment extract(String relPath, String text) {
        FragmentBuilder b = new FragmentBuilder(relPath, "code", null);
        String source = relPath.endsWith(".vue") ? scriptOnly(text) : text;
        SourceMask mask = SourceMask.of(source, true);
        String code = mask.masked;
        Lines lines = new Lines(source);
        int[] depth = mask.depths();
        int[] close = mask.closers();
        imports(b, relPath, source, code, lines);
        List<int[]> classes = new ArrayList<>();
        List<String> classIds = new ArrayList<>();
        Matcher d = DECLARATION.matcher(code);
        while (d.find()) {
            if (depth[d.start()] != 0) continue;
            String kind = d.group(1);
            String name = d.group(2);
            int line = lines.at(d.start());
            boolean function = kind.startsWith("function") || isArrow(code, d.end());
            boolean variable = kind.equals("const") || kind.equals("let") || kind.equals("var");
            if (variable && !function && !code.startsWith("export", d.start())) continue;
            String label = function ? name + "()" : name;
            String id = b.declared(name, label, "code", line, Map.of());
            b.edge(b.fileId(), id, Relations.CONTAINS, Confidence.EXTRACTED, line);
            if (kind.equals("class")) {
                int brace = code.indexOf('{', d.end());
                if (brace >= 0) {
                    classes.add(new int[] {brace, close[brace]});
                    classIds.add(id);
                    angular(b, source, code, lines, d.start(), id);
                }
            }
        }
        methods(b, code, lines, depth, classes, classIds);
        components(b, relPath, text, new Lines(text));
        for (SourceMask.Comment c : mask.comments) {
            String body = SourceMask.body(c);
            for (String prefix : List.of("NOTE:", "IMPORTANT:", "HACK:", "WHY:", "RATIONALE:")) {
                if (body.startsWith(prefix)) b.rationale(body, lines.at(c.offset()), b.fileId());
            }
        }
        return b.build();
    }

    private static void imports(FragmentBuilder b, String relPath, String source, String code, Lines lines) {
        for (Pattern p : List.of(FROM, BARE_IMPORT, CALL_IMPORT)) {
            Matcher m = p.matcher(code);
            while (m.find()) {
                String spec = SourceMask.quotedAt(source, m.end());
                if (spec == null || spec.isEmpty() || spec.contains("${")) continue;
                int line = lines.at(m.start());
                if (spec.startsWith(".")) {
                    b.mention(b.fileId(), Mention.Kind.MODULE, resolve(relPath, spec), line);
                } else if (!spec.startsWith("/")) {
                    String pkg = packageOf(spec);
                    String id = b.external(GraphIds.make("npm", pkg), pkg);
                    b.edge(b.fileId(), id, Relations.IMPORTS, Confidence.EXTRACTED, line);
                }
            }
        }
    }

    private static void methods(
            FragmentBuilder b, String code, Lines lines, int[] depth, List<int[]> classes, List<String> classIds) {
        Matcher m = METHOD.matcher(code);
        while (m.find()) {
            int at = m.start(1);
            String name = m.group(1);
            if (NOT_METHODS.contains(name)) continue;
            for (int i = 0; i < classes.size(); i++) {
                int[] body = classes.get(i);
                if (at > body[0] && at < body[1] && depth[at] == depth[body[0]] + 1) {
                    String owner = classIds.get(i);
                    int line = lines.at(at);
                    String id = b.node(GraphIds.member(owner, name), "." + name + "()", "code", line, Map.of());
                    b.edge(owner, id, Relations.METHOD, Confidence.EXTRACTED, line);
                    break;
                }
            }
        }
    }

    /** An Angular {@code @Component} just before the class: its template and styles, and its selector. */
    private static void angular(
            FragmentBuilder b, String source, String code, Lines lines, int classAt, String classId) {
        int deco = code.lastIndexOf("@Component", classAt);
        if (deco < 0 || code.substring(deco, classAt).contains("class ")) return;
        String args = source.substring(deco, classAt);
        Matcher f = COMPONENT_FILE.matcher(args);
        while (f.find()) {
            Matcher q = QUOTED.matcher(f.group(2));
            while (q.find())
                b.mention(classId, Mention.Kind.FILE, q.group(1), lines.at(deco + f.start()), Relations.USES);
        }
        Matcher s = SELECTOR.matcher(args);
        if (s.find()) b.annotateNode(classId, Map.of("selector", s.group(1)));
    }

    /** Capitalized JSX or template tags: a component used here, linked by name. */
    private static void components(FragmentBuilder b, String relPath, String text, Lines lines) {
        if (!(relPath.endsWith("x") || relPath.endsWith(".vue"))) return;
        Set<String> seen = new HashSet<>();
        Matcher m = JSX.matcher(text);
        while (m.find()) {
            if (seen.add(m.group(1)))
                b.mention(b.fileId(), Mention.Kind.SYMBOL, m.group(1), lines.at(m.start()), Relations.USES);
        }
    }

    private static boolean isArrow(String code, int from) {
        int eq = code.indexOf('=', from);
        if (eq < 0 || eq - from > 120 || code.substring(from, eq).contains(";")) return false;
        String rest = code.substring(eq + 1, Math.min(code.length(), eq + 160)).stripLeading();
        return rest.startsWith("function")
                || rest.startsWith("async")
                || rest.matches("(?s)^(\\([^)]*\\)|[\\w$]+)\\s*(:[^=]+)?=>.*");
    }

    /** The Vue file's {@code <script>} blocks with everything else blanked, offsets kept. */
    private static String scriptOnly(String text) {
        char[] out = new char[text.length()];
        for (int i = 0; i < out.length; i++) out[i] = text.charAt(i) == '\n' ? '\n' : ' ';
        Matcher m = SCRIPT_BLOCK.matcher(text);
        while (m.find()) text.getChars(m.start(1), m.end(1), out, m.start(1));
        return new String(out);
    }

    /** {@code spec} resolved against the directory of {@code relPath}. */
    static String resolve(String relPath, String spec) {
        int slash = relPath.lastIndexOf('/');
        String base = slash < 0 ? "" : relPath.substring(0, slash);
        List<String> parts = new ArrayList<>(base.isEmpty() ? List.of() : List.of(base.split("/")));
        for (String seg : spec.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.add(seg);
            }
        }
        String joined = String.join("/", parts);
        for (String ext : List.of(".js", ".mjs", ".cjs")) {
            if (joined.endsWith(ext)) return joined.substring(0, joined.length() - ext.length());
        }
        return joined;
    }

    /** The npm package a bare specifier names: {@code @scope/name} or the first segment. */
    static String packageOf(String spec) {
        String[] parts = spec.split("/");
        return spec.startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0];
    }
}
