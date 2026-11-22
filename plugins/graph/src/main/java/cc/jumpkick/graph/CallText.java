// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static cc.jumpkick.graph.JavaText.before;

import cc.jumpkick.graph.MoveMember.FileEdit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reading calls in Java source whose comments and literals are blanked: brackets, arguments,
 * receivers, type arguments and the class bodies around an offset.
 */
final class CallText {

    private CallText() {}

    /** The header of a named class body: a nested one's {@code this} is not its enclosing instance. */
    private static final Pattern CLASS = Pattern.compile("(?<![\\w$.])(?:class|interface|enum|record)\\s+[A-Za-z_$]");

    private static final Pattern EXTENDS = Pattern.compile("\\bextends\\b");

    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\b");

    /** {@code text} with every comment and literal blanked to spaces, lines kept: code to search for words and brackets. */
    static String code(String text) {
        StringBuilder b = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            int past = JavaText.past(text, i);
            if (past == i) {
                i++;
                continue;
            }
            for (int k = i; k < past; k++) {
                if (b.charAt(k) != '\n') b.setCharAt(k, ' ');
            }
            i = past;
        }
        return b.toString();
    }

    /** The offset of the bracket closing the one at {@code open} in blanked {@code code}, or -1. */
    static int closing(String code, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /** The bracket opening the one at {@code close}, or -1. */
    private static int opening(String code, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')' || c == ']' || c == '}') depth++;
            else if (c == '(' || c == '[' || c == '{') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * The items between the parentheses at {@code open} and {@code close} of blanked {@code code},
     * each {@code [start, end)} trimmed in {@code text}; {@code angles} nests every {@code <>}, for a
     * declaration's parameter types, else only type arguments do.
     */
    static List<int[]> split(String text, String code, int open, int close, boolean angles) {
        List<int[]> out = new ArrayList<>();
        if (text.substring(open + 1, close).isBlank()) return out;
        int depth = 0;
        int angle = 0;
        int from = open + 1;
        for (int i = open + 1; i <= close; i++) {
            char c = i == close ? ',' : code.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == '<' && (angles || angle > 0 || opensTypeArguments(code, i))) angle++;
            else if (c == '>' && angle > 0) angle--;
            else if (c == ',' && depth == 0 && angle == 0) {
                int s = from;
                int e = i;
                while (s < e && Character.isWhitespace(text.charAt(s))) s++;
                while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
                out.add(new int[] {s, e});
                from = i + 1;
            }
        }
        return out;
    }

    /** The first non-blank offset at or after {@code offset}. */
    static int after(String text, int offset) {
        int i = offset;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /** The start of the {@code this} token ending right before the dot at {@code dot}, unless something qualifies it; else -1. */
    static int self(String code, int dot) {
        int e = before(code, dot);
        if (e < 3 || !code.startsWith("this", e - 3)) return -1;
        int s = e - 3;
        if (s > 0 && Character.isJavaIdentifierPart(code.charAt(s - 1))) return -1;
        int k = before(code, s);
        return k >= 0 && code.charAt(k) == '.' ? -1 : s;
    }

    /**
     * The start of the receiver before the dot at {@code dot}: a chain of names, calls, array reads,
     * parenthesized expressions and type arguments, with a {@code new} before it; -1 when it cannot
     * be read.
     */
    static int receiverStart(String code, int dot) {
        int i = before(code, dot);
        while (i >= 0) {
            char c = code.charAt(i);
            int unit;
            if (c == ')' || c == ']') {
                int o = opening(code, i);
                if (o < 0) return -1;
                int k = before(code, o);
                if (c == ')' && k > 0 && code.charAt(k) == '>' && code.charAt(k - 1) != '-') {
                    int lt = angleOpen(code, k);
                    int made = lt < 0 ? -1 : created(code, before(code, lt));
                    return made < 0 ? -1 : outer(code, made);
                }
                if (k >= 0 && (Character.isJavaIdentifierPart(code.charAt(k)) || c == ']' && code.charAt(k) == ')')) {
                    i = k;
                    continue;
                }
                unit = o;
            } else if (Character.isJavaIdentifierPart(c)) {
                unit = i;
                while (unit > 0 && Character.isJavaIdentifierPart(code.charAt(unit - 1))) unit--;
            } else {
                return -1;
            }
            int k = before(code, unit);
            if (k >= 0 && code.charAt(k) == '.') {
                i = before(code, k);
                continue;
            }
            if (k >= 0 && code.charAt(k) == '>' && typeArguments(code, k)) {
                i = before(code, before(code, angleOpen(code, k)));
                continue;
            }
            if (k >= 2
                    && code.startsWith("new", k - 2)
                    && (k < 3 || !Character.isJavaIdentifierPart(code.charAt(k - 3)))) {
                return outer(code, k - 2);
            }
            return unit;
        }
        return -1;
    }

    /**
     * The start of the receiver of the {@code new} at {@code made}: the outer instance of {@code
     * order.new Line()}, else the {@code new} itself; -1 when that instance cannot be read.
     */
    private static int outer(String code, int made) {
        int k = before(code, made);
        return k >= 0 && code.charAt(k) == '.' ? receiverStart(code, k) : made;
    }

    /** Whether the {@code >} at {@code gt} closes the explicit type arguments of a call, {@code a.<T>b()}. */
    static boolean typeArguments(String code, int gt) {
        if (gt > 0 && code.charAt(gt - 1) == '-') return false;
        int lt = angleOpen(code, gt);
        int dot = lt < 0 ? -1 : before(code, lt);
        return dot >= 0 && code.charAt(dot) == '.';
    }

    /**
     * Whether the {@code <} at {@code lt} opens type arguments: after a dot, {@code a.<T>b()}, or
     * after the type a {@code new} names, {@code new Box<K, V>()}; else it compares.
     */
    private static boolean opensTypeArguments(String code, int lt) {
        int k = before(code, lt);
        return k >= 0 && (code.charAt(k) == '.' || created(code, k) >= 0);
    }

    /** The {@code <} matching the {@code >} at {@code gt} over what type arguments can hold, or -1. */
    private static int angleOpen(String code, int gt) {
        int depth = 0;
        for (int i = gt; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '>') depth++;
            else if (c == '<') {
                if (--depth == 0) return i;
            } else if (!Character.isJavaIdentifierPart(c) && !Character.isWhitespace(c) && ".,?[]@".indexOf(c) < 0) {
                return -1;
            }
        }
        return -1;
    }

    /** The start of the {@code new} before the type name that ends at {@code end}, or -1 when none is there. */
    private static int created(String code, int end) {
        int i = end;
        while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) {
            while (i > 0 && Character.isJavaIdentifierPart(code.charAt(i - 1))) i--;
            int k = before(code, i);
            if (k >= 0 && code.charAt(k) == '.') {
                i = before(code, k);
                continue;
            }
            boolean made = k >= 2
                    && code.startsWith("new", k - 2)
                    && (k < 3 || !Character.isJavaIdentifierPart(code.charAt(k - 3)));
            return made ? k - 2 : -1;
        }
        return -1;
    }

    /**
     * The class the class body at {@code brace} extends, as javac's index names it: {@code null} when
     * it extends none, empty when the index does not say.
     */
    static @Nullable String supertype(FileEdit f, String code, int brace) {
        int from;
        int to;
        int k = before(code, brace);
        int n = k >= 0 && code.charAt(k) == ')' ? before(code, opening(code, k)) : -1;
        if (n > 0 && code.charAt(n) == '>') n = before(code, angleOpen(code, n));
        int made = n < 0 ? -1 : created(code, n);
        if (made >= 0) {
            from = made;
            to = n + 1;
        } else {
            int head = statementStart(code, brace);
            String header = angles(code.substring(head, brace));
            Matcher ext = EXTENDS.matcher(header);
            if (!ext.find()) return null;
            from = head + ext.end();
            Matcher impl = IMPLEMENTS.matcher(header);
            to = impl.find(ext.end()) ? head + impl.start() : brace;
        }
        String last = "";
        for (ReferenceIndex.Span s : f.spans) {
            if (s.kind() != 'd' && s.symbol().startsWith("T ") && s.start() >= from && s.end() <= to) {
                last = s.symbol().substring(2);
            }
        }
        return last;
    }

    /** {@code text} with every {@code <...>}, nested ones included, blanked to spaces. */
    private static String angles(String text) {
        StringBuilder b = new StringBuilder(text);
        int depth = 0;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (c == '<') depth++;
            if (depth > 0) b.setCharAt(i, ' ');
            if (c == '>' && depth > 0) depth--;
        }
        return b.toString();
    }

    /** Whether the brace at {@code brace} opens a class body, a named class's or an anonymous one's. */
    static boolean classBody(String code, int brace) {
        int k = before(code, brace);
        if (k >= 0 && code.charAt(k) == ')') {
            int o = opening(code, k);
            int n = o < 0 ? -1 : before(code, o);
            if (n > 0 && code.charAt(n) == '>') {
                int lt = angleOpen(code, n);
                n = lt < 0 ? -1 : before(code, lt);
            }
            if (n >= 0 && created(code, n) >= 0) return true;
        }
        return CLASS.matcher(code.substring(statementStart(code, brace), brace)).find();
    }

    /** The offset just past the semicolon or brace before {@code at}. */
    static int statementStart(String code, int at) {
        int i = at - 1;
        while (i >= 0 && ";{}".indexOf(code.charAt(i)) < 0) i--;
        return i + 1;
    }
}
