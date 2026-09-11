// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.run.DebugAttach;
import cc.jumpkick.command.pipeline.TestCommand;
import cc.jumpkick.command.toolchain.ToolRunCommand;
import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --debug-jvm} and {@code --class} as {@code jk test} and {@code jk run} parse them, and the
 * client-side port settlement behind a {@code 0}.
 */
class DebugJvmFlagTest {

    private static Command command(String name, List<Opt> own, List<Param> params) {
        return new Command() {
            @Override
            public List<Param> parameters() {
                return params;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public List<Opt> options() {
                var opts = new ArrayList<>(own);
                opts.addAll(GlobalOptions.globalOpts());
                return opts;
            }
        };
    }

    private static final Command TEST = command("test", new TestCommand().options(), List.of());
    private static final Command RUN =
            command("run", new ToolRunCommand().options(), new ToolRunCommand().parameters());

    private static Invocation test(String... args) throws Exception {
        return ArgParser.parse(TEST, List.of(args));
    }

    @Test
    void absent_flag_is_no_debug_request() throws Exception {
        assertThat(DebugAttach.fromFlag(test())).isNull();
        assertThat(DebugAttach.fromFlag(test("--workers", "2"))).isNull();
    }

    @Test
    void a_bare_flag_is_the_default_address_suspended() throws Exception {
        assertThat(DebugAttach.fromFlag(test("--debug-jvm"))).isEqualTo(DebugJvm.DEFAULT);
        assertThat(DebugAttach.fromFlag(test("--debug-jvm", "--all"))).isEqualTo(DebugJvm.DEFAULT);
    }

    @Test
    void the_value_takes_both_spellings_and_the_suspend_override() throws Exception {
        assertThat(DebugAttach.fromFlag(test("--debug-jvm=0"))).isEqualTo(DebugJvm.parse("0"));
        assertThat(DebugAttach.fromFlag(test("--debug-jvm", "6006"))).isEqualTo(DebugJvm.parse("6006"));
        assertThat(DebugAttach.fromFlag(test("--debug-jvm=*:5005,suspend=n")))
                .isEqualTo(new DebugJvm("*", 5005, false));
        assertThatThrownBy(() -> DebugAttach.fromFlag(test("--debug-jvm=nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_run_verb_parses_the_same_flag() throws Exception {
        Invocation in = ArgParser.parse(RUN, List.of("--debug-jvm=0,suspend=n", ".", "--", "x"));
        assertThat(DebugAttach.fromFlag(in)).isEqualTo(DebugJvm.parse("0,suspend=n"));
        assertThat(ArgParser.parse(RUN, List.of("--debug-jvm", "--", "x")).value(DebugAttach.OPTION))
                .contains("");
    }

    @Test
    void class_patterns_land_on_the_selection(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25
                """);
        var sel = TestCommand.resolveTestSelection(
                test("-C", dir.toString(), "--class", "FooTest", "--class=com.acme.*IT"));
        assertThat(sel.classes()).containsExactly("FooTest", "com.acme.*IT");
        assertThat(TestCommand.resolveTestSelection(test("-C", dir.toString())).classes())
                .isEmpty();
    }

    @Test
    void an_explicit_port_is_kept_and_a_zero_becomes_a_free_bound_port() throws IOException {
        DebugJvm explicit = DebugJvm.parse("6006");
        assertThat(DebugAttach.bind(explicit)).isSameAs(explicit);

        DebugJvm bound = DebugAttach.bind(DebugJvm.parse("0,suspend=n"));
        assertThat(bound.port()).isBetween(1, 65_535);
        assertThat(bound.suspend()).isFalse();
        assertThat(bound.host()).isEqualTo("localhost");
        // Released: the JVM that is told this address can take it.
        try (ServerSocket again = new ServerSocket(bound.port(), 1, InetAddress.getByName("localhost"))) {
            assertThat(again.getLocalPort()).isEqualTo(bound.port());
        }
    }

    @Test
    void the_announcement_names_the_address_and_the_suspend_state() {
        assertThat(DebugAttach.announcement(DebugJvm.parse("7007")))
                .isEqualTo("Debugger listening on localhost:7007 — the JVM waits for a debugger to attach");
        assertThat(DebugAttach.announcement(DebugJvm.parse("7007,suspend=n")))
                .isEqualTo("Debugger listening on localhost:7007");
    }
}
