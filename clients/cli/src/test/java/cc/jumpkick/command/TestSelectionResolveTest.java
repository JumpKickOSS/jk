// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TestCommand#resolveTestSelection}: the resolved selection is final ({@code tagsResolved})
 * exactly when a layer spoke — baseline tags, a present profile key (including {@code = []} to
 * clear), or a CLI flag. Silent runs stay unresolved so the engine can still fold
 * per-module {@code [test]} tags for workspace members.
 */
class TestSelectionResolveTest {

    /** TestCommand's options plus the global mixin so {@code -C <dir>} parses like a real run. */
    private static final Command CMD = new Command() {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public List<Opt> options() {
            var opts = new ArrayList<>(new TestCommand().options());
            opts.addAll(GlobalOptions.globalOpts());
            return opts;
        }
    };

    private static Invocation parse(String... args) throws Exception {
        return ArgParser.parse(CMD, List.of(args));
    }

    private static void writeToml(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                """ + body);
    }

    @Test
    void profile_empty_list_clears_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]

                [profiles.ci]
                exclude-tags = []
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "-p", "ci"));
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void explicit_include_overrides_a_baseline_exclude_of_the_same_tag(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["integration", "slow", "bench"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--include-tags", "slow"));
        assertThat(sel.includeTags()).containsExactly("slow");
        assertThat(sel.excludeTags())
                .as("include ∧ exclude of one tag selects nothing — the explicit include wins")
                .containsExactly("integration", "bench");
    }

    @Test
    void explicit_exclude_still_beats_an_explicit_include(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow"]
                """);
        var sel = TestCommand.resolveTestSelection(
                parse("-C", dir.toString(), "--include-tags", "slow", "--exclude-tags", "slow"));
        assertThat(sel.excludeTags()).containsExactly("slow");
    }

    @Test
    void baseline_tags_are_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString()));
        assertThat(sel.excludeTags()).containsExactly("slow");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void cli_blank_value_clears_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--exclude-tags", ""));
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void all_clears_baseline_tags_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--all"));
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.includeTags()).isEmpty();
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void all_composes_with_explicit_cli_tags(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--all", "--exclude-tags", "bench"));
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.excludeTags()).containsExactly("bench");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void silent_run_stays_unresolved(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString()));
        assertThat(sel.includeTags()).isEmpty();
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isFalse();
    }

    @Test
    void gate_and_pre_merge_are_the_same_selection(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "network", "bench"]
                """);
        var gate = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--gate"));
        var pre = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--pre-merge"));
        assertThat(gate).isEqualTo(pre);
        assertThat(gate.gate()).isTrue();
        assertThat(gate.suites()).containsExactly("test", "integration");
        assertThat(gate.excludeTags()).containsExactly("slow", "network", "bench");
        assertThat(gate.allSuites()).isFalse();
        assertThat(gate.identityToken()).isEqualTo(pre.identityToken());
    }

    @Test
    void gate_and_all_cannot_combine(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        assertThatThrownBy(() -> TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--gate", "--all")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--all")
                .hasMessageContaining("--gate");
        assertThatThrownBy(
                        () -> TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--pre-merge", "--all")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--gate");
    }

    @Test
    void suite_wins_over_gate(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--gate", "-s", "e2e"));
        assertThat(sel.gate()).isFalse();
        assertThat(sel.suites()).containsExactly("e2e");
    }

    @Test
    void gate_suites_override_the_default_list(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                gate-suites = ["test", "contract"]
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--gate"));
        assertThat(sel.gate()).isTrue();
        assertThat(sel.suites()).containsExactly("test", "contract");
    }

    @Test
    void empty_gate_suites_is_the_unit_suite(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                gate-suites = []
                """);
        var sel = TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--gate"));
        assertThat(sel.suites()).containsExactly("test");
        assertThat(sel.gate()).isTrue();
    }

    @Test
    void suite_plus_gate_warns_once(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        Invocation in = parse("-C", dir.toString(), "--gate", "-s", "e2e");
        String err = Capture.stderr(() -> TestCommand.warnGateOverride(in, GlobalOptions.from(in)));
        assertThat(err).contains(TestCommand.GATE_SUITE_OVERRIDE_WARNING);
    }

    @Test
    void illegal_gate_suites_name_errors(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                gate-suites = ["Nope"]
                """);
        assertThatThrownBy(() -> TestCommand.resolveTestSelection(parse("-C", dir.toString(), "--gate")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nope")
                .hasMessageContaining("gate-suites");
    }
}
