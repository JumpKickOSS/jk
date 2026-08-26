// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MainSources;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The script-mode allowlist in {@code docs/contributors/tui.md} is the code, not a side table. */
class ScriptModeAllowlistTest {

    /**
     * Commands whose stdout is machine-consumed for every invocation — one row each in the
     * script-mode allowlist in {@code docs/contributors/tui.md}. A command declared here without a
     * row, or a row with no declaration, is the drift this test exists to catch. The
     * verb-conditional half ({@code jk activate <shell>}, {@code jk explain --graph}, {@code jk ide
     * --print-model}, {@code jk selective resolve}, {@code jk tasks show}) is pinned by
     * {@code ScriptModeVerbsTest}.
     */
    private static final Set<String> ALWAYS_SCRIPT_MODE = Set.of(
            "jk auth token",
            "jk bsp", // bare `jk bsp` is `serve`: JSON-RPC on stdio
            "jk cache dir",
            "jk hook-env",
            "jk jdk home",
            "jk manual",
            "jk show",
            "jk storage dir",
            "jk tool dir");

    @Test
    void unconditional_script_mode_commands_are_exactly_the_allowlist() {
        Invocation bare = Invocation.builder().build();
        Set<String> declared = new TreeSet<>();
        for (Named named : walk()) {
            if (named.command().scriptMode(bare)) declared.add(named.qualified());
        }
        assertThat(declared)
                .as("every scriptMode() override needs a row in the docs/contributors/tui.md allowlist")
                .containsExactlyInAnyOrderElementsOf(ALWAYS_SCRIPT_MODE);
    }

    @Test
    void no_command_begins_its_own_envelope() throws IOException {
        Path main = MainSources.locate();
        Pattern anti = Pattern.compile("CliOutput\\.beginCommand\\(");
        Path dispatch = main.resolve("cc/jumpkick/cli/CommandDispatch.java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(main)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.equals(dispatch))
                    .forEach(p -> {
                        try {
                            if (anti.matcher(Files.readString(p)).find()) {
                                offenders.add(main.relativize(p).toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertThat(offenders)
                .as("dispatch owns the envelope lifecycle — commands declare scriptMode(Invocation) instead")
                .isEmpty();
    }

    /** A command with the qualified name a user types, e.g. {@code jk cache dir}. */
    private record Named(String qualified, CliCommand command) {}

    /** Every reachable command, walking subcommands and any bare-group default leaf. */
    private static List<Named> walk() {
        List<Named> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CliCommand top : CommandDispatch.commands()) {
            visit("jk " + top.name(), top, found, seen);
        }
        return found;
    }

    private static void visit(String qualified, CliCommand cmd, List<Named> found, Set<String> seen) {
        if (cmd == null || !seen.add(qualified)) return;
        found.add(new Named(qualified, cmd));
        for (CliCommand sub : cmd.subcommands()) {
            visit(qualified + " " + sub.name(), sub, found, seen);
        }
        CliCommand def = cmd.defaultSubcommand();
        if (def != null) visit(qualified + " " + def.name(), def, found, seen);
    }
}
