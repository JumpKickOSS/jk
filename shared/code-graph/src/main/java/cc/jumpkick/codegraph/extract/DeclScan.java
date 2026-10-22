// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Decl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The parts of a JVM declaration the declaration regexes do not capture, read from masked source
 * (comments and literals blanked): the modifiers and annotations before it, and where its body ends.
 */
final class DeclScan {

    private DeclScan() {}

    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*([A-Za-z_$][\\w$.]*)");

    /** The modifier words and annotation simple names written before a declaration, and where they start. */
    record Preamble(List<String> modifiers, List<String> annotations, int start) {}

    /**
     * What precedes the declaration at {@code at}, back to the previous {@code ;}, {@code {} or
     * {@code }} outside parentheses.
     */
    static Preamble preamble(String code, int at) {
        int depth = 0;
        int start = at;
        while (start > 0) {
            char c = code.charAt(start - 1);
            if (c == ')') depth++;
            else if (c == '(') depth = Math.max(0, depth - 1);
            else if (depth == 0 && (c == ';' || c == '{' || c == '}')) break;
            start--;
        }
        String text = code.substring(start, at);
        List<String> annotations = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        Matcher a = ANNOTATION.matcher(text);
        int last = 0;
        while (a.find()) {
            String name = a.group(1);
            if (!name.equals("interface")) annotations.add(name.substring(name.lastIndexOf('.') + 1));
            plain.append(text, last, a.start());
            int end = a.end();
            int open = skipBlank(text, end);
            if (open < text.length() && text.charAt(open) == '(') {
                int closed = closeParen(text, open);
                end = closed < 0 ? text.length() : closed + 1;
            }
            last = Math.min(end, text.length());
        }
        plain.append(text.substring(last));
        List<String> modifiers = new ArrayList<>();
        Matcher w = WORD.matcher(plain);
        while (w.find()) modifiers.add(w.group());
        return new Preamble(modifiers, annotations, start);
    }

    private static final Pattern INLINE_TAG = Pattern.compile("\\{@(?:code|link|linkplain|literal|value)\\s+([^}]*)}");
    private static final Pattern HTML = Pattern.compile("</?[a-zA-Z][^>]*>");

    /**
     * The first sentence of the doc comment ({@code /**}) between {@code from} and {@code to} in the
     * original {@code text}, inline tags reduced to their text; {@code null} when there is none.
     */
    static @Nullable String docSummary(String text, String masked, int from, int to) {
        int open = text.lastIndexOf("/**", to);
        if (open < from || open >= to || masked.charAt(open) != ' ') return null;
        int close = text.indexOf("*/", open + 3);
        if (close < 0 || close > to) return null;
        StringBuilder body = new StringBuilder();
        for (String line : text.substring(open + 3, close).split("\n")) {
            String l = line.strip();
            while (l.startsWith("*")) l = l.substring(1).strip();
            if (l.startsWith("@")) break;
            if (l.isEmpty()) {
                if (!body.isEmpty()) break;
                continue;
            }
            if (!body.isEmpty()) body.append(' ');
            body.append(l);
        }
        String s = INLINE_TAG
                .matcher(body)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1).strip()));
        s = HTML.matcher(s).replaceAll("").replaceAll("\\s+", " ").strip();
        int stop = sentenceEnd(s);
        if (stop > 0) s = s.substring(0, stop + 1);
        if (s.length() > 160) s = s.substring(0, 157).strip() + "...";
        return s.isEmpty() ? null : s;
    }

    /** The offset of the period ending the first sentence, {@code -1} when it runs to the end. */
    private static int sentenceEnd(String s) {
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '.' && Character.isWhitespace(s.charAt(i + 1))) return i;
        }
        return s.endsWith(".") ? s.length() - 1 : -1;
    }

    /** {@code kind}, {@code vis}, {@code end} and {@code ann} for a declaration. */
    static Map<String, Object> facts(String kind, String vis, int endLine, List<String> annotations) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(Decl.KIND, kind);
        out.put(Decl.VIS, vis);
        if (endLine > 0) out.put(Decl.END, endLine);
        if (!annotations.isEmpty()) out.put(Decl.ANN, List.copyOf(new TreeSet<>(annotations)));
        return out;
    }

    /**
     * The offset a member's declaration ends at, given the {@code (} that opens its parameters: the
     * closing brace of a block body, the end of a Kotlin or Groovy expression body, or the {@code ;}
     * of an abstract one. {@code -1} when the parameters never close.
     */
    static int memberEnd(String code, int[] close, int paren, boolean expressionBodies) {
        int p = closeParen(code, paren);
        if (p < 0 || p >= code.length()) return -1;
        int angle = 0;
        for (int i = p + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '<') angle++;
            else if (c == '>' && angle > 0) angle--;
            else if (c == '{') return close[i];
            else if (c == ';' || c == '}') return i;
            else if (c == '=' && angle == 0 && expressionBodies && next(code, i + 1) != '=') {
                return expressionEnd(code, i + 1);
            } else if (c == '\n' && expressionBodies && angle == 0) {
                char n = next(code, i + 1);
                if (n != '{'
                        && n != '='
                        && n != ':'
                        && n != '.'
                        && !code.startsWith("where", skipBlank(code, i + 1))
                        && !code.startsWith("throws", skipBlank(code, i + 1))) {
                    return i;
                }
            }
        }
        return code.length();
    }

    /** The end of an expression starting at {@code from}: a line break outside brackets that continues nothing. */
    static int expressionEnd(String code, int from) {
        int depth = 0;
        int i = from;
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) i++;
        for (; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) return i;
                depth--;
            } else if (c == ';' && depth == 0) return i;
            else if (c == '\n' && depth == 0) {
                char n = next(code, i + 1);
                char p = previous(code, i);
                if (!continues(n) && !continues(p) && p != '=' && p != '(' && p != ',') return i;
            }
        }
        return Math.max(from, code.length() - 1);
    }

    private static boolean continues(char c) {
        return c == '.' || c == '?' || c == ':' || c == '+' || c == '-' || c == '*' || c == '/' || c == '&' || c == '|';
    }

    /** The offset of the {@code )} matching the {@code (} at {@code open}, {@code -1} when unclosed. */
    static int closeParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int skipBlank(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    private static char next(String code, int from) {
        int i = from;
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) i++;
        return i < code.length() ? code.charAt(i) : '\0';
    }

    private static char previous(String code, int before) {
        int i = before - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) i--;
        return i >= 0 ? code.charAt(i) : '\0';
    }
}
