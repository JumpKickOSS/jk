// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading Java source text where javac's reference index stops: a declaration's modifiers and
 * extent, what stands before a name, and lines by number and offset. Comments and string, character
 * and text-block literals are stepped over where a bracket or a {@code ;} is counted.
 */
final class JavaText {

    private JavaText() {}

    private static final Pattern WORD = Pattern.compile("@?[A-Za-z_$][\\w$]*");

    /** Whether the name at {@code offset} follows a {@code .}, {@code ::}, {@code #} or type arguments: something qualifies it. */
    static boolean afterQualifier(String text, int offset) {
        int k = before(text, offset);
        if (k < 0) return false;
        char c = text.charAt(k);
        return c == '.'
                || c == ':'
                || c == '#'
                || c == '>' && k > 0 && text.lastIndexOf(".<", k) > text.lastIndexOf('\n', k);
    }

    /** The index of the last non-blank character before {@code offset}, or -1. */
    static int before(String text, int offset) {
        int k = offset - 1;
        while (k >= 0 && Character.isWhitespace(text.charAt(k))) k--;
        return k;
    }

    /** A line of modifiers or annotations alone, above the line that names the declaration. */
    static boolean modifierLine(String line) {
        String s = line.strip();
        if (s.isEmpty() || s.startsWith("@")) return !s.isEmpty();
        return s.matches(
                "(?:(?:public|protected|private|static|final|synchronized|native|strictfp|transient|volatile)\\s*)+");
    }

    /** The offset of the {@code ;} that ends the statement holding {@code offset}, for a field's declarators. */
    static int statementOf(String text, int offset) {
        int i = offset;
        while (i < text.length()) {
            int past = past(text, i);
            if (past != i) {
                i = past;
                continue;
            }
            if (text.charAt(i) == ';') return i;
            i++;
        }
        return text.length();
    }

    /** The modifiers before the declared name at {@code offset}: the words since the last {@code ; { } ,} or comment. */
    static Set<String> modifiers(String text, int offset) {
        int k = offset - 1;
        while (k >= 0) {
            char c = text.charAt(k);
            if (c == ';' || c == '{' || c == '}' || c == ',' || c == '/' && k > 0 && text.charAt(k - 1) == '*') break;
            k--;
        }
        String head = text.substring(k + 1, offset);
        Set<String> out = new TreeSet<>();
        Matcher m = WORD.matcher(head.replaceAll("//[^\\n]*", ""));
        while (m.find()) {
            if (!m.group().startsWith("@")) out.add(m.group());
        }
        return out;
    }

    /** The offset of the {@code }} closing the method named at {@code name} (its {@code ;} when it has no body). */
    static int methodEnd(String text, int name) {
        int i = text.indexOf('(', name);
        int depth = 0;
        while (i >= 0 && i < text.length()) {
            int past = past(text, i);
            if (past != i) {
                i = past;
                continue;
            }
            char c = text.charAt(i);
            if (c == '(' || c == '{') depth++;
            else if (c == ')' || c == '}') {
                depth--;
                if (depth == 0 && c == '}') return i;
            } else if (c == ';' && depth == 0) return i;
            i++;
        }
        return text.length() - 1;
    }

    /** The offset after the comment or literal starting at {@code i}; {@code i} when none starts there. */
    static int past(String t, int i) {
        char c = t.charAt(i);
        if (c == '/' && i + 1 < t.length()) {
            if (t.charAt(i + 1) == '/') {
                int nl = t.indexOf('\n', i);
                return nl < 0 ? t.length() : nl;
            }
            if (t.charAt(i + 1) == '*') {
                int e = t.indexOf("*/", i + 2);
                return e < 0 ? t.length() : e + 2;
            }
        }
        if (t.startsWith("\"\"\"", i)) {
            int e = i + 3;
            while (e < t.length()) {
                if (t.charAt(e) == '\\') e += 2;
                else if (t.startsWith("\"\"\"", e)) return e + 3;
                else e++;
            }
            return t.length();
        }
        if (c == '"' || c == '\'') {
            int j = i + 1;
            while (j < t.length() && t.charAt(j) != c && t.charAt(j) != '\n') j += t.charAt(j) == '\\' ? 2 : 1;
            return Math.min(t.length(), j + 1);
        }
        return i;
    }

    static String indent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    /** The line holding {@code offset}, without its newline. */
    static String line(String text, int offset) {
        int a = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        if (offset == 0) a = 0;
        int b = text.indexOf('\n', offset);
        return text.substring(a, b < 0 ? text.length() : b);
    }

    static int column(String text, int offset) {
        return offset - (text.lastIndexOf('\n', offset - 1) + 1);
    }

    /** The offset line {@code n} (1-based) starts at; the text's length past its last line. */
    static int lineStart(String text, int n) {
        int at = 0;
        for (int i = 1; i < n; i++) {
            int nl = text.indexOf('\n', at);
            if (nl < 0) return text.length();
            at = nl + 1;
        }
        return at;
    }

    /** The offset just past line {@code n}'s newline. */
    static int lineEnd(String text, int n) {
        return lineStart(text, n + 1);
    }

    /** The 1-based line of {@code offset}. */
    static int lineNumber(String text, int offset) {
        int n = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }

    static boolean importLine(String text, int offset) {
        return line(text, offset).stripLeading().startsWith("import ");
    }

    /** The 1-based lines of {@code text} that hold {@code name} as a word outside comments. */
    static List<Integer> wordLines(String text, String name) {
        Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])");
        List<Integer> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = lines[i].strip();
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.startsWith("import "))
                continue;
            if (word.matcher(GraphUses.unquoted(lines[i])).find()) out.add(i + 1);
        }
        return out;
    }
}
