// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.pipeline.BuildFlags;
import cc.jumpkick.command.pipeline.BuildVerbCommand;
import cc.jumpkick.config.TestSelection;
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
 * {@link BuildFlags#testSelection}: suites choose the tests (the default suite, {@code --suite},
 * {@code --guard}'s list, {@code --all}); {@code -I}/{@code -E} narrow one run; no manifest key or
 * profile adds tags.
 */
class TestSelectionResolveTest {

    /** jk test's options plus the global mixin so {@code -C <dir>} parses like a real run. */
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
            var opts = new ArrayList<>(BuildVerbCommand.test().options());
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
    void a_profile_chooses_no_tests(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [profiles.ci]
                javac = ["-Werror"]
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "-p", "ci"));
        assertThat(sel).isEqualTo(TestSelection.DEFAULT);
    }

    @Test
    void cli_tags_narrow_one_run_and_an_explicit_exclude_wins(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = BuildFlags.testSelection(
                parse("-C", dir.toString(), "--include-tags", "smoke,slow", "--exclude-tags", "slow"));
        assertThat(sel.includeTags()).containsExactly("smoke", "slow");
        assertThat(sel.excludeTags()).containsExactly("slow");
    }

    @Test
    void a_blank_cli_value_is_no_filter(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--exclude-tags", ""));
        assertThat(sel.excludeTags()).isEmpty();
    }

    @Test
    void all_runs_every_suite_and_composes_with_cli_tags(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--all", "--exclude-tags", "bench"));
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.excludeTags()).containsExactly("bench");
    }

    @Test
    void a_member_directory_reads_the_workspace_roots_guard_suites(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [workspace]
                modules = ["m"]

                [test]
                guard-suites = ["test", "integration", "e2e"]
                """);
        Path member = Files.createDirectories(dir.resolve("m"));
        Files.writeString(member.resolve("jk.toml"), "name = \"m\"\n");
        var sel = BuildFlags.testSelection(parse("-C", member.toString(), "--guard"));
        assertThat(sel.suites()).containsExactly("test", "integration", "e2e");
    }

    @Test
    void the_gate_runs_the_unit_and_integration_suites(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var guard = BuildFlags.testSelection(parse("-C", dir.toString(), "--guard"));
        assertThat(guard.guard()).isTrue();
        assertThat(guard.suites()).containsExactly("test", "integration");
        assertThat(guard.excludeTags()).isEmpty();
        assertThat(guard.allSuites()).isFalse();
    }

    @Test
    void gate_and_all_cannot_combine(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        assertThatThrownBy(() -> BuildFlags.testSelection(parse("-C", dir.toString(), "--guard", "--all")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--all")
                .hasMessageContaining("--guard");
    }

    @Test
    void suite_wins_over_gate(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--guard", "-s", "e2e"));
        assertThat(sel.guard()).isFalse();
        assertThat(sel.suites()).containsExactly("e2e");
    }

    @Test
    void gate_suites_override_the_default_list(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                guard-suites = ["test", "contract"]
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--guard"));
        assertThat(sel.guard()).isTrue();
        assertThat(sel.suites()).containsExactly("test", "contract");
    }

    @Test
    void empty_gate_suites_is_the_unit_suite(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                guard-suites = []
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--guard"));
        assertThat(sel.suites()).containsExactly("test");
        assertThat(sel.guard()).isTrue();
    }

    @Test
    void suite_plus_gate_warns_once(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        Invocation in = parse("-C", dir.toString(), "--guard", "-s", "e2e");
        String err = Capture.stderr(() -> BuildFlags.warnGateOverride(in, GlobalOptions.from(in)));
        assertThat(err).contains(BuildFlags.GUARD_SUITE_OVERRIDE_WARNING);
    }

    @Test
    void illegal_gate_suites_name_errors(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                guard-suites = ["Nope"]
                """);
        assertThatThrownBy(() -> BuildFlags.testSelection(parse("-C", dir.toString(), "--guard")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nope")
                .hasMessageContaining("guard-suites");
    }

    @Test
    void scripts_only_and_no_scripts_cannot_combine(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        assertThatThrownBy(
                        () -> BuildFlags.testSelection(parse("-C", dir.toString(), "--scripts-only", "--no-scripts")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BuildFlags.SCRIPTS_FLAGS_CONFLICT);
    }

    @Test
    void scripts_only_without_a_guard_stem_errors(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        assertThatThrownBy(() -> BuildFlags.testSelection(parse("-C", dir.toString(), "--scripts-only")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no guard scripts")
                .hasMessageContaining(".jk/scripts/guard");
    }

    @Test
    void scripts_only_selects_scripts_without_junit(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        writeGate(dir);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--scripts-only"));
        assertThat(sel.scriptsOnly()).isTrue();
        assertThat(sel.guard()).isFalse();
        assertThat(sel.runGuardScripts()).isTrue();
        assertThat(sel.noScripts()).isFalse();
    }

    @Test
    void gate_scripts_only_equals_scripts_only(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        writeGate(dir);
        var only = BuildFlags.testSelection(parse("-C", dir.toString(), "--scripts-only"));
        var both = BuildFlags.testSelection(parse("-C", dir.toString(), "--guard", "--scripts-only"));
        assertThat(only.scriptsOnly()).isTrue();
        assertThat(both.scriptsOnly()).isTrue();
        assertThat(only.runGuardScripts()).isTrue();
        assertThat(both.runGuardScripts()).isTrue();
    }

    @Test
    void gate_no_scripts_skips_gate_scripts(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--guard", "--no-scripts"));
        assertThat(sel.guard()).isTrue();
        assertThat(sel.noScripts()).isTrue();
        assertThat(sel.runGuardScripts()).isFalse();
        assertThat(sel.suites()).containsExactly("test", "integration");
    }

    private static void writeGate(Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".jk/scripts"));
        Files.writeString(dir.resolve(".jk/scripts/guard.groovy"), "// gate\n");
    }
}
