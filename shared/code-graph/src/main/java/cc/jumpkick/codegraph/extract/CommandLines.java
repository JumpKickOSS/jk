// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Command lines that pass flags, as written in a script, a workflow step, a Dockerfile's {@code RUN},
 * {@code CMD} or {@code ENTRYPOINT}, a Makefile recipe, a {@code package.json} script or a fenced
 * block in a document: each command of the line ({@code a && b | c}) with at least one flag is a
 * {@link Mention.Kind#CLI} mention, its words space-joined, the program by its file name ({@code
 * ./bin/shop} is {@code shop}). After the first flag only flags are kept, each without its {@code
 * =value}: a value passed on a command line may be a secret, and no value enters the graph. Only a
 * command whose program the graph defines is a mention (the programs the compiled classes define,
 * which {@link Extractor#extract(String, String, Set)} passes in): with none defined no line is read at
 * all, and otherwise only a line naming one of them is split, so a line calling another tool adds
 * nothing.
 */
final class CommandLines {

    private CommandLines() {}

    /** The most words of one command kept: its program, subcommands and the flags after them. */
    static final int MAX_WORDS = 24;

    private static final Pattern SEPARATOR = Pattern.compile("&&|\\|\\||[;|&]");
    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=\\S*");
    private static final Pattern PREFIX =
            Pattern.compile("^\\s*(?:-\\s+)?(?:(?:run|script|command|entrypoint|cmd)\\s*:\\s*[|>]?[-+]?\\s*)?");
    private static final Pattern INSTRUCTION = Pattern.compile("(?i)^\\s*(?:RUN|CMD|ENTRYPOINT)\\s+");
    private static final Pattern FLAG = Pattern.compile("-{1,2}[A-Za-z][\\w-]*(?:=.*)?");
    private static final Set<String> WRAPPERS = Set.of("sudo", "exec", "time", "env", "nohup", "command", "$");

    /** The commands on {@code line} of one of {@code programs} that pass a flag, as mentions from {@code from}. */
    static void mentions(FragmentBuilder b, Set<String> programs, String from, String line, int n) {
        if (programs.isEmpty() || line.indexOf('-') < 0 || !namesOne(line, programs)) return;
        String text = INSTRUCTION.matcher(line).replaceFirst("");
        text = PREFIX.matcher(text).replaceFirst("");
        if (text.startsWith("[")) text = text.replaceAll("[\\[\\]\",]", " ");
        for (String command : SEPARATOR.split(text)) {
            List<String> words = words(command);
            if (words.size() >= 2
                    && programs.contains(words.getFirst())
                    && words.stream().anyMatch(w -> FLAG.matcher(w).matches())) {
                b.mention(from, Mention.Kind.CLI, String.join(" ", words), n);
            }
        }
    }

    private static boolean namesOne(String line, Set<String> programs) {
        for (String p : programs) {
            if (line.contains(p)) return true;
        }
        return false;
    }

    /**
     * One command's words, quotes dropped, leading assignments and wrappers ({@code sudo}) skipped,
     * and after the first flag its flags alone, values dropped, and {@code --}, which ends them.
     */
    private static List<String> words(String command) {
        List<String> out = new ArrayList<>();
        boolean flags = false;
        for (String raw : command.strip().split("\\s+")) {
            String w = raw.replace("\"", "").replace("'", "");
            if (w.isEmpty() || w.equals("\\")) continue;
            if (w.startsWith("#")) break;
            if (out.isEmpty() && (WRAPPERS.contains(w) || ASSIGNMENT.matcher(w).matches())) continue;
            if (out.isEmpty()) w = w.substring(w.lastIndexOf('/') + 1);
            boolean flag = FLAG.matcher(w).matches();
            if (flag) {
                flags = true;
                int eq = w.indexOf('=');
                if (eq > 0) w = w.substring(0, eq);
            } else if (flags && !w.equals("-" + "-")) {
                continue;
            }
            out.add(w);
            if (out.size() == MAX_WORDS) break;
        }
        return out;
    }
}
