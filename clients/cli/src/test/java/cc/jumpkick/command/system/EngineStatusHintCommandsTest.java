// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.args.ParseException;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineHeapDump;
import cc.jumpkick.model.command.CliCommand;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Every {@code `jk …`} command a {@code jk engine status} hint names is a command jk parses, under
 * its canonical name: each word resolves exactly (no alias, no prefix) to a registered command, and
 * the leaf accepts the options that follow.
 */
class EngineStatusHintCommandsTest {

    private static final Pattern COMMAND = Pattern.compile("`(jk [^`]*)`");

    private static EngineFleet.Member ours(long pid) {
        return new EngineFleet.Member(null, null, null, pid, false);
    }

    private static EngineFleet.Member other(long pid) {
        return new EngineFleet.Member(null, null, null, pid, false, true, Path.of("/other/home"), null);
    }

    private static List<String> hints() {
        return List.of(
                EngineHeapDump.finding(Path.of("/home/u/.jk/state/engine/java_pid42.hprof")),
                EngineStatusCommand.strayHeadline(ProcessHandle.current().pid()),
                EngineStatusCommand.strayHeadline(Long.MAX_VALUE),
                EngineStatusCommand.stopHint(List.of(ours(1), ours(2))),
                EngineStatusCommand.stopHint(List.of(ours(1), other(2), other(3))),
                EngineStatusCommand.stopHint(List.of(other(7))),
                EngineStatusCommand.describeIgnoredSignals("HUP"));
    }

    @Test
    void every_command_a_status_hint_names_parses() {
        List<String> commands = new ArrayList<>();
        for (String hint : hints()) {
            Matcher m = COMMAND.matcher(hint);
            boolean named = false;
            while (m.find()) {
                commands.add(m.group(1));
                named = true;
            }
            assertThat(named).as("a hint names a command: %s", hint).isTrue();
        }

        List<String> broken = new ArrayList<>();
        for (String command : commands) {
            String why = parseFailure(command);
            if (why != null) broken.add(command + ": " + why);
        }
        assertThat(broken).as("hint commands jk does not parse").isEmpty();
    }

    @Test
    void the_heap_dump_hint_names_no_heap_limit_and_no_alias() {
        assertThat(EngineHeapDump.REMEDY).contains("`jk cache clean`").doesNotContain("max-heap-mb", "prune");
        assertThat(parseFailure("jk cache prune"))
                .as("an alias is not a canonical name")
                .isNotNull();
    }

    /** Why {@code command} does not parse as written, or null when it does. */
    private static @Nullable String parseFailure(String command) {
        List<String> words = List.of(command.replace("<pid>", "1").split(" "));
        List<CliCommand> level = CommandDispatch.commands();
        CliCommand cmd = null;
        int i = 1;
        while (i < words.size() && !words.get(i).startsWith("-")) {
            CliCommand next = exact(level, words.get(i));
            if (next == null) {
                if (cmd == null || !cmd.isLeaf()) return "no command named '" + words.get(i) + "'";
                break;
            }
            cmd = next;
            level = next.subcommands();
            i++;
        }
        if (cmd == null) return "names no command";
        if (!cmd.isLeaf()) {
            CliCommand fallback = cmd.defaultSubcommand();
            if (fallback == null) return "'" + cmd.name() + "' needs a subcommand";
            cmd = fallback;
        }
        try {
            ArgParser.parse(CommandDispatch.withGlobals(cmd), words.subList(i, words.size()));
            return null;
        } catch (ParseException e) {
            return e.getMessage();
        }
    }

    private static @Nullable CliCommand exact(List<CliCommand> commands, String name) {
        for (CliCommand c : commands) if (c.name().equals(name)) return c;
        return null;
    }
}
