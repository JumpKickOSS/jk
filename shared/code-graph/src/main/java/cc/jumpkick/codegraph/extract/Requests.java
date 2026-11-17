// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * HTTP requests written in text: a URL to this service in a command line, a workflow step or a
 * code block ({@code curl -X POST http://localhost:8080/api/orders}), and a front-end call with a
 * path it can read ({@code http.get('/api/orders')}, {@code fetch(`/api/orders/${id}`)}, {@code
 * cy.request('POST', '/api/orders', order)}, {@code cy.intercept('GET', '/api/orders+(?*|)')}, an
 * options object's {@code url: '/api/orders'} with its {@code method}). A path may be built from a
 * string constant the same file assigns once ({@code resourceUrl = '/api/labels'}, or through a
 * helper given one literal, {@code getEndpointFor('api/labels')}): {@code `${this.resourceUrl}/${id}`}
 * and {@code this.resourceUrl + '/' + id} read as {@code /api/labels/{}}. Each is a {@link
 * Mention.Kind#ROUTE} mention, which the linker links only to a route a handler declares, so a path
 * that matches none adds nothing. A URL counts only when its host is this machine, a variable or a
 * one-label name such as a compose service: a public URL ({@code https://docs.example.com/api/...})
 * is someone else's route.
 */
final class Requests {

    private Requests() {}

    private static final Pattern URL = Pattern.compile("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1]"
            + "|\\$\\{?\\w+}?|[A-Za-z][\\w-]*)(?::(?:\\d+|\\$\\{?\\w+}?))?(/[^\\s'\"`)<>|;,\\\\]*)");
    private static final Pattern METHOD_FLAG = Pattern.compile("(?:-X|--request)\\s*['\"]?([A-Za-z]+)");
    private static final Pattern BODY_FLAG =
            Pattern.compile("(?:^|\\s)(?:-d|-F|-{2}(?:data(?:-raw|-binary)?|json|form))\\b");
    private static final Pattern CALL = Pattern.compile(
            "\\.(get|post|put|delete|patch|head|options)\\s*(?:<[^>()]*>)?\\s*\\(|\\b(fetch)\\s*\\(|\\bcy\\.(request|intercept)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_KEY = Pattern.compile("(?<![\\w$.'\"])url\\s*:\\s*(?=['\"`\\w$])");
    private static final Pattern METHOD_KEY = Pattern.compile("(?<![\\w$.])method\\s*:\\s*(['\"])([A-Za-z]+)\\1");
    private static final Pattern HTTP_METHOD = Pattern.compile("(['\"])(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\1");
    private static final Pattern ASSIGNMENT =
            Pattern.compile("(?<![\\w$.])(?:this\\.)?([A-Za-z_$][\\w$]*)\\s*(?::\\s*string\\s*)?=(?![=>])\\s*");
    private static final Pattern HELPER = Pattern.compile("[A-Za-z_$][\\w$.]*\\s*\\(\\s*(['\"])([^'\"\\n]*)\\1\\s*\\)");
    private static final Pattern IDENT = Pattern.compile("(?:this\\.)?([A-Za-z_$][\\w$]*)");
    private static final Pattern PATHLIKE = Pattern.compile("[\\w${}.-]+(?:/[\\w${}.*+?()|:-]*)+");
    /** A variable named as a base URL ({@code serverApiUrl}, {@code baseUrl}, {@code API_BASE}): what follows it is the path. */
    private static final Pattern BASE_URL = Pattern.compile("(?i)(?:url|base|host|origin|endpoint|root)$");

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{[^}]*}|\\$\\w+");

    /**
     * The URLs to this service on one line of a command, a workflow, a config value or a code block,
     * and the ports on this machine they name ({@link Ports#urls}).
     */
    static void urls(FragmentBuilder b, String from, String line, int n) {
        Ports.urls(b, from, line, n);
        if (!line.contains("://")) return;
        Matcher u = URL.matcher(line);
        while (u.find()) {
            String path = variables(u.group(1));
            if (path.length() > 1) b.mention(from, Mention.Kind.ROUTE, method(line) + " " + path, n);
        }
    }

    /**
     * Front-end calls whose path can be read: a literal or template, or one built from a string
     * constant of the same file; {@code http.get(...)}, {@code fetch(...)}, {@code
     * cy.request}/{@code cy.intercept} with or without a method, and an options object's {@code url}.
     */
    static void calls(FragmentBuilder b, String from, String text, Lines lines) {
        // The file's constants are collected only once a request needs them: most scripts make none.
        Map<String, String> constants = new LazyConstants(text);
        Matcher c = CALL.matcher(text);
        while (c.find()) {
            int[] first = argument(text, c.end());
            if (first == null) continue;
            String method;
            int[] pathArg = first;
            if (c.group(1) != null) {
                method = c.group(1).toUpperCase(Locale.ROOT);
            } else if (c.group(2) != null) {
                method = "*";
            } else {
                method = "*";
                Matcher m =
                        HTTP_METHOD.matcher(text.substring(first[0], first[1]).strip());
                if (m.matches()) {
                    method = m.group(2);
                    pathArg = text.charAt(first[1]) == ',' ? argument(text, first[1] + 1) : null;
                }
            }
            if (pathArg == null) continue;
            String path = path(text.substring(pathArg[0], pathArg[1]), constants);
            if (path != null) b.mention(from, Mention.Kind.ROUTE, method + " " + path, lines.at(c.start()));
        }
        Matcher u = URL_KEY.matcher(text);
        while (u.find()) {
            int[] value = argument(text, u.end());
            if (value == null) continue;
            String path = path(text.substring(value[0], value[1]), constants);
            if (path == null) continue;
            String method = "*";
            int open = enclosing(text, u.start());
            if (open >= 0) {
                int close = closing(text, open);
                Matcher m = METHOD_KEY.matcher(text.substring(open, close < 0 ? text.length() : close));
                if (m.find()) method = m.group(2).toUpperCase(Locale.ROOT);
            }
            b.mention(from, Mention.Kind.ROUTE, method + " " + path, lines.at(u.start()));
        }
        for (int i = 0, n = 1; i < text.length(); n++) {
            int eol = text.indexOf('\n', i);
            String line = eol < 0 ? text.substring(i) : text.substring(i, eol);
            urls(b, from, line, n);
            if (eol < 0) break;
            i = eol + 1;
        }
    }

    /** {@link #constants} of a text, collected on the first lookup. */
    private static final class LazyConstants extends AbstractMap<String, String> {
        private final String text;
        private @Nullable Map<String, String> found;

        LazyConstants(String text) {
            this.text = text;
        }

        @Override
        public Set<Map.Entry<String, String>> entrySet() {
            if (found == null) found = constants(text);
            return found.entrySet();
        }

        @Override
        public @Nullable String get(Object key) {
            if (found == null) found = constants(text);
            return found.get(key);
        }
    }

    /**
     * The string constants {@code text} assigns exactly once, by name: a literal, a template, or a
     * helper called with one literal ({@code getEndpointFor('api/labels')}), each read as a path
     * ({@code /api/labels}); a name assigned twice, or to anything else, is not one.
     */
    static Map<String, String> constants(String text) {
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> value = new HashMap<>();
        Matcher a = ASSIGNMENT.matcher(text);
        while (a.find()) {
            String name = a.group(1);
            count.merge(name, 1, Integer::sum);
            int[] rhs = argument(text, a.end());
            String v =
                    rhs == null ? null : constant(text.substring(rhs[0], rhs[1]).strip());
            if (v != null) value.put(name, v);
        }
        Map<String, String> out = new HashMap<>();
        value.forEach((name, v) -> {
            if (count.getOrDefault(name, 0) == 1) out.put(name, v);
        });
        return out;
    }

    /** The path a constant's right-hand side holds; null when it holds none. */
    private static @Nullable String constant(String rhs) {
        String v = null;
        if (quoted(rhs)) {
            v = rhs.substring(1, rhs.length() - 1);
            if (rhs.charAt(0) == '`' && v.startsWith("${")) {
                // A base URL first (`${serverApiUrl}api/labels`): the path is what follows it.
                int close = v.indexOf('}');
                v = close < 0 ? null : v.substring(close + 1);
            }
        } else {
            Matcher h = HELPER.matcher(rhs);
            if (h.matches()) v = h.group(2);
        }
        if (v == null) return null;
        v = v.replaceFirst("^https?://[^/]*", "");
        if (v.startsWith("/")) return v.length() > 1 ? v : null;
        return PATHLIKE.matcher(v).matches() && !v.contains("${") ? "/" + v : null;
    }

    /**
     * The path the argument {@code expr} reads as: a literal or template starting with {@code /}, or
     * a {@code +} chain or template whose head is a constant of the file or a variable named as a
     * base URL ({@code `${serverApiUrl}api/account`}); its variables as {@code
     * {}}, a glob's tail ({@code +(?*|)}) dropped and a {@code *} segment read as a variable. Null
     * when its head is neither.
     */
    static @Nullable String path(String expr, Map<String, String> constants) {
        StringBuilder out = new StringBuilder();
        List<String> terms = terms(expr.strip());
        for (int i = 0; i < terms.size(); i++) {
            String t = terms.get(i).strip();
            String part;
            if (quoted(t)) {
                part = t.substring(1, t.length() - 1);
                if (t.charAt(0) == '`' && part.startsWith("${")) {
                    int close = part.indexOf('}');
                    Matcher id = IDENT.matcher(
                            close < 0 ? "" : part.substring(2, close).strip());
                    String head = id.matches() ? constants.get(id.group(1)) : null;
                    String rest = part.substring(close + 1);
                    if (head == null
                            && i == 0
                            && id.matches()
                            && BASE_URL.matcher(id.group(1)).find()) {
                        // A base URL the file does not assign (`${serverApiUrl}api/account`): the path is what follows
                        // it.
                        head = "";
                        if (!rest.startsWith("/")) rest = "/" + rest;
                    }
                    if (head == null && i == 0) return null;
                    part = (head == null ? "{}" : head) + rest;
                }
            } else {
                Matcher id = IDENT.matcher(t);
                String known = id.matches() ? constants.get(id.group(1)) : null;
                if (known == null && i == 0) return null;
                part = known == null ? "{}" : known;
            }
            out.append(part);
        }
        String p = out.toString();
        if (!p.startsWith("/") || p.contains("\n")) return null;
        int glob = p.indexOf("+(");
        if (glob >= 0) p = p.substring(0, glob);
        p = variables(p);
        List<String> segments = new ArrayList<>();
        for (String s : p.split("/", -1)) segments.add(s.equals("*") || s.equals("**") ? "{}" : s);
        p = String.join("/", segments);
        return p.length() > 1 && !p.contains(" ") ? p : null;
    }

    private static boolean quoted(String t) {
        if (t.length() < 2) return false;
        char q = t.charAt(0);
        return (q == '\'' || q == '"' || q == '`') && t.charAt(t.length() - 1) == q && end(t, 0) == t.length() - 1;
    }

    /** {@code expr} split at its top-level {@code +}. */
    private static List<String> terms(String expr) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int e = end(expr, i);
                if (e < 0) return List.of(expr);
                i = e;
            } else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == '+' && depth == 0) {
                out.add(expr.substring(from, i));
                from = i + 1;
            }
        }
        out.add(expr.substring(from));
        return out;
    }

    /**
     * The bounds of the argument starting at {@code from}: up to the next top-level {@code ,}, {@code
     * )}, {@code }}, {@code ;} or line end outside brackets; null for an empty one.
     */
    static int @Nullable [] argument(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        int start = i;
        int depth = 0;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int e = end(text, i);
                if (e < 0) return null;
                i = e;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break;
                depth--;
            } else if (depth == 0 && (c == ',' || c == ';' || c == '\n')) {
                break;
            }
        }
        int end = i;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return end > start ? new int[] {start, end} : null;
    }

    /** The offset of the quote closing the string opened at {@code open}; a template's {@code ${...}} skipped; -1 when unclosed. */
    private static int end(String text, int open) {
        char q = text.charAt(open);
        for (int i = open + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == q) {
                return i;
            } else if (c == '\n' && q != '`') {
                return -1;
            } else if (q == '`' && c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int depth = 0;
                for (i++; i < text.length(); i++) {
                    if (text.charAt(i) == '{') depth++;
                    else if (text.charAt(i) == '}' && --depth == 0) break;
                }
            }
        }
        return -1;
    }

    /** The {@code {} still open before {@code at}; -1 for none. */
    private static int enclosing(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}') depth++;
            else if (c == '{' && depth-- == 0) return i;
        }
        return -1;
    }

    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** The method a command line asks for: {@code -X}, else POST for curl with a body, GET for curl or wget, else any. */
    static String method(String line) {
        Matcher m = METHOD_FLAG.matcher(line);
        if (m.find()) return m.group(1).toUpperCase(Locale.ROOT);
        boolean curl = line.contains("curl ");
        if (curl && BODY_FLAG.matcher(line).find()) return "POST";
        return curl || line.contains("wget ") ? "GET" : "*";
    }

    /** {@code ${id}} and {@code $ID} as a path variable, and a trailing query or fragment dropped. */
    static String variables(String path) {
        return VARIABLE.matcher(path).replaceAll("{}");
    }
}
