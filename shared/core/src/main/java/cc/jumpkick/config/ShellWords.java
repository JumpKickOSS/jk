// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command string the way a POSIX shell tokenises it — whitespace separates, single and
 * double quotes group, a backslash escapes the next character outside single quotes — and does
 * nothing else: no variable expansion, no globbing, no shell is ever run.
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
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < command.length()) {
                    word.append(command.charAt(++i));
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < command.length()) {
                word.append(command.charAt(++i));
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
}
