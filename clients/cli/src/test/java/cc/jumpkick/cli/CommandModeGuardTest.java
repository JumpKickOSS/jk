// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.theme.StreamCaps;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.secret.SecretVault;
import cc.jumpkick.util.TestHomes;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every registered command, hidden ones included, in every output mode, checked from the command
 * registry so a new command is covered the day it is registered: help works in each mode, an
 * unknown verb fails, a redirected, {@code -u} or agent run writes no escape byte, {@code -u} writes
 * only ASCII, and under {@code -O jsonl} every stdout line is an envelope object or the command
 * refuses the flag. A command runs in-process against a temporary home and an empty working
 * directory and user home, offline, with no engine (the fast tier forbids spawning one); the commands that cannot
 * run that way are in {@link #NOT_RUN}, each with its reason, and that list is checked against the
 * registry too.
 *
 * <p>Each command in each mode is a test of its own, so each gets the tier's per-method limit: one
 * method looping over the registry passed 20 s on a loaded budget Windows host, where the whole walk
 * takes about 13 s idle and a single run well under one.
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

    private String userHome;

    @BeforeEach
    void isolate() throws Exception {
        // jk doctor reads the shell rc files under the user home: a run must never read the real ones.
        userHome = System.getProperty("user.home");
        System.setProperty(
                "user.home", Files.createDirectories(tmp.resolve("user-home")).toString());
        // The temporary home is a test sandbox by its path (TestHomes.DIR), as every test JVM's is: a
        // jk running in one lists only its own engines and never reads the endpoints of the
        // developer's, which `jk engine status` would otherwise probe.
        for (String dir : ISOLATED) {
            System.setProperty(
                    "jk.env." + dir,
                    Files.createDirectories(tmp.resolve(TestHomes.DIR).resolve(dir))
                            .toString());
        }
        // Secrets stay in the temporary home's files: no run reads or writes an OS keychain.
        System.setProperty("jk.env." + SecretVault.ENV, "file");
        stdin = System.in;
        System.setIn(new ByteArrayInputStream(new byte[0]));
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", userHome);
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

    /**
     * The limit on one run, under the fast tier's 20 s per method so that a hung command fails here,
     * naming itself, rather than at the tier's limit. The slowest single run on the budget Windows host
     * (bocaminipc) takes about a second.
     */
    static final int RUN_SECONDS = 15;

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
            Run r = run(argv("jk nosuchverb", mode.flags), RUN_SECONDS);
            assertThat(r.exit()).as("jk nosuchverb %s", mode.flags).isEqualTo(Exit.USAGE);
            assertThat(r.out()).as("jk nosuchverb %s: stdout", mode.flags).isEmpty();
        }
    }

    /** The registered command at {@code name}, which the sources above took from the registry. */
    private static CliCommand command(String name) {
        return Objects.requireNonNull(allCommands().get(name), name);
    }

    /** Every command path paired with every mode. */
    static Stream<Arguments> everyCommandInEveryMode() {
        return allCommands().keySet().stream()
                .flatMap(name -> Stream.of(Mode.values()).map(mode -> Arguments.of(name, mode)));
    }

    /** Every command path, for the run on a terminal. */
    static Stream<String> everyCommand() {
        return allCommands().keySet().stream();
    }

    /** The leaves that run headless, each paired with every mode. */
    static Stream<Arguments> runnableInEveryMode() {
        return runnable().stream().flatMap(name -> Stream.of(Mode.values()).map(mode -> Arguments.of(name, mode)));
    }

    /** The leaves that run headless: neither in {@link #NOT_RUN} nor a passthrough. */
    static List<String> runnable() {
        List<String> out = new ArrayList<>();
        leaves().forEach((name, leaf) -> {
            if (!notRun(name) && !leaf.passthrough()) out.add(name);
        });
        return out;
    }

    /** The leaves with no machine output, which must refuse {@code -O jsonl}. */
    static Stream<String> withNoMachineOutput() {
        return leaves().entrySet().stream()
                .filter(e -> e.getValue().machineOutput() == MachineOutput.NONE
                        && !e.getValue().passthrough())
                .map(Map.Entry::getKey);
    }

    /** {@code name}'s help page: after its name, or jk's own for a passthrough command, whose --help is the tool's. */
    private static List<String> helpArgs(String name) {
        return command(name).passthrough()
                ? new ArrayList<>(List.of("--help", name.substring("jk ".length())))
                : argv(name, List.of("--help"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyCommandInEveryMode")
    void help_works_for_every_command_in_every_mode(String name, Mode mode) throws Exception {
        boolean passthrough = command(name).passthrough();
        List<String> helpArgs = helpArgs(name);
        // Flags before a passthrough command's name are jk's; after it, the tool's.
        List<String> args = new ArrayList<>(passthrough ? mode.flags : helpArgs);
        args.addAll(passthrough ? helpArgs : mode.flags);
        Run r = run(args, RUN_SECONDS);
        String what = "jk " + String.join(" ", args);
        assertThat(r.exit()).as(what).isZero();
        assertThat(r.out()).as(what).contains("Usage");
        assertNoEscape(what, r);
        if (mode == Mode.ASCII) assertAscii(what, r);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyCommand")
    void help_works_for_every_command_on_a_terminal(String name) throws Exception {
        List<String> helpArgs = helpArgs(name);
        // On this thread: the terminal stand-in is bound to it.
        Run onTerminal = StreamCaps.asTerminal(true, true, () -> runHere(helpArgs));
        assertThat(onTerminal.exit()).as("%s on a terminal", helpArgs).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withNoMachineOutput")
    void a_command_with_no_machine_output_refuses_jsonl(String name) throws Exception {
        CliCommand leaf = command(name);
        Run r = run(argv(name, DispatchFoldsGlobalsTest.satisfying(leaf, "-O", "jsonl")), RUN_SECONDS);
        String what = name + " -O jsonl";
        assertThat(r.exit()).as(what).isEqualTo(Exit.USAGE);
        assertThat(r.out()).as(what).isEmpty();
        assertThat(r.err()).as(what).contains("has no -O jsonl output");
    }

    @Test
    void some_commands_run_headless() {
        assertThat(runnable()).as("commands run headless").isNotEmpty();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("runnableInEveryMode")
    void every_runnable_command_honours_every_mode(String name, Mode mode) throws Exception {
        CliCommand leaf = command(name);
        Path cwd = Files.createTempDirectory(tmp, "cwd");
        List<String> tail = new ArrayList<>(DispatchFoldsGlobalsTest.satisfying(leaf));
        tail.addAll(List.of("-C", cwd.toString(), "-o"));
        tail.addAll(mode.flags);
        Run r = run(argv(name, tail), RUN_SECONDS);
        String what = name + " " + mode.flags;
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
    }
}
