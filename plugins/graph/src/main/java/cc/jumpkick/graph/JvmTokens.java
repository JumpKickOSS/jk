// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A Java or Kotlin source as the tokens {@link GraphRename} needs: identifiers and punctuation with
 * their offsets and lines, comments set apart, every literal one placeholder token, and the
 * method bodies (each a header and its braces) by line, so a use can be placed in the body the
 * compiled classes counted it in.
 */
final class JvmTokens {

    /** Statements whose braces are a block inside a method, not a body of their own. */
    private static final Set<String> CONTROL = Set.of(
            "if",
            "else",
            "for",
            "while",
            "do",
            "switch",
            "try",
            "catch",
            "finally",
            "synchronized",
            "when",
            "static",
            "init");

    /** Words before a name that make it a call or an expression, not a declaration. */
    private static final Set<String> NOT_A_TYPE = Set.of(
            "return", "new", "throw", "else", "case", "yield", "assert", "do", "in", "is", "as", "when", "await");

    private final List<String> text = new ArrayList<>();
    private final List<Integer> start = new ArrayList<>();
    private final List<Integer> lineOf = new ArrayList<>();
    private final List<int[]> comments = new ArrayList<>();
    private final List<int[]> regions = new ArrayList<>();

    private JvmTokens() {}

    static JvmTokens of(String src) {
        JvmTokens t = new JvmTokens();
        t.lex(src);
        t.regions();
        return t;
    }

    private void lex(String s) {
        int line = 1;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                end = end < 0 ? n : end;
                comments.add(new int[] {i, end, line});
                i = end;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                comments.add(new int[] {i, end, line});
                line += newlines(s, i, end);
                i = end;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                add("\"", i, line);
                line += newlines(s, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1;
                add("\"", i, line);
                i = Math.min(n, j + 1);
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) j++;
                add(s.substring(i, j), i, line);
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.'))
                    j++;
                add("0", i, line);
                i = j;
            } else if ((c == ':' && i + 1 < n && s.charAt(i + 1) == ':')
                    || (c == '-' && i + 1 < n && s.charAt(i + 1) == '>')) {
                add(s.substring(i, i + 2), i, line);
                i += 2;
            } else {
                add(String.valueOf(c), i, line);
                i++;
            }
        }
    }

    private static int newlines(String s, int from, int to) {
        int count = 0;
        for (int k = from; k < to; k++) if (s.charAt(k) == '\n') count++;
        return count;
    }

    private void add(String token, int offset, int line) {
        text.add(token);
        start.add(offset);
        lineOf.add(line);
    }

    /** One open brace: whether it opens a body, the line its header starts on, and the outer state. */
    private record Open(boolean body, int line, int parens) {}

    private void regions() {
        Deque<Open> open = new ArrayDeque<>();
        int parens = 0;
        int header = 0;
        for (int i = 0; i < text.size(); i++) {
            switch (text.get(i)) {
                case "(" -> parens++;
                case ")" -> parens = Math.max(0, parens - 1);
                case ";" -> {
                    if (parens == 0) header = i + 1;
                }
                case "{" -> {
                    open.push(new Open(body(header, i), lineOf.get(Math.min(header, i)), parens));
                    parens = 0;
                    header = i + 1;
                }
                case "}" -> {
                    Open o = open.poll();
                    if (o != null) {
                        if (o.body()) regions.add(new int[] {o.line(), lineOf.get(i)});
                        parens = o.parens();
                    }
                    header = i + 1;
                }
                default -> {}
            }
        }
    }

    /** True when the tokens {@code [from, brace)} head a method, constructor or class-like body. */
    private boolean body(int from, int brace) {
        if (from >= brace) return false;
        String first = text.get(from);
        if (CONTROL.contains(first)
                && (!first.equals("synchronized")
                        || from + 1 < brace && text.get(from + 1).equals("("))) {
            return false;
        }
        String last = text.get(brace - 1);
        if (last.equals("->")) return false;
        boolean throwsClause = false;
        for (int k = from; k < brace; k++) {
            String t = text.get(k);
            if (t.equals("fun")) return true;
            if (t.equals("throws")) throwsClause = true;
        }
        return last.equals(")") || throwsClause;
    }

    /** The indexes of the tokens that use {@code name} as a method: called, declared or referenced. */
    List<Integer> uses(String name) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < text.size(); i++) {
            if (!text.get(i).equals(name)) continue;
            String prev = i > 0 ? text.get(i - 1) : "";
            String next = i + 1 < text.size() ? text.get(i + 1) : "";
            if (prev.equals("new")) continue;
            if (next.equals("(") || prev.equals("::")) out.add(i);
        }
        return out;
    }

    /** The arguments a call at {@code token} passes, or a declaration there takes; -1 for a method reference. */
    int arity(int token) {
        if (token + 1 >= text.size() || !text.get(token + 1).equals("(")) return -1;
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int k = token + 1; k < text.size(); k++) {
            String t = text.get(k);
            if (t.equals("(") || t.equals("[") || t.equals("{")) depth++;
            else if (t.equals(")") || t.equals("]") || t.equals("}")) {
                if (--depth == 0) return any ? commas + 1 : 0;
            } else if (depth == 1 && t.equals(",")) commas++;
            if (depth >= 1 && k > token + 1) any = true;
        }
        return -1;
    }

    /**
     * What a use at {@code token} is called through: the token before its {@code .} or {@code ::}
     * ({@code ""} when that is not a name), or {@code null} for an unqualified use.
     */
    @Nullable
    String qualifier(int token) {
        if (token == 0) return null;
        String prev = text.get(token - 1);
        if (!prev.equals(".") && !prev.equals("::")) return null;
        if (token < 2) return "";
        String q = text.get(token - 2);
        return Character.isJavaIdentifierStart(q.charAt(0)) ? q : "";
    }

    /** True when {@code name} is one of the tokens. */
    boolean has(String name) {
        return text.contains(name);
    }

    int line(int token) {
        return lineOf.get(token);
    }

    /** The innermost body holding {@code line}: its index, or -1 for none. */
    int region(int line) {
        int best = -1;
        for (int r = 0; r < regions.size(); r++) {
            int[] b = regions.get(r);
            if (b[0] <= line && line <= b[1] && (best < 0 || b[0] >= regions.get(best)[0])) best = r;
        }
        return best;
    }

    /** The first line of body {@code r}; 1 for none. */
    int regionStart(int r) {
        return r < 0 ? 1 : regions.get(r)[0];
    }

    /** True when the use at {@code token} declares the method: a type or modifier stands before it. */
    boolean declares(int token) {
        if (token == 0) return false;
        String prev = text.get(token - 1);
        if (prev.equals(">") || prev.equals("]")) return true;
        return Character.isJavaIdentifierStart(prev.charAt(0)) && !NOT_A_TYPE.contains(prev);
    }

    /** True when the declaration at {@code token} is marked {@code @Override} or Kotlin {@code override}. */
    boolean overrideMarked(int token) {
        for (int k = token - 1; k >= 0; k--) {
            String t = text.get(k);
            if (t.equals(";") || t.equals("{") || t.equals("}")) return false;
            if (t.equals("override")) return true;
            if (t.equals("Override") && k > 0 && text.get(k - 1).equals("@")) return true;
        }
        return false;
    }

    /** {@code src} with the name at each of {@code tokens} replaced by {@code to}. */
    String replace(String src, List<Integer> tokens, int length, String to) {
        StringBuilder out = new StringBuilder(src.length() + tokens.size() * Math.max(0, to.length() - length));
        int at = 0;
        for (int t : tokens.stream().sorted().toList()) {
            int s = start.get(t);
            out.append(src, at, s).append(to);
            at = s + length;
        }
        return out.append(src.substring(at)).toString();
    }

    /** A source with its doc links renamed, how many, and on which lines. */
    record DocEdit(String text, int count, List<Integer> lines) {}

    /**
     * {@code src} with every {@code Type#old} in a comment, {@code Type} one of {@code types} (or
     * qualified to end in one), renamed; and {@code #old} with no type too when {@code bare}, in a
     * file whose own {@code old} is the one renamed.
     */
    DocEdit docLinks(String src, String old, String to, Set<String> types, boolean bare) {
        Pattern link = Pattern.compile("([\\w$.]*)#" + Pattern.quote(old) + "(?![\\w$])");
        StringBuilder out = new StringBuilder();
        List<Integer> lines = new ArrayList<>();
        int at = 0;
        int count = 0;
        for (int[] c : comments) {
            Matcher m = link.matcher(src).region(c[0], c[1]);
            while (m.find()) {
                String type = m.group(1);
                String simple = type.substring(type.lastIndexOf('.') + 1);
                if (type.isEmpty() ? !bare : !types.contains(simple)) continue;
                int name = m.end() - old.length();
                out.append(src, at, name).append(to);
                at = m.end();
                count++;
                lines.add(c[2] + newlines(src, c[0], name));
            }
        }
        out.append(src.substring(at));
        return new DocEdit(out.toString(), count, lines);
    }
}
