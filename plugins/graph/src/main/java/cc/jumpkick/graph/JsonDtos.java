// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.host.CodeText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The JSON a Java route handler reads and writes, read from source as Jackson's defaults see it. A
 * handler's body types are its return type and its request-body parameter ({@code @RequestBody},
 * Micronaut's {@code @Body}, or JAX-RS's one unannotated parameter); every type name in them is a
 * candidate, so {@code ResponseEntity<List<OwnerDto>>} names {@code OwnerDto} and the library
 * wrappers drop out when they are resolved against the workspace.
 *
 * <p>A class's properties are its record components, public fields, public getters ({@code getX},
 * {@code isX} for a {@code boolean}), setters, and any field or accessor carrying {@code
 * @JsonProperty}; a Lombok {@code @Data}, {@code @Value} or {@code @Getter} class, or one with {@code
 * @JsonAutoDetect(fieldVisibility = ANY)}, exposes its fields. {@code @JsonProperty("name")} renames
 * a property on any of its members; {@code @JsonIgnore} and {@code @JsonIgnoreProperties} drop one;
 * a class-level {@code @JsonNaming} translates every name not given explicitly. Not read: Kotlin and
 * Groovy classes, mix-ins, a global naming strategy set in configuration or on an {@code
 * ObjectMapper}, custom serializers, {@code @JsonView}, {@code @JsonUnwrapped} prefixes and
 * polymorphic type ids.
 */
final class JsonDtos {

    private JsonDtos() {}

    /** One property: its JSON name, the line of the member that declares it, the type names of its value. */
    record Property(String name, int line, List<String> types) {}

    /** One class as Jackson writes it: its properties and the supertype it extends. */
    record Dto(
            String simple,
            List<Property> properties,
            @Nullable String supertype) {}

    /** A Java source as the readers below take it: comments blanked, and literals blanked too. */
    record Source(String code, String masked, int[] lineStarts) {

        static Source of(String text) {
            String code = CodeText.blank(text, CodeText.Blank.COMMENTS);
            String masked = CodeText.blank(text, CodeText.Blank.COMMENTS_AND_STRINGS);
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
            int[] s = new int[starts.size()];
            for (int i = 0; i < s.length; i++) s[i] = starts.get(i);
            return new Source(code, masked, s);
        }

        int line(int offset) {
            int lo = 0;
            int hi = lineStarts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (lineStarts[mid] <= offset) lo = mid;
                else hi = mid - 1;
            }
            return lo + 1;
        }
    }

    private static final Pattern MODIFIERS = Pattern.compile(
            "\\b(?:public|protected|private|static|final|abstract|synchronized|native|default|transient|volatile|strictfp)\\b");
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern HANDLER = Pattern.compile(
            "@(?:\\w+\\.)*(?:Get|Post|Put|Delete|Patch|Request)Mapping\\b|@(?:\\w+\\.)*(?:GET|POST|PUT|DELETE|PATCH|Path)\\b"
                    + "|@(?:\\w+\\.)*(?:Get|Post|Put|Delete|Patch)\\b");
    private static final Pattern BODY_PARAM = Pattern.compile("@(?:\\w+\\.)*(?:RequestBody|Body)\\b");
    private static final Pattern JAX_RS_PARAM = Pattern.compile(
            "@(?:\\w+\\.)*(?:PathParam|QueryParam|HeaderParam|CookieParam|FormParam|MatrixParam|BeanParam|Context|Suspended)\\b");
    private static final Pattern JSON_PROPERTY = Pattern.compile("@(?:\\w+\\.)*JsonProperty\\s*(\\(([^)]*)\\))?");
    private static final Pattern JSON_IGNORE =
            Pattern.compile("@(?:\\w+\\.)*JsonIgnore\\b(?!Properties)(\\s*\\(\\s*false\\s*\\))?");
    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern IGNORE_PROPERTIES =
            Pattern.compile("@(?:\\w+\\.)*JsonIgnoreProperties\\s*\\(([^)]*)\\)");
    private static final Pattern NAMING =
            Pattern.compile("@(?:\\w+\\.)*JsonNaming\\s*\\(\\s*(?:value\\s*=\\s*)?([\\w.$]+)\\.class");
    private static final Pattern LOMBOK_GETTERS = Pattern.compile("@(?:lombok\\.)?(?:Data|Value|Getter)\\b");
    private static final Pattern FIELDS_ANY = Pattern.compile("fieldVisibility\\s*=\\s*(?:\\w+\\.)*ANY\\b");

    /**
     * The type names (as written) of the bodies the handler {@code method} declared in {@code src}
     * reads and writes: those of every declaration of that name carrying a mapping annotation.
     */
    static List<String> bodyTypes(Source src, String method) {
        Set<String> out = new LinkedHashSet<>();
        boolean jaxRs = src.code().contains("jakarta.ws.rs") || src.code().contains("javax.ws.rs");
        // A Spring MVC @Controller's return is a view name or model unless @ResponseBody says otherwise.
        boolean views = src.code().contains("@Controller")
                && !src.code().contains("@RestController")
                && !src.code().contains("io.micronaut")
                && !classResponseBody(src.code());
        Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(src.masked());
        while (m.find()) {
            int start = statementStart(src.masked(), m.start());
            String header = src.code().substring(start, m.start());
            if (!HANDLER.matcher(header).find()) continue;
            int open = src.masked().indexOf('(', m.start());
            int close = closing(src.masked(), open, '(', ')');
            if (close < 0) continue;
            String after = src.masked()
                    .substring(close + 1, Math.min(src.masked().length(), close + 200))
                    .strip();
            if (!after.startsWith("{") && !after.startsWith("throws") && !after.startsWith(";")) continue;
            if (!views || header.contains("@ResponseBody")) out.addAll(typeNames(returnType(header)));
            for (String p : topLevel(src.code().substring(open + 1, close), ',')) {
                boolean body = BODY_PARAM.matcher(p).find() || jaxRs && jaxRsEntity(p);
                if (body) out.addAll(typeNames(paramType(p)));
            }
        }
        return List.copyOf(out);
    }

    /** Whether {@code @ResponseBody} annotates the class itself: it comes before the class keyword. */
    private static boolean classResponseBody(String code) {
        Matcher c = Pattern.compile("\\bclass\\s").matcher(code);
        int at = c.find() ? c.start() : code.length();
        return code.substring(0, at).contains("@ResponseBody");
    }

    /** A JAX-RS parameter with no annotation but validation's: the request entity. */
    private static boolean jaxRsEntity(String param) {
        return !param.replaceAll("@(?:\\w+\\.)*(?:Valid|NotNull|NonNull|Validated)\\b", "")
                .contains("@");
    }

    /**
     * The class {@code simple} declared in {@code src} as Jackson writes it; {@code null} when the
     * file declares no class, record or interface of that name, or it is an enum (written as a string).
     */
    static @Nullable Dto dto(Source src, String simple) {
        Matcher m = Pattern.compile("\\b(class|record|interface|enum)\\s+" + Pattern.quote(simple) + "\\b")
                .matcher(src.masked());
        if (!m.find()) return null;
        if (m.group(1).equals("enum")) return null;
        int start = statementStart(src.masked(), m.start());
        String annotations = src.code().substring(start, m.start());
        int open = src.masked().indexOf('{', m.end());
        if (open < 0) return null;
        int close = closing(src.masked(), open, '{', '}');
        if (close < 0) close = src.masked().length();
        String head = src.masked().substring(m.end(), open);
        String supertype = null;
        Matcher ext = Pattern.compile("\\bextends\\s+([\\w.$]+)").matcher(head);
        if (ext.find() && !m.group(1).equals("interface")) supertype = ext.group(1);
        Model model = new Model(annotations);
        if (m.group(1).equals("record")) {
            int p = src.masked().indexOf('(', m.end());
            int q = p < 0 || p > open ? -1 : closing(src.masked(), p, '(', ')');
            if (q > 0) {
                int at = p + 1;
                for (String c : topLevel(src.code().substring(p + 1, q), ',')) {
                    String name = lastIdent(stripAnnotations(c));
                    if (name != null) {
                        model.member(name, Kind.COMPONENT, c, src.line(at + leading(c)), typeNames(paramType(c)));
                    }
                    at += c.length() + 1;
                }
            }
        }
        members(src, open + 1, close, m.group(1).equals("interface"), model);
        return new Dto(simple, model.properties(), supertype);
    }

    private enum Kind {
        COMPONENT,
        FIELD,
        GETTER,
        SETTER
    }

    /** The members of one class by the property they belong to (their internal name). */
    private static final class Model {
        final Map<String, List<Member>> byName = new LinkedHashMap<>();
        final Set<String> ignored = new LinkedHashSet<>();
        final @Nullable String naming;
        final boolean fieldsVisible;

        record Member(
                Kind kind, boolean visible, @Nullable String explicit, boolean ignore, int line, List<String> types) {}

        Model(String classAnnotations) {
            Matcher n = NAMING.matcher(classAnnotations);
            naming = n.find() ? n.group(1) : null;
            fieldsVisible = LOMBOK_GETTERS.matcher(classAnnotations).find()
                    || FIELDS_ANY.matcher(classAnnotations).find();
            Matcher ip = IGNORE_PROPERTIES.matcher(classAnnotations);
            if (ip.find()) {
                Matcher q = QUOTED.matcher(ip.group(1));
                while (q.find()) ignored.add(q.group(1));
            }
        }

        void member(String name, Kind kind, String text, int line, List<String> types) {
            add(name, kind, kind == Kind.COMPONENT || kind == Kind.SETTER, text, line, types);
        }

        void add(String name, Kind kind, boolean visible, String text, int line, List<String> types) {
            String explicit = null;
            Matcher p = JSON_PROPERTY.matcher(text);
            if (p.find()) {
                Matcher q = p.group(2) == null ? null : QUOTED.matcher(p.group(2));
                explicit = q != null && q.find() && !q.group(1).isEmpty() ? q.group(1) : "";
            }
            Matcher ig = JSON_IGNORE.matcher(text);
            boolean ignore = ig.find() && ig.group(1) == null;
            byName.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new Member(kind, visible || explicit != null, explicit, ignore, line, types));
        }

        List<Property> properties() {
            List<Property> out = new ArrayList<>();
            for (Map.Entry<String, List<Member>> e : byName.entrySet()) {
                List<Member> ms = e.getValue();
                boolean visible = false;
                boolean ignore = false;
                String explicit = null;
                int line = Integer.MAX_VALUE;
                int fieldLine = Integer.MAX_VALUE;
                List<String> types = new ArrayList<>();
                for (Member m : ms) {
                    boolean shown = m.visible() || m.kind() == Kind.FIELD && fieldsVisible;
                    visible |= shown;
                    ignore |= m.ignore();
                    if (m.explicit() != null && !m.explicit().isEmpty()) explicit = m.explicit();
                    if (m.kind() == Kind.FIELD || m.kind() == Kind.COMPONENT) fieldLine = Math.min(fieldLine, m.line());
                    if (shown) line = Math.min(line, m.line());
                    for (String t : m.types()) if (!types.contains(t)) types.add(t);
                }
                if (!visible || ignore) continue;
                String name = explicit != null ? explicit : translate(e.getKey(), naming);
                if (ignored.contains(name)) continue;
                out.add(new Property(name, fieldLine != Integer.MAX_VALUE ? fieldLine : line, List.copyOf(types)));
            }
            return out;
        }
    }

    /** The direct members of the class body between {@code from} and {@code to}, nested types and bodies skipped. */
    private static void members(Source src, int from, int to, boolean iface, Model model) {
        String masked = src.masked();
        int i = from;
        while (i < to) {
            int start = i;
            int end = i;
            int depth = 0;
            boolean init = false;
            int block = -1;
            while (end < to) {
                char c = masked.charAt(end);
                if (c == '(' || c == '[') {
                    depth++;
                } else if ((c == ')' || c == ']') && depth > 0) {
                    depth--;
                } else if (depth == 0 && c == '=') {
                    init = true;
                } else if (depth == 0 && (c == ';' || c == '}')) {
                    break;
                } else if (depth == 0 && c == '{') {
                    int close = closing(masked, end, '{', '}');
                    if (close < 0 || close >= to) {
                        end = to;
                        break;
                    }
                    // An initialiser's braces (an array, a lambda, an anonymous class) end at the ';' after them.
                    if (init) {
                        end = close + 1;
                        continue;
                    }
                    block = end;
                    break;
                }
                end++;
            }
            if (end >= to) break;
            String text = src.code().substring(start, block >= 0 ? block : end);
            if (!text.isBlank()) member(src, text, start, iface, model);
            i = block >= 0 ? closing(masked, block, '{', '}') + 1 : end + 1;
        }
    }

    private static final Pattern NESTED = Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w");
    private static final Pattern STATIC = Pattern.compile("\\bstatic\\b");
    private static final Pattern PUBLIC = Pattern.compile("\\bpublic\\b");
    private static final Pattern TRANSIENT = Pattern.compile("\\btransient\\b");

    /** One member's declaration ({@code text}, up to its body or {@code ;}) into {@code model}. */
    private static void member(Source src, String text, int at, boolean iface, Model model) {
        String bare = stripAnnotations(CodeText.blank(text, CodeText.Blank.COMMENTS_AND_STRINGS));
        if (NESTED.matcher(bare).find() || STATIC.matcher(bare).find()) return;
        boolean isPublic = iface || PUBLIC.matcher(bare).find();
        int eq = bare.indexOf('=');
        int paren = bare.indexOf('(');
        if (paren >= 0 && (eq < 0 || paren < eq)) {
            String head = bare.substring(0, paren);
            String name = lastIdent(head);
            if (name == null) return;
            int close = closing(bare, paren, '(', ')');
            String params = close < 0
                    ? ""
                    : stripAnnotations(text)
                            .substring(
                                    paren + 1,
                                    Math.min(close, stripAnnotations(text).length()))
                            .strip();
            String ret = returnType(head.substring(0, head.length() - name.length()));
            int line = src.line(
                    at + Math.max(0, text.indexOf(name + "(") >= 0 ? text.indexOf(name + "(") : text.indexOf(name)));
            if (params.isEmpty()
                    && name.length() > 3
                    && name.startsWith("get")
                    && !name.equals("getClass")
                    && !ret.equals("void")
                    && !ret.isEmpty()) {
                model.add(mangle(name.substring(3)), Kind.GETTER, isPublic, text, line, typeNames(ret));
            } else if (params.isEmpty() && name.length() > 2 && name.startsWith("is") && ret.equals("boolean")) {
                model.add(mangle(name.substring(2)), Kind.GETTER, isPublic, text, line, List.of());
            } else if (name.length() > 3
                    && name.startsWith("set")
                    && !params.isEmpty()
                    && topLevel(params, ',').size() == 1) {
                model.add(mangle(name.substring(3)), Kind.SETTER, true, text, line, typeNames(paramType(params)));
            } else if (params.isEmpty()
                    && !ret.equals("void")
                    && !ret.isEmpty()
                    && JSON_PROPERTY.matcher(text).find()) {
                model.add(name, Kind.GETTER, true, text, line, typeNames(ret));
            }
            return;
        }
        if (TRANSIENT.matcher(bare).find()) return;
        String decl = eq < 0 ? bare : bare.substring(0, eq);
        List<String> parts = topLevel(decl, ',');
        String type = null;
        for (String part : parts) {
            String n = lastIdent(part);
            if (n == null) continue;
            if (type == null)
                type = MODIFIERS
                        .matcher(part.substring(0, part.lastIndexOf(n)))
                        .replaceAll(" ")
                        .strip();
            if (type.isEmpty()) return;
            Matcher declared = Pattern.compile("(?<![\\w$])" + Pattern.quote(n) + "(?![\\w$])(?=\\s*(?:=|;|,|$))")
                    .matcher(text);
            int off = declared.find() ? declared.start() : 0;
            model.add(n, Kind.FIELD, isPublic, text, src.line(at + off), typeNames(type));
        }
    }

    /** Where the declaration holding {@code at} starts: after the last {@code ;}, {@code {} or {@code }} before it. */
    static int statementStart(String masked, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            else if (depth == 0 && (c == ';' || c == '{' || c == '}')) return i + 1;
        }
        return 0;
    }

    /** The offset of the bracket closing the one at {@code open}; -1 when it is not closed. */
    static int closing(String masked, int open, char o, char c) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char ch = masked.charAt(i);
            if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return -1;
    }

    /** {@code text} split at {@code sep} outside brackets and angle brackets. */
    static List<String> topLevel(String text, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == '>' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            else if (c == sep && depth == 0) {
                out.add(text.substring(from, i));
                from = i + 1;
            }
        }
        if (from < text.length() && !text.substring(from).isBlank()) out.add(text.substring(from));
        return out;
    }

    /** {@code text} without its annotations ({@code @JsonProperty("x")}, {@code @Valid}). */
    static String stripAnnotations(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '@' && i + 1 < text.length() && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                int j = i + 1;
                while (j < text.length() && (Character.isJavaIdentifierPart(text.charAt(j)) || text.charAt(j) == '.'))
                    j++;
                int k = j;
                while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
                if (k < text.length() && text.charAt(k) == '(') {
                    int depth = 0;
                    for (; k < text.length(); k++) {
                        if (text.charAt(k) == '(') depth++;
                        else if (text.charAt(k) == ')' && --depth == 0) break;
                    }
                    j = Math.min(text.length(), k + 1);
                }
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The return type in a method's header (annotations, modifiers, type parameters and the type). */
    static String returnType(String header) {
        String t = MODIFIERS.matcher(stripAnnotations(header)).replaceAll(" ").strip();
        if (t.startsWith("<")) {
            int close = closing(t, 0, '<', '>');
            t = close < 0 ? t : t.substring(close + 1).strip();
        }
        return t;
    }

    /** The type of one parameter: annotations, {@code final} and its name removed. */
    static String paramType(String param) {
        String p = MODIFIERS.matcher(stripAnnotations(param)).replaceAll(" ").strip();
        String name = lastIdent(p);
        return name == null
                ? p
                : p.substring(0, p.length() - name.length())
                        .replace("...", "[]")
                        .strip();
    }

    /** The type names {@code type} is written with: {@code ResponseEntity<List<OwnerDto>>} names three. */
    static List<String> typeNames(String type) {
        List<String> out = new ArrayList<>();
        Matcher m = TYPE_NAME.matcher(type.replace('?', ' '));
        while (m.find()) {
            String n = m.group();
            if (n.equals("extends") || n.equals("super")) continue;
            String last = n.substring(n.lastIndexOf('.') + 1);
            if (!last.isEmpty() && Character.isUpperCase(last.charAt(0)) && !out.contains(n)) out.add(n);
        }
        return out;
    }

    private static @Nullable String lastIdent(String text) {
        Matcher m = IDENT.matcher(text);
        String last = null;
        while (m.find()) last = m.group();
        return last;
    }

    private static int leading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Jackson's default bean name: {@code BirthDate} as {@code birthDate}, {@code URL} as {@code url}. */
    static String mangle(String suffix) {
        StringBuilder s = new StringBuilder(suffix);
        for (int i = 0; i < s.length() && Character.isUpperCase(s.charAt(i)); i++) {
            s.setCharAt(i, Character.toLowerCase(s.charAt(i)));
        }
        return s.toString();
    }

    /** {@code name} as {@code @JsonNaming(strategy)} writes it; unchanged for a strategy not listed. */
    static String translate(String name, @Nullable String strategy) {
        if (strategy == null) return name;
        String s = strategy.substring(strategy.lastIndexOf('.') + 1);
        return switch (s) {
            case "SnakeCaseStrategy", "SNAKE_CASE" -> separated(name, '_', false);
            case "UpperSnakeCaseStrategy", "UPPER_SNAKE_CASE" -> separated(name, '_', true);
            case "KebabCaseStrategy", "KEBAB_CASE" -> separated(name, '-', false);
            case "LowerDotCaseStrategy", "LOWER_DOT_CASE" -> separated(name, '.', false);
            case "LowerCaseStrategy", "LOWER_CASE" -> name.toLowerCase(Locale.ROOT);
            case "UpperCamelCaseStrategy", "UPPER_CAMEL_CASE" ->
                name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            default -> name;
        };
    }

    private static String separated(String name, char sep, boolean upper) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && out.charAt(out.length() - 1) != sep) out.append(sep);
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        String s = out.toString();
        return upper ? s.toUpperCase(Locale.ROOT) : s;
    }
}
