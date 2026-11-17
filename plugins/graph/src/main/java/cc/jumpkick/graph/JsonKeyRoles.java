// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an object literal in a C-family text is to the JSON a request sends or receives ({@link
 * JsonReaders.Role}): the body a request sends, a value compared with one, or anything else; and
 * where the request a body goes with is written. Read on a text with comments and literals blanked.
 */
final class JsonKeyRoles {

    private JsonKeyRoles() {}

    private static final Pattern BODY_KEY = Pattern.compile("(?<![\\w$.])(?:body|data|json)\\s*:\\s*$");
    private static final Pattern REQUEST_CALLEE = Pattern.compile(
            "(?:\\.(?:get|post|put|delete|patch|head|options)\\s*(?:<[^>()]*>)?|\\bfetch|\\bcy\\.(?:request|intercept)"
                    + "|\\b[A-Za-z_$][\\w$]*[Rr]equest)\\s*$");
    private static final Pattern SENDS = Pattern.compile("\\.(?:post|put|patch)\\s*(?:<[^>()]*>)?\\s*$");
    private static final Pattern COMPARES = Pattern.compile(
            "(?:\\.|\\b)(?:toEqual|toStrictEqual|toMatchObject|equal|eql|equals|deepEqual|deepStrictEqual|include|contain|containSubset)\\s*$");
    private static final Pattern SENDER = Pattern.compile("(?:\\bcy\\.request|\\b[A-Za-z_$][\\w$]*[Rr]equest)\\s*$");
    private static final Pattern STRINGIFY = Pattern.compile("\\bJSON\\s*\\.\\s*stringify\\s*$");
    private static final Pattern URL_IN_OBJECT = Pattern.compile("(?<![\\w$.])url\\s*:");
    private static final Pattern ASSIGNED =
            Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*(?::[^=;{}()]*)?=\\s*$");
    private static final Pattern NAME_AS_BODY = Pattern.compile(
            "(?<![\\w$.])(?:body|data|json)\\s*:\\s*(?:JSON\\s*\\.\\s*stringify\\s*\\(\\s*)?([A-Za-z_$][\\w$]*)\\b(?!\\s*[.(\\[])"
                    + "|\\.(?:post|put|patch)\\s*(?:<[^>()]*>)?\\s*\\([^,()]*(?:\\([^()]*\\))?[^,()]*,\\s*([A-Za-z_$][\\w$]*)\\s*[,)]");

    /** The names a script sends as a request's body ({@code body: sample}, {@code http.post(url, sample)}), with where. */
    static Map<String, Integer> sentNames(String masked) {
        Map<String, Integer> out = new HashMap<>();
        Matcher m = NAME_AS_BODY.matcher(masked);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            out.putIfAbsent(name, m.group(1) != null ? m.start(1) : m.start(2));
        }
        return out;
    }

    /**
     * What the object literal holding the key at {@code at} is: sent as a request's body (the value
     * of an options object's {@code body}, {@code data} or {@code json}, possibly through {@code
     * JSON.stringify}; the body argument of a {@code post}, {@code put} or {@code patch}; a constant
     * the script sends so), compared with a value, or other. A literal nested in another takes the
     * outer one's role. For a body, {@code tie[0]} is set to the offset the request it goes with is
     * written at.
     */
    static JsonReaders.Role role(String masked, int at, Map<String, Integer> sent, int[] tie) {
        int o = enclosing(masked, at, '{');
        for (int guard = 0; o >= 0 && guard < 50; guard++) {
            int p = o - 1;
            while (p >= 0 && Character.isWhitespace(masked.charAt(p))) p--;
            if (p < 0) return JsonReaders.Role.OTHER;
            char c = masked.charAt(p);
            String before = masked.substring(Math.max(0, p - 80), p + 1);
            if (c == ':') {
                if (BODY_KEY.matcher(before).find()) {
                    tie[0] = request(masked, o);
                    return JsonReaders.Role.BODY;
                }
                o = enclosing(masked, o, '{');
            } else if (c == '[') {
                o = p;
            } else if (c == '(' || c == ',') {
                int paren = c == '(' ? p : opener(masked, o);
                if (paren < 0) return JsonReaders.Role.OTHER;
                if (masked.charAt(paren) == '[') {
                    o = paren;
                    continue;
                }
                if (masked.charAt(paren) != '(') return JsonReaders.Role.OTHER;
                String callee = masked.substring(Math.max(0, paren - 80), paren);
                Matcher s = STRINGIFY.matcher(callee);
                if (s.find()) {
                    o = Math.max(0, paren - 80) + s.start();
                    continue;
                }
                if (COMPARES.matcher(callee).find()) return JsonReaders.Role.COMPARED;
                boolean first = masked.substring(paren + 1, o).isBlank();
                if (!first
                        && (SENDS.matcher(callee).find()
                                || SENDER.matcher(callee).find())) {
                    tie[0] = calleeStart(masked, paren);
                    return JsonReaders.Role.BODY;
                }
                return JsonReaders.Role.OTHER;
            } else if (c == '=' && (p == 0 || "=!<>".indexOf(masked.charAt(p - 1)) < 0)) {
                Matcher a = ASSIGNED.matcher(before);
                if (!a.find()) return JsonReaders.Role.OTHER;
                Integer where = sent.get(a.group(1));
                if (where == null) return JsonReaders.Role.OTHER;
                tie[0] = request(masked, where);
                return JsonReaders.Role.BODY;
            } else {
                return JsonReaders.Role.OTHER;
            }
        }
        return JsonReaders.Role.OTHER;
    }

    /**
     * The offset a request holding {@code offset} is written at, as {@link
     * cc.jumpkick.codegraph.extract} reads requests: the {@code url} key of the options object it
     * sits in, else the call it is an argument of; -1 for none before a function's body.
     */
    private static int request(String masked, int offset) {
        int i = offset;
        for (int guard = 0; guard < 20; guard++) {
            int brace = enclosing(masked, i, '{');
            int paren = enclosing(masked, i, '(');
            int o = Math.max(brace, paren);
            if (o < 0) return -1;
            if (masked.charAt(o) == '{') {
                if (JsonReaders.functionBrace(masked, o)) return -1;
                int close = JsonReaders.closing(masked, o, '{', '}');
                String object = JsonReaders.depthOne(masked, o, close < 0 ? masked.length() - 1 : close);
                Matcher u = URL_IN_OBJECT.matcher(object);
                if (u.find()) return o + 1 + u.start();
            } else if (REQUEST_CALLEE
                    .matcher(masked.substring(Math.max(0, o - 80), o))
                    .find()) {
                return calleeStart(masked, o);
            }
            i = o;
        }
        return -1;
    }

    /** Where the callee of the call opening at {@code paren} starts: the {@code .} of {@code http.post}, the {@code f} of {@code fetch}. */
    private static int calleeStart(String masked, int paren) {
        int from = Math.max(0, paren - 80);
        Matcher m = REQUEST_CALLEE.matcher(masked.substring(from, paren));
        return m.find() ? from + m.start() : paren;
    }

    /** The nearest bracket of any kind still open before {@code at}; -1 for none. */
    private static int opener(String masked, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')' || c == ']' || c == '}') depth++;
            else if ((c == '(' || c == '[' || c == '{') && depth-- == 0) return i;
        }
        return -1;
    }

    /** The bracket {@code open} still open before {@code at}; -1 for none. */
    private static int enclosing(String masked, int at, char open) {
        char close = open == '{' ? '}' : open == '(' ? ')' : ']';
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == close) depth++;
            else if (c == open && depth-- == 0) return i;
        }
        return -1;
    }
}
