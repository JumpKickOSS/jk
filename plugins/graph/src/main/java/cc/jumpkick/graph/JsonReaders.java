// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.host.CodeText;
import cc.jumpkick.host.OutputDirs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where client files read or write a JSON property, as text: each reader returns the names with the
 * line and the code block they sit in, and {@link JsonAnchors} decides which route or class each is
 * tied to. Readers: TypeScript and JavaScript (a property read {@code .birthDate} or {@code
 * ['birthDate']}, a destructured name, an object literal's key, an interface's, type's or class's
 * member), jq programs in scripts and workflows, JSONPath literals in JVM tests ({@code
 * jsonPath("$.birthDate")}), JSON documents' keys (fixtures, OpenAPI in JSON) and OpenAPI YAML.
 */
final class JsonReaders {

    private JsonReaders() {}

    /** One name read or written at {@code line}; {@code block} is the function holding it, -1 for none. */
    record Use(String name, int line, int block) {}

    /** One member {@code name} of the type {@code type} declared at {@code line} (a TS interface, an OpenAPI schema). */
    record Member(String type, String name, int line) {}

    /** The text's code blocks a function owns: each the offsets of its braces. */
    record Blocks(int[] open, int[] close) {

        /** The outermost function block holding {@code offset}; -1 for none. */
        int at(int offset) {
            for (int i = 0; i < open.length; i++) {
                if (open[i] < offset && offset < close[i]) return i;
            }
            return -1;
        }
    }

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Set<String> CONTROL =
            Set.of("if", "for", "while", "switch", "catch", "with", "return", "typeof", "await", "else");

    // ---------------------------------------------------------------- C-family text

    /** Line starts of {@code text}, for {@link #line}. */
    static int[] lineStarts(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        int[] s = new int[n];
        int k = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') s[k++] = i + 1;
        return s;
    }

    static int line(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
    }

    /**
     * The function bodies of a C-family text ({@code masked}: comments and literals blanked), outermost
     * first: a block opened after {@code =>}, or after a parameter list (and a return type) that is not
     * an {@code if}, {@code for}, {@code while}, {@code switch} or {@code catch}.
     */
    static Blocks functions(String masked) {
        List<int[]> found = new ArrayList<>();
        for (int o = masked.indexOf('{'); o >= 0; o = masked.indexOf('{', o + 1)) {
            if (!functionBrace(masked, o)) continue;
            int close = JsonDtos.closing(masked, o, '{', '}');
            int c = close < 0 ? masked.length() : close;
            boolean inside = false;
            for (int[] f : found) {
                if (f[0] < o && o < f[1]) {
                    inside = true;
                    break;
                }
            }
            if (!inside) found.add(new int[] {o, c});
        }
        int[] open = new int[found.size()];
        int[] close = new int[found.size()];
        for (int i = 0; i < open.length; i++) {
            open[i] = found.get(i)[0];
            close[i] = found.get(i)[1];
        }
        return new Blocks(open, close);
    }

    private static boolean functionBrace(String masked, int brace) {
        int i = brace - 1;
        while (i >= 0 && Character.isWhitespace(masked.charAt(i))) i--;
        if (i >= 1 && masked.charAt(i) == '>' && masked.charAt(i - 1) == '=') return true;
        // Past a return type annotation, ": Promise<Owner>", back to the parameter list's ')'.
        int depth = 0;
        int limit = Math.max(0, brace - 200);
        for (; i >= limit; i--) {
            char c = masked.charAt(i);
            if (c == '>') depth++;
            else if (c == '<') depth--;
            else if (c == ')' && depth == 0) break;
            else if (depth == 0 && (c == ';' || c == '{' || c == '}' || c == '=' || c == '(')) return false;
        }
        if (i < limit || masked.charAt(i) != ')') return false;
        int open = openingParen(masked, i);
        if (open < 0) return false;
        int j = open - 1;
        while (j >= 0 && Character.isWhitespace(masked.charAt(j))) j--;
        int end = j + 1;
        while (j >= 0 && (Character.isJavaIdentifierPart(masked.charAt(j)))) j--;
        String word = masked.substring(j + 1, end);
        return !CONTROL.contains(word);
    }

    private static int openingParen(String masked, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')') depth++;
            else if (c == '(' && --depth == 0) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- TypeScript / JavaScript

    private static final Pattern DOT_READ =
            Pattern.compile("(?<=[\\w$)\\]!?])\\s*\\??\\.\\s*([A-Za-z_$][\\w$]*)\\b(?!\\s*\\()");
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^{}`]*)}");
    private static final Pattern BRACKET_READ = Pattern.compile("\\[\\s*(['\"])([A-Za-z_$][\\w$-]*)\\1\\s*]");
    private static final Pattern DESTRUCTURE = Pattern.compile(
            "(?:\\b(?:const|let|var)\\s*\\{([^{}]*)}\\s*(?::[^=;]*)?=)|(?:\\(\\s*\\{([^{}]*)}\\s*(?::[^)]*)?\\)\\s*(?:=>|\\{|:))");
    private static final Pattern OBJECT_KEY =
            Pattern.compile("[{,]\\s*([A-Za-z_$][\\w$]*|'[^'\\n]+'|\"[^\"\\n]+\")\\s*:");
    private static final Pattern TYPE_DECL =
            Pattern.compile("\\b(interface|type|class)\\s+([A-Z][\\w$]*)\\b[^{;=]*(=\\s*)?\\{");
    private static final Pattern TYPE_MEMBER = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|private|protected|readonly|declare)\\s+)*([A-Za-z_$][\\w$]*|'[^']+'|\"[^\"]+\")\\s*[?!]?\\s*:");

    /** The JSON names a script reads or writes: property reads, destructured names and object keys, each with its function. */
    static List<Use> scriptUses(String text) {
        String masked = CodeText.blank(text, CodeText.Blank.COMMENTS_AND_STRINGS, true);
        String code = CodeText.blank(text, CodeText.Blank.COMMENTS, true);
        int[] starts = lineStarts(text);
        Blocks blocks = functions(masked);
        List<Use> out = new ArrayList<>();
        Matcher d = DOT_READ.matcher(masked);
        while (d.find()) out.add(new Use(d.group(1), line(starts, d.start(1)), blocks.at(d.start(1))));
        // Inside a template literal's ${…}, which the mask blanks with the literal.
        Matcher t = INTERPOLATION.matcher(code);
        while (t.find()) {
            if (masked.charAt(t.start()) != ' ') continue;
            Matcher r = DOT_READ.matcher(t.group(1));
            while (r.find()) {
                int at = t.start(1) + r.start(1);
                out.add(new Use(r.group(1), line(starts, at), blocks.at(at)));
            }
        }
        Matcher b = BRACKET_READ.matcher(code);
        while (b.find()) {
            if (masked.charAt(b.start()) != '[') continue;
            out.add(new Use(b.group(2), line(starts, b.start(2)), blocks.at(b.start(2))));
        }
        Matcher s = DESTRUCTURE.matcher(masked);
        while (s.find()) {
            int g = s.group(1) != null ? 1 : 2;
            int at = s.start(g);
            for (String part : s.group(g).split(",")) {
                String p = part.strip();
                int colon = p.indexOf(':');
                int eq = p.indexOf('=');
                String name = colon >= 0 ? p.substring(0, colon) : eq >= 0 ? p.substring(0, eq) : p;
                name = name.strip();
                if (!name.startsWith("...") && IDENT.matcher(name).matches()) {
                    out.add(new Use(name, line(starts, at), blocks.at(at)));
                }
                at += part.length() + 1;
            }
        }
        Matcher k = OBJECT_KEY.matcher(code);
        while (k.find()) {
            int at = k.start(1);
            char opener = masked.charAt(k.start());
            if (opener != '{' && opener != ',') continue;
            if (!inBraces(masked, at) || typeContext(masked, at)) continue;
            String name = unquote(k.group(1));
            out.add(new Use(name, line(starts, at), blocks.at(at)));
        }
        return out;
    }

    /** Whether the bracket nearest before {@code at} still open is a brace (an object, not a parameter list). */
    private static boolean inBraces(String masked, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')' || c == ']' || c == '}') depth++;
            else if (c == '(' || c == '[' || c == '{') {
                if (depth == 0) return c == '{';
                depth--;
            }
        }
        return false;
    }

    /** Whether the brace holding {@code at} opens a type ({@code : {}, {@code interface X {}, {@code type X = {}). */
    private static boolean typeContext(String masked, int at) {
        int depth = 0;
        int i = at - 1;
        for (; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth == 0) break;
                depth--;
            }
        }
        if (i < 0) return false;
        int j = i - 1;
        while (j >= 0 && Character.isWhitespace(masked.charAt(j))) j--;
        if (j >= 0
                && (masked.charAt(j) == ':'
                        || masked.charAt(j) == '<'
                        || masked.charAt(j) == '|'
                        || masked.charAt(j) == '&')) {
            return true;
        }
        int lineStart = masked.lastIndexOf('\n', i) + 1;
        String head = masked.substring(lineStart, i);
        return head.matches(".*\\b(?:interface|type|class|enum)\\s+\\w.*");
    }

    /** The members of every interface, object type and class a script declares, by the type's name. */
    static List<Member> scriptTypes(String text) {
        String masked = CodeText.blank(text, CodeText.Blank.COMMENTS_AND_STRINGS, true);
        String code = CodeText.blank(text, CodeText.Blank.COMMENTS, true);
        int[] starts = lineStarts(text);
        List<Member> out = new ArrayList<>();
        Matcher t = TYPE_DECL.matcher(masked);
        while (t.find()) {
            int open = t.end() - 1;
            int close = JsonDtos.closing(masked, open, '{', '}');
            if (close < 0) continue;
            String body = depthOne(masked, open, close);
            Matcher m = TYPE_MEMBER.matcher(body);
            while (m.find()) {
                int at = open + 1 + m.start(1);
                String name = unquote(code.substring(at, open + 1 + m.end(1)));
                out.add(new Member(t.group(2), name, line(starts, at)));
            }
        }
        return out;
    }

    /** The text between {@code open} and {@code close} with every nested bracket's content blanked. */
    private static String depthOne(String masked, int open, int close) {
        StringBuilder s = new StringBuilder(masked.substring(open + 1, close));
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '(' || c == '[' || c == '<') {
                depth++;
                continue;
            }
            if ((c == '}' || c == ')' || c == ']' || c == '>') && depth > 0) {
                depth--;
                continue;
            }
            if (depth > 0 && c != '\n') s.setCharAt(i, ' ');
        }
        return s.toString();
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"') && t.charAt(t.length() - 1) == t.charAt(0)) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    // ---------------------------------------------------------------- jq and JSONPath

    private static final Pattern JQ = Pattern.compile("(?<![\\w-])jq\\b");
    private static final Pattern JQ_FIELD = Pattern.compile("\\.\\s*([A-Za-z_]\\w*)|\\[\\s*\"([^\"]+)\"\\s*]");

    /**
     * The fields the jq programs of a shell-shaped text read, each on its logical line (a command
     * continued with {@code \} counts as the line it starts on); the block is the logical line's
     * first line, so a request on the same pipeline ties to it.
     */
    static List<Use> jqUses(String text) {
        List<Use> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int first = 0;
        StringBuilder logical = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (logical.isEmpty()) first = i;
            String l = lines[i];
            if (l.endsWith("\\")) {
                logical.append(l, 0, l.length() - 1).append(' ');
                continue;
            }
            logical.append(l);
            jq(logical.toString(), first + 1, i + 1, out);
            logical.setLength(0);
        }
        return out;
    }

    private static void jq(String line, int at, int last, List<Use> out) {
        Matcher m = JQ.matcher(line);
        while (m.find()) {
            String program = jqProgram(line, m.end());
            if (program == null) continue;
            String p = withoutStrings(program);
            Matcher f = JQ_FIELD.matcher(p);
            while (f.find()) {
                out.add(new Use(f.group(1) != null ? f.group(1) : f.group(2), last, at));
            }
        }
    }

    private static final Pattern JQ_STRING = Pattern.compile("(?<!\\[\\s{0,3})\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JQ_INTERPOLATION = Pattern.compile("\\\\\\((.*?)\\)");

    /**
     * A jq program with its strings' text dropped, a value and not a field, but their interpolations
     * kept ({@code "\\(.name)"} reads {@code .name}); a field's bracket name ({@code .["last-name"]}) stays.
     */
    private static String withoutStrings(String program) {
        Matcher m = JQ_STRING.matcher(program);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            StringBuilder kept = new StringBuilder(" ");
            Matcher i = JQ_INTERPOLATION.matcher(m.group(1));
            while (i.find()) kept.append(i.group(1)).append(' ');
            m.appendReplacement(out, Matcher.quoteReplacement(kept.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The program of the jq call whose arguments start at {@code from}: its first argument not an option. */
    private static @Nullable String jqProgram(String line, int from) {
        int i = from;
        int n = line.length();
        boolean takesValue = false;
        while (i < n) {
            while (i < n && Character.isWhitespace(line.charAt(i))) i++;
            if (i >= n) return null;
            char c = line.charAt(i);
            if (c == '|' || c == ';' || c == '&' || c == ')') return null;
            String token;
            int end;
            if (c == '\'' || c == '"') {
                end = line.indexOf(c, i + 1);
                if (end < 0) return null;
                token = line.substring(i + 1, end);
                end++;
            } else {
                end = i;
                while (end < n && !Character.isWhitespace(line.charAt(end))) end++;
                token = line.substring(i, end);
            }
            if (takesValue) {
                takesValue = false;
            } else if (token.startsWith("-") && c != '\'' && c != '"') {
                if (token.equals("--arg")
                        || token.equals("--argjson")
                        || token.equals("--slurpfile")
                        || token.equals("--rawfile")) {
                    i = skipToken(line, end);
                    takesValue = true;
                    continue;
                }
            } else {
                return token.contains(".") ? token : null;
            }
            i = end;
        }
        return null;
    }

    private static int skipToken(String line, int from) {
        int i = from;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        while (i < line.length() && !Character.isWhitespace(line.charAt(i))) i++;
        return i;
    }

    private static final Pattern JSON_PATH_FIELD = Pattern.compile("\\.\\.?([A-Za-z_][\\w]*)|\\[\\s*'([^']+)'\\s*]");

    /** The fields the JSONPath literals ({@code "$.owner.birthDate"}) of a JVM source name, each with its method. */
    static List<Use> jsonPathUses(String text) {
        String masked = CodeText.blank(text, CodeText.Blank.COMMENTS_AND_STRINGS);
        int[] starts = lineStarts(text);
        Blocks blocks = functions(masked);
        List<Use> out = new ArrayList<>();
        for (CodeText.Literal lit : CodeText.literals(text)) {
            String body = lit.body();
            if (!body.startsWith("$.") && !body.startsWith("$[")) continue;
            Matcher f = JSON_PATH_FIELD.matcher(body.substring(1));
            while (f.find()) {
                String name = f.group(1) != null ? f.group(1) : f.group(2);
                out.add(new Use(name, line(starts, lit.start()), blocks.at(lit.start())));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- JSON and YAML documents

    /** One key of a document, with the keys above it ({@code paths./owners/{id}.get}) and its line. */
    record Key(List<String> path, String name, int line) {}

    /** Every object key of a JSON document, with its path; empty when the text is not JSON. */
    static List<Key> jsonKeys(String text) {
        List<Key> out = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<Boolean> inObject = new ArrayList<>();
        String pending = null;
        int line = 1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '"') {
                int j = i + 1;
                StringBuilder s = new StringBuilder();
                while (j < n && text.charAt(j) != '"') {
                    if (text.charAt(j) == '\\' && j + 1 < n) j++;
                    if (text.charAt(j) == '\n') line++;
                    s.append(text.charAt(j));
                    j++;
                }
                int k = j + 1;
                while (k < n && Character.isWhitespace(text.charAt(k))) k++;
                if (k < n && text.charAt(k) == ':' && !inObject.isEmpty() && inObject.getLast()) {
                    out.add(new Key(List.copyOf(path), s.toString(), line));
                    pending = s.toString();
                }
                i = j;
            } else if (c == '{' || c == '[') {
                inObject.add(c == '{');
                path.add(pending == null ? (c == '[' ? "[]" : "") : pending);
                pending = null;
            } else if (c == '}' || c == ']') {
                if (!inObject.isEmpty()) inObject.removeLast();
                if (!path.isEmpty()) path.removeLast();
                pending = null;
            } else if (c == ',') {
                pending = null;
            }
        }
        // The outermost container's own entry is not a key.
        List<Key> trimmed = new ArrayList<>(out.size());
        for (Key k : out)
            trimmed.add(new Key(
                    k.path().isEmpty() ? k.path() : k.path().subList(1, k.path().size()), k.name(), k.line()));
        return trimmed;
    }

    private static final Pattern YAML_KEY =
            Pattern.compile("^(\\s*)(-\\s+)?(?:'([^']*)'|\"([^\"]*)\"|([^\\s#'\"\\-][^#]*?))\\s*:(?:\\s+(.*?))?\\s*$");

    /** Every mapping key of a YAML document with its path, by indentation; flow mappings are not read. */
    static List<Key> yamlKeys(String text) {
        List<Key> out = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        List<String> path = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean block = false;
        int blockIndent = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.isBlank() || l.stripLeading().startsWith("#")) continue;
            int indent = 0;
            while (indent < l.length() && l.charAt(indent) == ' ') indent++;
            if (block) {
                if (indent > blockIndent) continue;
                block = false;
            }
            Matcher m = YAML_KEY.matcher(l);
            if (!m.matches()) continue;
            int keyIndent =
                    m.group(1).length() + (m.group(2) == null ? 0 : m.group(2).length());
            while (!indents.isEmpty() && indents.getLast() >= keyIndent) {
                indents.removeLast();
                path.removeLast();
            }
            String key = (m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5)).strip();
            out.add(new Key(List.copyOf(path), key, i + 1));
            String value = m.group(6) == null ? "" : m.group(6).strip();
            if (value.equals("|") || value.equals(">") || value.startsWith("|-") || value.startsWith(">-")) {
                block = true;
                blockIndent = keyIndent;
            }
            indents.add(keyIndent);
            path.add(key);
        }
        return out;
    }

    /** The value of the YAML key at {@code line} of {@code text}, unquoted; empty when it has none. */
    static String yamlValue(String text, int line) {
        String[] lines = text.split("\n", -1);
        if (line < 1 || line > lines.length) return "";
        Matcher m = YAML_KEY.matcher(lines[line - 1]);
        return m.matches() && m.group(6) != null ? unquote(m.group(6).replaceAll("\\s+#.*$", "")) : "";
    }

    // ---------------------------------------------------------------- generated files

    private static final Pattern GENERATED_HEADER = Pattern.compile(
            "(?i)auto-?generated|do not (?:edit|modify)|generated by|@generated|this file was generated");

    /** Whether the head of {@code text} says a tool wrote it. */
    static boolean generatedHeader(String text) {
        int end = 0;
        for (int i = 0; i < 12 && end >= 0; i++) end = text.indexOf('\n', end + 1);
        String head = end < 0 ? text : text.substring(0, end);
        return GENERATED_HEADER.matcher(head).find();
    }

    private static final Pattern XML_OUTPUT =
            Pattern.compile("<(outputDir|output|outputFileName|outputDirectory|outputFile)>\\s*([^<]+?)\\s*</\\1>");
    private static final Pattern GRADLE_OUTPUT = Pattern.compile(
            "\\b(?:outputDir|outputFileName|outputFile|output)\\b\\s*(?:\\.set\\s*\\(|=|\\()\\s*(?:file\\s*\\(|layout\\.\\w+\\.\\w+\\s*\\(|project\\.file\\s*\\()?\\s*\"([^\"]+)\"");
    private static final Pattern NPM_OUTPUT = Pattern.compile(
            "(?:openapi-typescript|openapi-generator(?:-cli)?|orval|swagger-typescript-api|openapi-zod-client|openapi2ts)[^\"]*?(?:-o|--output)\\s+([^\\s\"&;]+)");

    /**
     * The paths an OpenAPI or client generator writes, as a build file ({@code pom.xml}, {@code
     * build.gradle[.kts]}, {@code package.json}) in directory {@code dir} names them: a springdoc
     * plugin's output file, an openapi-generator's output directory, an npm script's {@code -o}.
     * Relative to the workspace; a directory ends with {@code /}.
     */
    static List<String> generatorOutputs(String buildFile, String dir, String text) {
        List<String> out = new ArrayList<>();
        String name = buildFile.toLowerCase(Locale.ROOT);
        if (name.equals("pom.xml")) {
            Matcher plugin = Pattern.compile("(?s)<plugin>.*?</plugin>").matcher(text);
            while (plugin.find()) {
                String p = plugin.group();
                if (!p.toLowerCase(Locale.ROOT).matches("(?s).*(openapi|swagger|springdoc).*")) continue;
                Matcher o = XML_OUTPUT.matcher(p);
                String outDir = "";
                List<String> files = new ArrayList<>();
                while (o.find()) {
                    String v = mavenPath(o.group(2));
                    if (o.group(1).equals("outputFileName") || o.group(1).equals("outputFile")) files.add(v);
                    else outDir = v;
                }
                if (files.isEmpty() && !outDir.isEmpty()) out.add(join(dir, outDir) + "/");
                for (String f : files) out.add(outDir.isEmpty() ? "*/" + f : join(dir, outDir) + "/" + f);
            }
        } else if (name.startsWith("build.gradle")) {
            if (!text.toLowerCase(Locale.ROOT).matches("(?s).*(openapi|swagger|springdoc).*")) return out;
            Matcher o = GRADLE_OUTPUT.matcher(text);
            while (o.find()) {
                String v = o.group(1).replace("$buildDir", "build").replace("${buildDir}", "build");
                out.add(
                        v.contains(".") && !v.endsWith("/")
                                ? "*/" + v.substring(v.lastIndexOf('/') + 1)
                                : join(dir, v) + "/");
            }
        } else if (name.equals("package.json")) {
            Matcher o = NPM_OUTPUT.matcher(text);
            while (o.find()) {
                String v = o.group(1);
                out.add(join(dir, v) + (v.matches(".*\\.\\w+$") ? "" : "/"));
            }
        }
        return out;
    }

    private static String mavenPath(String v) {
        return v.replace("${project.basedir}/", "")
                .replace("${basedir}/", "")
                .replace("${project.build.directory}", OutputDirs.TARGET)
                .strip();
    }

    private static String join(String dir, String rel) {
        String r = rel.startsWith("./") ? rel.substring(2) : rel;
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return dir.isEmpty() ? r : dir + "/" + r;
    }

    /** Whether {@code rel} is one of {@code outputs}: a file named, or a file under a directory named. */
    static boolean generatedBy(String rel, List<String> outputs) {
        for (String o : outputs) {
            if (o.startsWith("*/")) {
                if (rel.endsWith("/" + o.substring(2)) || rel.equals(o.substring(2))) return true;
            } else if (o.endsWith("/") ? rel.startsWith(o) : rel.equals(o)) {
                return true;
            }
        }
        return false;
    }
}
