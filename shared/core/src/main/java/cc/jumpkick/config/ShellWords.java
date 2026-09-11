// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command string the way a POSIX shell tokenises it — whitespace separates, single and
 * double quotes group — with one deliberate difference: a backslash is literal unless it sits
 * right before a quote, a space, or another backslash, so {@code C:\tools\node.exe} survives
 * unquoted. Inside double quotes only {@code \"} and {@code \\} escape; inside single quotes nothing
 * does. Nothing is expanded and no shell is ever run.
 */
public final class ShellWords {

    private ShellWords() {}

    public static List<String> split(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            char next = i + 1 < command.length() ? command.charAt(i + 1) : 0;
            if (quote == '\'') {
                if (c == '\'') quote = 0;
                else word.append(c);
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && (next == '"' || next == '\\')) {
                    word.append(next);
                    i++;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && escapes(next)) {
                word.append(next);
                i++;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("unbalanced quote in `" + command + "`");
        if (inWord) out.add(word.toString());
        return List.copyOf(out);
    }

    private static boolean escapes(char next) {
        return next == '"' || next == '\'' || next == '\\' || (next != 0 && Character.isWhitespace(next));
    }
}
