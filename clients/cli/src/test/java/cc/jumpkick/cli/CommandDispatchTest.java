// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.LiveRegion;
import cc.jumpkick.command.interop.MvnCommand;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandDispatchTest {

    @Test
    void escaping_exception_sweep_closes_active_live_region() {
        // A RuntimeException escaping a command must not leave the live region owning the terminal
        // (hidden cursor, animator, taskbar progress) — dispatch sweeps the active region closed.
        var cm = JkManager.plan(new PrintStream(new ByteArrayOutputStream()), "Build", false);
        assertThat(LiveRegion.active()).isSameAs(cm);
        CommandDispatch.closeActiveLiveRegion();
        assertThat(LiveRegion.active()).isNull();
        CommandDispatch.closeActiveLiveRegion(); // idempotent with nothing active
    }

    @Test
    void hidden_verb_aliases_stay_out_of_prefix_dispatch() {
        // Plan / why-rebuilt are VERB_ALIASES rewrites (exact token only), never
        // dispatcher names — otherwise `jk pl` unique-prefixes plan→explain and
        // `jk wh` is ambiguous with why.
        assertThat(CommandDispatch.resolveName("pl").resolved())
                .as("jk pl is not a command prefix")
                .isFalse();
        assertThat(CommandDispatch.resolveName("plan").resolved())
                .as("plan is a rewrite, not a dispatcher name")
                .isFalse();
        var wh = CommandDispatch.resolveName("wh");
        assertThat(wh.resolved()).as("jk wh → why, not ambiguous").isTrue();
        assertThat(Objects.requireNonNull(wh.value()).name()).isEqualTo("why");
        assertThat(CommandDispatch.resolveName("why-rebuilt").resolved())
                .as("why-rebuilt is a rewrite, not a dispatcher name")
                .isFalse();
        // The exact alias tokens still reach explain via the rewrite layer.
        assertThat(Jk.rewriteAlias(new String[] {"plan"})[0]).isEqualTo("explain");
        assertThat(Jk.rewriteAlias(new String[] {"why-rebuilt"})[0]).isEqualTo("explain");
    }

    @Test
    void commandIndex_findsFirstPositional() {
        assertThat(CommandDispatch.commandIndex(List.of("build"))).isZero();
        assertThat(CommandDispatch.commandIndex(List.of("-q", "build"))).isEqualTo(1);
    }

    @Test
    void commandIndex_skipsValueTakingGlobalAndItsArgument() {
        // -C consumes the next token, so the command is at index 2.
        assertThat(CommandDispatch.commandIndex(List.of("-C", "/tmp", "build"))).isEqualTo(2);
    }

    @Test
    void commandIndex_skipsAbbreviatedValueTakingGlobal() {
        // --di is a unique prefix of the value-taking global --dir, so it consumes /tmp too.
        assertThat(CommandDispatch.commandIndex(List.of("--di", "/tmp", "build")))
                .isEqualTo(2);
    }

    @Test
    void commandIndex_inlineValueGlobalDoesNotConsumeNext() {
        assertThat(CommandDispatch.commandIndex(List.of("--dir=/tmp", "build"))).isEqualTo(1);
    }

    @Test
    void commandIndex_directoryAliasStillConsumesValue() {
        // Hidden alias --directory of --dir remains accepted.
        assertThat(CommandDispatch.commandIndex(List.of("--directory", "/tmp", "build")))
                .isEqualTo(2);
    }

    @Test
    void top_level_help_says_where_a_passthrough_tools_own_help_lives() {
        // `jk mvn --help` is Maven's help — everything after the name is the tool's — so the row on
        // `jk --help` is the one place a user learns that jk's page for the command is `jk --help mvn`.
        String help = Capture.stdout(() -> assertThat(Jk.execute("--help")).isZero());
        List<String> passthrough = CommandDispatch.commands().stream()
                .filter(c -> c.passthrough() && !c.hidden())
                .map(CliCommand::name)
                .toList();
        assertThat(passthrough).contains("mvn", "gradle");
        for (String name : passthrough) {
            assertThat(help).as("the %s row points at jk's own help", name).contains("jk --help " + name);
        }
    }

    @Test
    void quiet_does_not_silence_help_asked_for_by_name() {
        // -q mutes progress and information. Help is the one output that can never be noise: the
        // user asked for it by name, so it is written whether or not the session is quiet.
        String help = Capture.stdout(
                () -> assertThat(Jk.execute("-q", "--help", "build")).isZero());
        assertThat(help).contains("Usage").contains("build");
        assertThat(CommandDispatch.asksForHelpOrVersion(List.of("-q", "--help", "build")))
                .isTrue();
        assertThat(CommandDispatch.asksForHelpOrVersion(List.of("-q", "build", "-h")))
                .isTrue();
        assertThat(CommandDispatch.asksForHelpOrVersion(List.of("-q", "--version")))
                .isTrue();
        // A program's or a passthrough tool's --help is theirs, not jk's ask: quiet still applies.
        assertThat(CommandDispatch.asksForHelpOrVersion(List.of("-q", "run", ".", "--", "--help")))
                .isFalse();
        assertThat(CommandDispatch.asksForHelpOrVersion(List.of("-q", "mvn", "--help")))
                .isFalse();
        assertThat(CommandDispatch.asksForHelpOrVersion(List.of("-q", "build"))).isFalse();
    }

    @Test
    void global_options_help_names_and_order() {
        List<String> names = GlobalOptions.globalOpts().stream()
                .filter(o -> !o.hidden())
                .map(o -> String.join(", ", o.names())
                        + (o.takesValue() && o.paramLabel() != null ? " " + o.paramLabel() : ""))
                .toList();
        assertThat(names)
                .containsExactly(
                        "-F, --force",
                        "-r, --redo",
                        "-O, --output <FORMAT>",
                        "-q, --quiet",
                        "-v, --verbose",
                        "--no-progress",
                        "--no-timeline",
                        "--no-ansi",
                        "--no-osc",
                        "--notify",
                        "--no-notify",
                        "--color <WHEN>",
                        "--config-file <FILE>",
                        "--no-config",
                        "-C, --dir <DIR>",
                        "-j, --jobs <N>",
                        "--ram-percent <PCT>",
                        "--jdk <spec>",
                        "--graal <spec>",
                        "--jvm-arg <ARG>",
                        "--offline",
                        "-V, --version",
                        "-h, --help");
        // Hidden aliases stay out of help names.
        var byCanonical = new HashMap<String, Opt>();
        for (var g : GlobalOptions.globalOpts()) byCanonical.put(g.canonicalName(), g);
        assertThat(global(byCanonical, "redo").aliases()).containsExactly("--rebuild");
        assertThat(global(byCanonical, "dir").aliases()).containsExactly("--directory");
        assertThat(global(byCanonical, "ram-percent").aliases()).containsExactly("--max-ram-percent");
    }

    @Test
    void commandIndex_doubleDashSelectsFollowingToken() {
        assertThat(CommandDispatch.commandIndex(List.of("--", "build"))).isEqualTo(1);
    }

    @Test
    void own_args_end_at_a_literal_double_dash() {
        // The parser stops reading options there; the pre-scans that run before it must too, or
        // `jk run . -- -q` mutes jk and `-- -C /elsewhere` rebases its working directory.
        assertThat(CommandDispatch.ownArgsEnd(List.of("run", ".", "--", "-q"))).isEqualTo(2);
        assertThat(CommandDispatch.ownArgsEnd(List.of("-q", "--", "build", "--list")))
                .isEqualTo(1);
        assertThat(CommandDispatch.ownArgsEnd(List.of("build", "-q"))).isEqualTo(2);
        assertThat(CommandDispatch.ownArgsEnd(List.of())).isZero();
    }

    @Test
    void own_args_end_at_a_passthrough_command_name() {
        // Everything after `jk mvn` is Maven's: -C is its strict-checksums flag, not jk's directory.
        assertThat(CommandDispatch.ownArgsEnd(List.of("mvn", "-C", "install"))).isZero();
        assertThat(CommandDispatch.ownArgsEnd(List.of("gradle", "-q", "build"))).isZero();
        assertThat(CommandDispatch.ownArgsEnd(List.of("-C", "app", "mvn", "install")))
                .isEqualTo(2);
        // A unique prefix names the command just as dispatch resolves it.
        assertThat(CommandDispatch.ownArgsEnd(List.of("gradl", "-q"))).isZero();
        // Other commands keep jk's globals anywhere.
        assertThat(CommandDispatch.ownArgsEnd(List.of("build", "-C", "app"))).isEqualTo(3);
    }

    @Test
    void a_passthrough_command_owns_its_three_options_after_the_name_and_nothing_else() throws Exception {
        // The rule migration.md states: jk's globals before the name; after it, only the command's own
        // options, spelled exactly, are jk's — every other token is the child tool's, in order.
        CliCommand mvn = new MvnCommand();
        Invocation in = CommandDispatch.parsePassthrough(
                mvn,
                new CommandDispatch.Split(
                        List.of(),
                        List.of("--tools-dir", "/opt/jk-tools", "clean", "--no-discover", "install", "-X", "-C")));
        assertThat(in.value("tools-dir")).hasValue("/opt/jk-tools");
        assertThat(in.isSet("no-discover")).isTrue();
        assertThat(in.positionals()).containsExactly("clean", "install", "-X", "-C");

        // Exact spelling only: an abbreviation would risk eating a flag meant for the tool.
        Invocation abbreviated = CommandDispatch.parsePassthrough(
                mvn, new CommandDispatch.Split(List.of(), List.of("--tools", "x", "package")));
        assertThat(abbreviated.isSet("tools-dir")).isFalse();
        assertThat(abbreviated.positionals()).containsExactly("--tools", "x", "package");

        // A global after the name is the tool's; before it, jk's.
        Invocation global = CommandDispatch.parsePassthrough(
                mvn, new CommandDispatch.Split(List.of("-C", "app"), List.of("-q", "verify")));
        assertThat(global.value("dir")).hasValue("app");
        assertThat(global.isSet("quiet")).isFalse();
        assertThat(global.positionals()).containsExactly("-q", "verify");
    }

    /**
     * ArgParser indexes option names last-wins and globals are merged after command options, so a
     * name collision silently replaces a command's option with the global (this once broke
     * `jk self update <version>`: the global -V/--version flag ate the value option). The
     * dispatcher now throws on collision; this walks every registered command so a new collision
     * fails here instead of in the field.
     */
    @Test
    void no_registered_command_option_collides_with_a_global() {
        Set<String> globals = new HashSet<>();
        for (var g : GlobalOptions.globalOpts()) globals.addAll(g.allNames());
        for (var cmd : CommandDispatch.commands()) assertNoGlobalCollision(cmd, cmd.name(), globals);
    }

    @Test
    void global_force_and_redo_declare_short_and_long_names() {
        Map<String, Opt> byCanonical = new HashMap<>();
        for (var g : GlobalOptions.globalOpts()) {
            byCanonical.put(g.canonicalName(), g);
        }
        // -F/--force; -r/--redo with --rebuild as a hidden alias (not in help names).
        assertThat(global(byCanonical, "force").names()).containsExactly("-F", "--force");
        assertThat(global(byCanonical, "force").aliases()).isEmpty();
        assertThat(global(byCanonical, "redo").names()).containsExactly("-r", "--redo");
        assertThat(global(byCanonical, "redo").aliases()).containsExactly("--rebuild");
    }

    private static Opt global(Map<String, Opt> byCanonical, String canonicalName) {
        assertThat(byCanonical).containsKey(canonicalName);
        return Objects.requireNonNull(byCanonical.get(canonicalName));
    }

    private static void assertNoGlobalCollision(CliCommand cmd, String qualified, Set<String> globals) {
        for (var opt : cmd.options()) {
            for (String n : opt.allNames()) {
                assertThat(globals)
                        .as("`jk %s` declares %s, which the global options also declare", qualified, n)
                        .doesNotContain(n);
            }
        }
        for (var sub : cmd.subcommands()) {
            assertNoGlobalCollision(sub, qualified + " " + sub.name(), globals);
        }
    }

    @Test
    void plugin_arg_scan_honors_output_flags() {
        assertThat(CommandDispatch.pluginArgsAskJson(List.of("-O", "json"))).isTrue();
        assertThat(CommandDispatch.pluginArgsAskJson(List.of("--output", "jsonl")))
                .isTrue();
        assertThat(CommandDispatch.pluginArgsAskJson(List.of("--output=json", "x")))
                .isTrue();
        assertThat(CommandDispatch.pluginArgsAskJson(List.of("-O", "text"))).isFalse();
        assertThat(CommandDispatch.pluginArgsAskJson(List.of("build", "--fast")))
                .isFalse();
    }
}
