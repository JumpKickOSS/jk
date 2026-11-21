// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.args.Abbreviations;
import cc.jumpkick.model.command.Opt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the command stands in argv: the first non-option token, skipping any leading global options
 * and the value of a value-taking one ({@code -C dir}). Globals are read as the parser reads them: a
 * long name against every global, so {@code --o} is ambiguous rather than {@code --output}; a short
 * cluster letter by letter, so {@code -qC dir} ends in {@code -C}'s value; and a value is never a
 * token that looks like an option.
 */
final class CommandIndex {

    private static final Map<String, Opt> GLOBALS = byName();

    private CommandIndex() {}

    /** The command's index in {@code args}; -1 when there's none (bare {@code jk}, or {@code jk --help}). */
    static int of(List<String> args) {
        int i = 0;
        while (i < args.size()) {
            String a = args.get(i);
            if (a.equals("--")) return i + 1 < args.size() ? i + 1 : -1;
            if (!a.startsWith("-") || a.equals("-")) return i; // first positional = command
            boolean valueFollows = i + 1 < args.size() && !looksLikeOption(args.get(i + 1));
            i += valueFollows && takesNextAsValue(a) ? 2 : 1;
        }
        return -1;
    }

    /** Whether the global option token {@code a} takes the next token as its value. */
    private static boolean takesNextAsValue(String a) {
        if (a.startsWith("--")) {
            if (a.contains("=")) return false;
            Opt opt = Abbreviations.resolve(a, GLOBALS).value();
            return opt != null && opt.takesValue();
        }
        for (int c = 1; c < a.length(); c++) {
            Opt opt = GLOBALS.get("-" + a.charAt(c));
            if (opt == null) return false;
            if (opt.takesValue()) return c == a.length() - 1;
        }
        return false;
    }

    private static boolean looksLikeOption(String tok) {
        return tok.length() > 1 && tok.charAt(0) == '-';
    }

    private static Map<String, Opt> byName() {
        Map<String, Opt> out = new LinkedHashMap<>();
        for (Opt o : GlobalOptions.globalOpts()) {
            for (String n : o.allNames()) out.put(n, o);
        }
        return out;
    }
}
