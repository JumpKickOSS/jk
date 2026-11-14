// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import cc.jumpkick.cli.api.StreamCaps;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.secret.SecretVault;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every registered command, hidden ones included, in every output mode, checked from the command
 * registry so a new command is covered the day it is registered: help works in each mode, an
 * unknown verb fails, a redirected, {@code -u} or agent run writes no escape byte, {@code -u} writes
 * only ASCII, and under {@code -O jsonl} every stdout line is an envelope object or the command
 * refuses the flag. A command runs in-process against a temporary home and an empty working
 * directory, offline, with no engine (the fast tier forbids spawning one); the commands that cannot
 * run that way are in {@link #NOT_RUN}, each with its reason, and that list is checked against the
 * registry too.
 */
class CommandModeGuardTest {

    /**
     * Commands not run headless, with the reason. A key ending in {@code *} covers every command
     * under that path. Help, the unknown-verb rule and the {@code -O} refusal still cover them.
     */
    static final Map<String, String> NOT_RUN = Map.ofEntries(
            Map.entry("jk mcp", "stdio JSON-RPC server: answers until its client closes stdin"),
            Map.entry("jk bsp", "stdio JSON-RPC server: answers until its client closes stdin"),
            Map.entry("jk watch*", "a watch loop that runs until Ctrl-C"),
            Map.entry("jk web", "serves the dashboard and opens a browser"),
            Map.entry("jk shell", "starts an interactive subshell"),
            Map.entry("jk jshell", "starts an interactive REPL"),
            Map.entry("jk tool run", "runs a tool on inherited stdio"),
            Map.entry("jk node exec", "runs a Node program on inherited stdio"),
            Map.entry("jk node run", "runs a package script on inherited stdio"),
            Map.entry("jk npx", "passthrough to npx on inherited stdio"),
            Map.entry("jk npm", "passthrough to npm on inherited stdio"),
            Map.entry("jk mvn", "passthrough to Maven on inherited stdio"),
            Map.entry("jk gradle", "passthrough to Gradle on inherited stdio"),
            Map.entry("jk self*", "changes jk's own install and the user's shell files"),
            Map.entry("jk activate", "writes the user's shell rc files"),
            Map.entry("jk deactivate", "writes the user's shell rc files"),
            Map.entry("jk jdk update-shell", "writes the user's shell rc files"),
            Map.entry("jk agents", "writes coding-agent files into the project the working directory names"),
            Map.entry("jk engine start", "starts an engine"),
            Map.entry("jk ide", "launches an IDE"),
            Map.entry("jk vscode", "launches an IDE"),
            Map.entry("jk export idea", "launches an IDE"));

    /** The roots a run may write, every one under the test's temporary directory. */
    static final List<String> ISOLATED = List.of("JK_HOME", "JK_CACHE_DIR", "JK_STORE_DIR", "JK_STATE_DIR");

    /** The output modes a command runs in: the flags after its arguments. */
    enum Mode {
        REDIRECT(List.of()),
        ASCII(List.of("-u")),
        AGENT(List.of("-a")),
        JSONL(List.of("-O", "jsonl"));

        final List<String> flags;

        Mode(List<String> flags) {
            this.flags = flags;
        }
    }

    @TempDir
    Path tmp;

    private InputStream stdin;

    @BeforeEach
    void isolate() throws Exception {
        for (String dir : ISOLATED) {
            System.setProperty(
                    "jk.env." + dir, Files.createDirectories(tmp.resolve(dir)).toString());
        }
        // Secrets stay in the temporary home's files: no run reads or writes an OS keychain.
        System.setProperty("jk.env." + SecretVault.ENV, "file");
        stdin = System.in;
        System.setIn(new ByteArrayInputStream(new byte[0]));
    }

    @AfterEach
    void restore() {
        System.setIn(stdin);
        for (String dir : ISOLATED) System.clearProperty("jk.env." + dir);
        System.clearProperty("jk.env." + SecretVault.ENV);
    }

    /** Every command path in the registry, groups and leaves, hidden ones included. */
    static Map<String, CliCommand> allCommands() {
        Map<String, CliCommand> out = new TreeMap<>();
        for (CliCommand c : CommandDispatch.commands()) collect("jk " + c.name(), c, out);
        return out;
    }

    private static void collect(String qualified, CliCommand c, Map<String, CliCommand> out) {
        out.put(qualified, c);
        for (CliCommand sub : c.subcommands()) collect(qualified + " " + sub.name(), sub, out);
    }

    static Map<String, CliCommand> leaves() {
        Map<String, CliCommand> out = new TreeMap<>();
        allCommands().forEach((name, c) -> {
            if (c.isLeaf()) out.put(name, c);
        });
        return out;
    }

    static boolean notRun(String qualified) {
        for (String key : NOT_RUN.keySet()) {
            if (key.endsWith("*") ? qualified.startsWith(key.substring(0, key.length() - 1)) : key.equals(qualified)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> argv(String qualified, List<String> tail) {
        List<String> args =
                new ArrayList<>(List.of(qualified.substring("jk ".length()).split(" ")));
        args.addAll(tail);
        return args;
    }

    private record Run(int exit, String out, String err) {}

    /** {@code args} run in-process on a thread of its own, failing the test past {@code seconds}. */
    private static Run run(List<String> args, int seconds) throws Exception {
        int[] exit = {Integer.MIN_VALUE};
        Capture.Streams[] streams = new Capture.Streams[1];
        Throwable[] thrown = new Throwable[1];
        Thread t = Thread.ofPlatform().daemon().start(() -> {
            try {
                streams[0] = Capture.both(() -> exit[0] = Jk.execute(args.toArray(String[]::new)));
            } catch (Throwable e) {
                thrown[0] = e;
            }
        });
        t.join(TimeUnit.SECONDS.toMillis(seconds));
        if (t.isAlive()) {
            t.interrupt();
            fail("jk %s still running after %d s: add it to NOT_RUN with the reason", String.join(" ", args), seconds);
        }
        if (thrown[0] != null) fail("jk " + String.join(" ", args) + " threw", thrown[0]);
        return new Run(exit[0], streams[0].out(), streams[0].err());
    }

    private static Run runHere(List<String> args) {
        int[] exit = {Integer.MIN_VALUE};
        Capture.Streams streams = Capture.both(() -> exit[0] = Jk.execute(args.toArray(String[]::new)));
        return new Run(exit[0], streams.out(), streams.err());
    }

    private static void assertNoEscape(String what, Run r) {
        assertThat(r.out()).as("%s: stdout", what).doesNotContain("\u001b");
        assertThat(r.err()).as("%s: stderr", what).doesNotContain("\u001b");
    }

    private static void assertAscii(String what, Run r) {
        assertThat(nonAscii(r.out())).as("%s: non-ASCII on stdout", what).isEmpty();
        assertThat(nonAscii(r.err())).as("%s: non-ASCII on stderr", what).isEmpty();
    }

    private static String nonAscii(String s) {
        StringBuilder found = new StringBuilder();
        s.codePoints().filter(cp -> cp > 0x7F).distinct().forEach(found::appendCodePoint);
        return found.toString();
    }

    private static void assertJsonl(String what, Run r) {
        for (String line : r.out().split("\n")) {
            if (line.isBlank()) continue;
            Object parsed;
            try {
                parsed = MiniJson.parse(line);
            } catch (RuntimeException e) {
                parsed = null;
            }
            if (!(parsed instanceof Map<?, ?> row)) {
                throw new AssertionError(what + ": stdout line is not a JSON object: " + line);
            }
            assertThat(row.containsKey("schema") && row.containsKey("ts") && row.containsKey("type"))
                    .as("%s: envelope of %s", what, line)
                    .isTrue();
        }
    }

    @Test
    void the_not_run_list_names_registered_commands() {
        Map<String, CliCommand> all = allCommands();
        List<String> stale = new ArrayList<>();
        for (String key : NOT_RUN.keySet()) {
            boolean matches = key.endsWith("*")
                    ? all.keySet().stream().anyMatch(n -> n.startsWith(key.substring(0, key.length() - 1)))
                    : all.containsKey(key);
            if (!matches) stale.add(key);
        }
        assertThat(stale).as("NOT_RUN entries that name no registered command").isEmpty();
    }

    @Test
    void an_unknown_verb_fails_in_every_mode() throws Exception {
        for (Mode mode : Mode.values()) {
            Run r = run(argv("jk nosuchverb", mode.flags), 30);
            assertThat(r.exit()).as("jk nosuchverb %s", mode.flags).isEqualTo(Exit.USAGE);
            assertThat(r.out()).as("jk nosuchverb %s: stdout", mode.flags).isEmpty();
        }
    }

    @Test
    void help_works_for_every_command_in_every_mode() throws Exception {
        for (var e : allCommands().entrySet()) {
            String name = e.getKey();
            // A passthrough command's --help after its name is the tool's; jk's page is jk --help <name>.
            List<String> helpArgs = e.getValue().passthrough()
                    ? new ArrayList<>(List.of("--help", name.substring("jk ".length())))
                    : argv(name, List.of("--help"));
            for (Mode mode : Mode.values()) {
                // Flags before a passthrough command's name are jk's; after it, the tool's.
                List<String> args = new ArrayList<>(e.getValue().passthrough() ? mode.flags : helpArgs);
                args.addAll(e.getValue().passthrough() ? helpArgs : mode.flags);
                Run r = run(args, 30);
                String what = "jk " + String.join(" ", args);
                assertThat(r.exit()).as(what).isZero();
                assertThat(r.out()).as(what).contains("Usage");
                assertNoEscape(what, r);
                if (mode == Mode.ASCII) assertAscii(what, r);
            }
            // On this thread: the terminal stand-in is bound to it.
            Run onTerminal = StreamCaps.asTerminal(true, true, () -> runHere(helpArgs));
            assertThat(onTerminal.exit()).as("%s on a terminal", helpArgs).isZero();
        }
    }

    @Test
    void a_command_with_no_machine_output_refuses_jsonl() throws Exception {
        for (var e : leaves().entrySet()) {
            CliCommand leaf = e.getValue();
            if (leaf.machineOutput() != MachineOutput.NONE || leaf.passthrough()) continue;
            Run r = run(argv(e.getKey(), DispatchFoldsGlobalsTest.satisfying(leaf, "-O", "jsonl")), 30);
            String what = e.getKey() + " -O jsonl";
            assertThat(r.exit()).as(what).isEqualTo(Exit.USAGE);
            assertThat(r.out()).as(what).isEmpty();
            assertThat(r.err()).as(what).contains("has no -O jsonl output");
        }
    }

    @Test
    void every_runnable_command_honours_every_mode() throws Exception {
        Map<String, List<String>> failures = new LinkedHashMap<>();
        TreeSet<String> ran = new TreeSet<>();
        for (var e : leaves().entrySet()) {
            String name = e.getKey();
            CliCommand leaf = e.getValue();
            if (notRun(name) || leaf.passthrough()) continue;
            ran.add(name);
            for (Mode mode : Mode.values()) {
                Path cwd = Files.createTempDirectory(tmp, "cwd");
                List<String> tail = new ArrayList<>(DispatchFoldsGlobalsTest.satisfying(leaf));
                tail.addAll(List.of("-C", cwd.toString(), "-o"));
                tail.addAll(mode.flags);
                Run r = run(argv(name, tail), 60);
                String what = name + " " + mode.flags;
                try {
                    assertNoEscape(what, r);
                    if (mode == Mode.ASCII) {
                        // A script-mode command's stdout is its payload, byte-exact (a playbook, a path).
                        if (leaf.scriptMode(Invocation.builder().build())) {
                            assertThat(nonAscii(r.err()))
                                    .as("%s: non-ASCII on stderr", what)
                                    .isEmpty();
                        } else {
                            assertAscii(what, r);
                        }
                    }
                    if (mode == Mode.JSONL) {
                        if (leaf.machineOutput() == MachineOutput.JSONL) assertJsonl(what, r);
                        else assertThat(r.exit()).as(what).isEqualTo(Exit.USAGE);
                    }
                } catch (AssertionError failure) {
                    failures.computeIfAbsent(name, k -> new ArrayList<>()).add(failure.getMessage());
                }
            }
        }
        assertThat(ran).as("commands run headless").isNotEmpty();
        assertThat(failures).as("commands that broke an output-mode rule").isEmpty();
    }
}
