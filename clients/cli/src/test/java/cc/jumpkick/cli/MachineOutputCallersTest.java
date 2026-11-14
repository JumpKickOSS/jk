// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Every place in this checkout that runs jk with {@code -O json} or {@code -O jsonl} (the VS Code
 * extension, the IntelliJ plugin, scripts, CI workflows, benches, the docs and the text jk ships to
 * agents) names a command that declares that output. A command cannot lose its declaration while a
 * caller still asks for it, and a new caller cannot ask a command that refuses it: the dispatcher
 * would answer it with a usage error, which is how {@code jk web -O jsonl} broke the VS Code
 * extension.
 */
class MachineOutputCallersTest {

    /** Where callers live, from the checkout root; every one must exist so the scan cannot go blind. */
    static final List<String> ROOTS = List.of(
            "clients/vscode/src",
            "clients/vscode/README.md",
            "clients/intellij/src/main",
            "scripts",
            ".github",
            "bench",
            "docs",
            "hosting",
            "README.md",
            "AGENTS.md",
            "CONTRIBUTING.md",
            "shared/core/src/main/resources/cc/jumpkick/docs");

    /** Callers the scan must find; a regex or root change that loses them fails here, not silently. */
    static final Set<String> KNOWN = Set.of(
            "clients/vscode/src/engine/connect.ts: jk web",
            ".github/workflows/ci.yml: jk audit",
            "scripts/ci-bootstrap.sh: jk lock",
            "scripts/heap-monorepo-measure.sh: jk engine status",
            "bench/oracle/Oracle.java: jk build",
            "bench/memory/run: jk engine status");

    private static final Pattern FORMAT = Pattern.compile("json|jsonl");
    private static final Pattern JK = Pattern.compile("(?i)\\$?\\{?(jk|jk_bin|jk_cmd)\\}?|.*/jk");
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", "target", "out", "dist", ".git", "test");

    /**
     * One token of a line, whether it was inside a string literal, and the backtick span it was in
     * ({@code -1} for none): a Markdown code span is one command line, never part of another.
     */
    record Token(String text, boolean literal, int span) {}

    /** A line that runs jk with a machine format: the command path it names, or null when none. */
    record Caller(
            String file, int line, String text, @Nullable String command, String format) {}

    @Test
    void every_caller_names_a_command_that_prints_its_format() throws IOException {
        Path root = RepoRoot.find(MachineOutputCallersTest.class);
        Map<String, CliCommand> commands = CommandModeGuardTest.allCommands();
        List<Caller> callers = scan(root, commands);

        List<String> unresolved = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        Set<String> found = new TreeSet<>();
        for (Caller c : callers) {
            String where = c.file() + ":" + c.line() + ": " + c.text().strip();
            if (c.command() == null) {
                unresolved.add(where);
                continue;
            }
            found.add(c.file() + ": " + c.command());
            CliCommand command = Objects.requireNonNull(commands.get(c.command()));
            if (!command.outputFormats().contains(c.format())) {
                refused.add(c.command() + " -O " + c.format() + "  (" + where + ")");
            }
        }
        assertThat(unresolved)
                .as("lines that pass -O json|jsonl to jk but name no registered command; spell the"
                        + " command out on the line")
                .isEmpty();
        assertThat(refused)
                .as("callers asking for a machine output the command does not declare" + " (CliCommand.machineOutput)")
                .isEmpty();
        assertThat(found).as("callers the scan must find").containsAll(KNOWN);
    }

    /**
     * Commands whose source asks for the JSON output mode without printing one, with the reason:
     * they read it only to keep a prompt or an animation away from a program.
     */
    static final Map<String, String> READS_JSON_MODE_ONLY = Map.of(
            "jk new", "asks no questions under -O json; prints no rows",
            "jk decide", "asks no questions under -O json; prints no rows",
            "jk engine stop", "skips its live region when no terminal; prints no rows");

    /** What in a command's source writes or chooses machine output. */
    private static final Pattern WRITES_JSON =
            Pattern.compile("outputIsJson|BuildPlanConsole\\.(modeFor|run)\\(|JsonlShape\\.");

    @Test
    void a_command_that_writes_json_declares_it() throws IOException {
        Path root = RepoRoot.find(MachineOutputCallersTest.class);
        Map<String, CliCommand> leaves = CommandModeGuardTest.leaves();
        List<String> undeclared = new ArrayList<>();
        for (var e : leaves.entrySet()) {
            CliCommand leaf = e.getValue();
            if (leaf.machineOutput() != MachineOutput.NONE || READS_JSON_MODE_ONLY.containsKey(e.getKey())) continue;
            String top = leaf.getClass().getName().replaceAll("\\$.*", "");
            Path source = root.resolve("clients/cli/src/main/java/" + top.replace('.', '/') + ".java");
            if (!Files.isRegularFile(source)) continue;
            if (WRITES_JSON
                    .matcher(body(Files.readString(source), leaf.getClass()))
                    .find()) {
                undeclared.add(e.getKey() + " (" + root.relativize(source) + ")");
            }
        }
        assertThat(undeclared)
                .as("commands whose source writes JSON while machineOutput() is NONE, so the dispatcher"
                        + " refuses -O json before that code can run")
                .isEmpty();
        assertThat(leaves.keySet()).as("READS_JSON_MODE_ONLY names leaves").containsAll(READS_JSON_MODE_ONLY.keySet());
    }

    /**
     * The source of {@code type}: its own class body when it is a named nested class, so a sibling
     * command in the same file does not count, else the whole file.
     */
    static String body(String file, Class<?> type) {
        if (type.getEnclosingClass() == null || type.getSimpleName().isEmpty()) return file;
        var m = Pattern.compile("\\bclass\\s+" + type.getSimpleName() + "\\b").matcher(file);
        if (!m.find()) return file;
        int open = file.indexOf('{', m.end());
        int depth = 0;
        for (int i = open; i >= 0 && i < file.length(); i++) {
            char c = file.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return file.substring(m.start(), i + 1);
        }
        return file;
    }

    @Test
    void a_caller_is_resolved_from_shell_markdown_and_argument_lists() {
        Map<String, CliCommand> commands = CommandModeGuardTest.allCommands();
        assertThat(resolve("  \"$JK_BIN\" engine status -O jsonl 2>/dev/null", commands))
                .containsExactly("jk engine status=jsonl");
        assertThat(resolve("Run `jk audit -s HIGH -O jsonl` in CI.", commands)).containsExactly("jk audit=jsonl");
        assertThat(resolve("const r = await run(bin, [\"web\", \"-O\", \"jsonl\"], cwd);", commands))
                .containsExactly("jk web=jsonl");
        assertThat(resolve("p = run([jk, \"build\", \"--skip-tests\", \"--output\", \"json\"], cwd=w)", commands))
                .containsExactly("jk build=json");
        assertThat(resolve("List<String> build = new ArrayList<>(List.of(\"build\", \"-O\", \"jsonl\"));", commands))
                .containsExactly("jk build=jsonl");
        assertThat(resolve("jk lock -F --output=jsonl", commands)).containsExactly("jk lock=jsonl");
        assertThat(resolve("rql snapshot --output out.tgz", commands)).isEmpty();
        assertThat(resolve("jk frobnicate -O jsonl", commands)).containsExactly("null=jsonl");
        assertThat(resolve("Each `jk node` command under `-O jsonl` prints a row", commands))
                .isEmpty();
        assertThat(resolve("steps that finished (`-O jsonl` task-finish events)", commands))
                .isEmpty();
    }

    private static List<String> resolve(String line, Map<String, CliCommand> commands) {
        List<String> out = new ArrayList<>();
        for (Caller c : callersOf("f", 1, line, commands)) out.add(c.command() + "=" + c.format());
        return out;
    }

    static List<Caller> scan(Path root, Map<String, CliCommand> commands) throws IOException {
        List<Caller> out = new ArrayList<>();
        for (String r : ROOTS) {
            Path start = root.resolve(r);
            assertThat(start).as("caller root %s", r).exists();
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(start)
                            && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return RepoRoot.otherCheckout(dir, root) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() > 2_000_000) return FileVisitResult.CONTINUE;
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    } catch (MalformedInputException binary) {
                        return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    for (int i = 0; i < lines.size(); i++) out.addAll(callersOf(rel, i + 1, lines.get(i), commands));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return out;
    }

    /** The machine-format callers on one line. */
    static List<Caller> callersOf(String file, int lineNo, String line, Map<String, CliCommand> commands) {
        if (!line.contains("-O") && !line.contains("--output")) return List.of();
        List<Token> tokens = tokens(line);
        List<Caller> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i).text();
            String format = null;
            if ((t.equals("-O") || t.equals("--output")) && i + 1 < tokens.size()) {
                String next = trimPunct(tokens.get(i + 1).text());
                if (FORMAT.matcher(next).matches()) format = next;
            } else if (t.startsWith("--output=") || t.startsWith("-O") && t.length() > 2) {
                String v = trimPunct(t.substring(t.startsWith("-O") ? 2 : "--output=".length()));
                if (FORMAT.matcher(v).matches()) format = v;
            }
            if (format == null) continue;
            String command = command(tokens, i, commands);
            if (!MENTION.equals(command)) out.add(new Caller(file, lineNo, line, command, format));
        }
        return out;
    }

    /** No words before the flag: a mention of the format in prose, not a run of jk. */
    private static final String MENTION = "";

    /**
     * The command path a format flag at {@code flag} belongs to: the words after the nearest jk
     * before it ({@code jk}, {@code $jk}, {@code "$JK_BIN"}, {@code …/jk}), or else the first word of
     * the run of string literals the flag ends (an argument list such as {@code ["web", "-O"]}).
     * {@link #MENTION} when no word precedes the flag, null when the words name no command.
     */
    static @Nullable String command(List<Token> tokens, int flag, Map<String, CliCommand> commands) {
        int start = -1;
        int span = tokens.get(flag).span();
        int floor = 0;
        if (span >= 0) {
            while (floor < flag && tokens.get(floor).span() != span) floor++;
        }
        for (int j = flag - 1; j >= floor; j--) {
            if (JK.matcher(tokens.get(j).text()).matches()) {
                start = j + 1;
                break;
            }
        }
        if (start < 0 && tokens.get(flag).literal()) {
            start = flag;
            while (start > floor && tokens.get(start - 1).literal()) start--;
        }
        if (start < 0 || start == flag) return MENTION;
        @Nullable String path = null;
        for (int j = start; j < flag; j++) {
            String word = trimPunct(tokens.get(j).text());
            if (path == null && word.startsWith("-")) continue;
            String next = (path == null ? "jk" : path) + " " + word;
            if (!commands.containsKey(next)) break;
            path = next;
        }
        return path;
    }

    /** The words of a line, quotes, brackets and commas as separators, literals marked. */
    static List<Token> tokens(String line) {
        List<Token> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int spans = 0;
        for (int i = 0; i <= line.length(); i++) {
            char c = i < line.length() ? line.charAt(i) : ' ';
            boolean sep = Character.isWhitespace(c) || "()[]{},;|&".indexOf(c) >= 0 && quote == 0;
            boolean q = c == '"' || c == '\'' || c == '`';
            int span = quote == '`' ? spans : -1;
            if (q && (quote == 0 || quote == c)) {
                flush(cur, quote != 0, span, out);
                if (quote == '`') spans++;
                quote = quote == 0 ? c : 0;
            } else if (sep) {
                flush(cur, quote != 0, span, out);
            } else {
                cur.append(c);
            }
        }
        return out;
    }

    private static void flush(StringBuilder cur, boolean literal, int span, List<Token> out) {
        if (cur.isEmpty()) return;
        out.add(new Token(cur.toString(), literal, span));
        cur.setLength(0);
    }

    private static String trimPunct(String s) {
        int end = s.length();
        while (end > 0 && ".:;,)".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }
}
