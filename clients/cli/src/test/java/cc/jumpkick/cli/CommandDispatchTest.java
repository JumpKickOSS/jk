// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandDispatchTest {

    @Test
    void escaping_exception_sweep_closes_active_live_region() {
        // A RuntimeException escaping a command must not leave the live region owning the terminal
        // (hidden cursor, animator, taskbar progress) — dispatch sweeps the active region closed.
        var cm = cc.jumpkick.cli.tui.CommandManager.plan(
                new java.io.PrintStream(new java.io.ByteArrayOutputStream()), "Build", false);
        assertThat(cc.jumpkick.cli.tui.LiveRegion.active()).isSameAs(cm);
        CommandDispatch.closeActiveLiveRegion();
        assertThat(cc.jumpkick.cli.tui.LiveRegion.active()).isNull();
        CommandDispatch.closeActiveLiveRegion(); // idempotent with nothing active
    }

    @Test
    void hidden_verb_aliases_stay_out_of_prefix_dispatch() {
        // JK-1364: plan / why-rebuilt are VERB_ALIASES rewrites (exact token only), never
        // dispatcher names — otherwise `jk pl` is ambiguous with plugin and `jk wh` with why.
        var pl = CommandDispatch.resolveName("pl");
        assertThat(pl.resolved()).as("jk pl → plugin, not ambiguous").isTrue();
        assertThat(pl.value().name()).isEqualTo("plugin");
        var wh = CommandDispatch.resolveName("wh");
        assertThat(wh.resolved()).as("jk wh → why, not ambiguous").isTrue();
        assertThat(wh.value().name()).isEqualTo("why");
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
        var byCanonical = new java.util.HashMap<String, cc.jumpkick.model.command.Opt>();
        for (var g : GlobalOptions.globalOpts()) byCanonical.put(g.canonicalName(), g);
        assertThat(byCanonical.get("redo").aliases()).containsExactly("--rebuild");
        assertThat(byCanonical.get("dir").aliases()).containsExactly("--directory");
        assertThat(byCanonical.get("ram-percent").aliases()).containsExactly("--max-ram-percent");
    }

    @Test
    void commandIndex_doubleDashSelectsFollowingToken() {
        assertThat(CommandDispatch.commandIndex(List.of("--", "build"))).isEqualTo(1);
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
        java.util.Set<String> globals = new java.util.HashSet<>();
        for (var g : GlobalOptions.globalOpts()) globals.addAll(g.allNames());
        for (var cmd : CommandDispatch.commands()) assertNoGlobalCollision(cmd, cmd.name(), globals);
    }

    @Test
    void global_force_and_redo_declare_short_and_long_names() {
        java.util.Map<String, cc.jumpkick.model.command.Opt> byCanonical = new java.util.HashMap<>();
        for (var g : GlobalOptions.globalOpts()) {
            byCanonical.put(g.canonicalName(), g);
        }
        // -F/--force; -r/--redo with --rebuild as a hidden alias (not in help names).
        assertThat(byCanonical.get("force").names()).containsExactly("-F", "--force");
        assertThat(byCanonical.get("force").aliases()).isEmpty();
        assertThat(byCanonical.get("redo").names()).containsExactly("-r", "--redo");
        assertThat(byCanonical.get("redo").aliases()).containsExactly("--rebuild");
    }

    private static void assertNoGlobalCollision(
            cc.jumpkick.model.command.CliCommand cmd, String qualified, java.util.Set<String> globals) {
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
}
