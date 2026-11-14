// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Dispatch folds the parsed global flags into the session before any command runs, so {@code -u}
 * and {@code -a} after the verb reach every command's renderer, whether or not its body reads its
 * own global options. Each registered leaf is stood in for by a probe with the leaf's own option
 * table, so the parse is exactly the one a user gets, and the probe records what the theme says
 * inside {@code run}.
 */
class DispatchFoldsGlobalsTest {

    /** Every leaf of the registry, hidden ones included, by its qualified name. */
    static Map<String, CliCommand> leaves() {
        Map<String, CliCommand> out = new TreeMap<>();
        for (CliCommand c : CommandDispatch.commands()) collect("jk " + c.name(), c, out);
        return out;
    }

    private static void collect(String qualified, CliCommand c, Map<String, CliCommand> out) {
        if (c.isLeaf()) {
            out.put(qualified, c);
            return;
        }
        for (CliCommand sub : c.subcommands()) collect(qualified + " " + sub.name(), sub, out);
    }

    /** Arguments that satisfy {@code leaf}'s required parameters and options, then {@code extra}. */
    static List<String> satisfying(CliCommand leaf, String... extra) {
        List<String> args = new ArrayList<>();
        for (Opt o : leaf.options()) {
            if (o.required()) {
                args.add(o.names().getLast());
                if (o.takesValue()) args.add("x");
            }
        }
        for (Param p : leaf.parameters()) {
            for (int i = 0; i < p.arity().min(); i++) args.add("x");
        }
        args.addAll(List.of(extra));
        return args;
    }

    /** What the theme said inside a probe standing in for {@code leaf}, run with {@code args}. */
    private static boolean ansiInsideRun(CliCommand leaf, String qualified, List<String> args) throws Exception {
        boolean[] seen = {true};
        CliCommand probe = new CliCommand() {
            @Override
            public String name() {
                return leaf.name();
            }

            @Override
            public String description() {
                return leaf.description();
            }

            @Override
            public List<Opt> options() {
                return leaf.options();
            }

            @Override
            public List<Param> parameters() {
                return leaf.parameters();
            }

            @Override
            public boolean passthrough() {
                return leaf.passthrough();
            }

            @Override
            public int run(Invocation in) {
                seen[0] = Theme.active().isAnsi();
                return 0;
            }
        };
        int exit = SessionContext.invocation(() -> {
            // Pinned ANSI, so a fold that never happened shows as ANSI instead of passing on a plain host.
            SessionContext.installConfig(JkConfig.empty().withForceAnsi(true));
            return CommandDispatch.dispatch(probe, qualified, args, true);
        });
        assertThat(exit).as("%s %s parsed and ran", qualified, args).isZero();
        return seen[0];
    }

    @Test
    void no_ansi_after_the_verb_reaches_every_command() throws Exception {
        for (var e : leaves().entrySet()) {
            CliCommand leaf = e.getValue();
            // A passthrough command reads jk's globals only before its name (the pre-dispatch scan).
            if (leaf.passthrough()) continue;
            assertThat(ansiInsideRun(leaf, e.getKey(), satisfying(leaf, "-u")))
                    .as("%s -u: the theme inside run", e.getKey())
                    .isFalse();
        }
    }

    @Test
    void agent_after_the_verb_reaches_every_command() throws Exception {
        for (var e : leaves().entrySet()) {
            CliCommand leaf = e.getValue();
            if (leaf.passthrough()) continue;
            assertThat(ansiInsideRun(leaf, e.getKey(), satisfying(leaf, "-a")))
                    .as("%s -a: the theme inside run", e.getKey())
                    .isFalse();
        }
    }

    @Test
    void short_globals_reach_the_help_screens() {
        // --color always pins the help to ANSI, so only the flag can turn it off.
        assertThat(Capture.stdout(() -> Jk.execute("-k", "always", "--help")))
                .as("control: forced ANSI")
                .contains("\u001b[");
        assertThat(Capture.stdout(() -> Jk.execute("-k", "always", "-u", "--help")))
                .doesNotContain("\u001b");
        assertThat(Capture.stdout(() -> Jk.execute("-k", "always", "-a", "--help")))
                .doesNotContain("\u001b");
        assertThat(Capture.stdout(() -> Jk.execute("-k", "always", "jobs", "--help", "-u")))
                .doesNotContain("\u001b");
        assertThat(Capture.stdout(() -> Jk.execute("-k", "always", "-u", "jobs", "--help")))
                .doesNotContain("\u001b");
    }

    @Test
    void the_probe_sees_ansi_when_no_flag_asks_otherwise() throws Exception {
        CliCommand jobs = leaves().get("jk jobs");
        assertThat(jobs).isNotNull();
        assertThat(ansiInsideRun(Objects.requireNonNull(jobs), "jk jobs", List.of()))
                .as("without -u the pinned ANSI holds, so the other tests prove the fold")
                .isTrue();
    }
}
