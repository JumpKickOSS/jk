// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The text-level reading of a {@code build.gradle[.kts]} the scanner shares: comment stripping that
 * leaves string literals alone, brace-matched block extraction, and the string-literal grammar.
 */
final class GradleScriptText {

    /** String literal: {@code "foo"} or {@code 'bar'}, the quoted body in group 1 or 2. */
    static final String STR = "(?:\"([^\"\\n]*)\"|'([^'\\n]*)')";

    private GradleScriptText() {}

    /**
     * Strip {@code //} line comments and {@code /* * /} block comments while leaving string literals
     * untouched: a regex pass would eat the {@code //} inside {@code "https://example.com"}.
     */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int eol = text.indexOf('\n', i + 2);
                i = eol < 0 ? n : eol;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = copyLiteral(text, i, out);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Copy the literal opening at {@code from} through its closing quote (or end of line, which Groovy permits). */
    private static int copyLiteral(String text, int from, StringBuilder out) {
        char quote = text.charAt(from);
        out.append(quote);
        int i = from + 1;
        int n = text.length();
        while (i < n) {
            char d = text.charAt(i);
            out.append(d);
            i++;
            if (d == '\\' && i < n) {
                out.append(text.charAt(i));
                i++;
            } else if (d == quote || d == '\n') {
                break;
            }
        }
        return i;
    }

    /** The contents of the first top-level {@code name { ... }} block, by brace matching. */
    static Optional<String> extractBlock(String text, String name) {
        Pattern header = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*\\{");
        Matcher m = header.matcher(text);
        if (!m.find()) return Optional.empty();
        int open = m.end() - 1;
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return Optional.of(text.substring(open + 1, i));
            }
        }
        return Optional.empty();
    }

    /** The first literal {@code pattern} captures in groups 1/2, if any. */
    static Optional<String> firstString(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return Optional.empty();
        String s = firstNonNull(m.group(1), m.group(2));
        return s == null ? Optional.empty() : Optional.of(s);
    }

    static @Nullable String firstNonNull(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * Split {@code text} at the commas outside quotes and parentheses: the arguments of {@code
     * implementation("a:b:1", "c:d:2")} or a Groovy {@code testImplementation "a:b:1", "c:d:2"}.
     */
    static List<String> splitArguments(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }
}
