// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.pipeline.BuildFlags;
import cc.jumpkick.command.pipeline.BuildVerbCommand;
import cc.jumpkick.model.Profiles;
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
 * {@link BuildFlags#testSelection}: the resolved selection is final ({@code tagsResolved})
 * exactly when a layer spoke — baseline tags, a present profile key (including {@code = []} to
 * clear), or a CLI flag. Silent runs stay unresolved so the engine can still fold
 * per-module {@code [test]} tags for workspace members.
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
    void profile_empty_list_clears_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]

                [profiles.ci]
                exclude-tags = []
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "-p", "ci"));
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void explicit_include_overrides_a_baseline_exclude_of_the_same_tag(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["integration", "slow", "bench"]
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--include-tags", "slow"));
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
        var sel = BuildFlags.testSelection(
                parse("-C", dir.toString(), "--include-tags", "slow", "--exclude-tags", "slow"));
        assertThat(sel.excludeTags()).containsExactly("slow");
    }

    /** From inside a member directory the root's tags are still the baseline layer, whatever the member lists. */
    @Test
    void member_directory_reads_the_workspace_roots_tags(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                name = "ws"
                group = "t"
                version = "0.0.1"
                java = 25

                [workspace]
                modules = ["member"]

                [test]
                exclude-tags = ["slow"]
                guard-suites = ["test", "integration"]
                """);
        Path member = Files.createDirectories(dir.resolve("member"));
        writeToml(member, """
                [test]
                exclude-tags = ["bench"]
                include-tags = ["unit"]
                """);

        var sel = BuildFlags.testSelection(parse("-C", member.toString()));
        assertThat(sel.excludeTags()).containsExactly("slow");
        assertThat(sel.includeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();

        var gate = BuildFlags.testSelection(parse("-C", member.toString(), "--guard"));
        assertThat(gate.suites()).containsExactly("test", "integration");
    }

    @Test
    void baseline_tags_are_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow"]
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString()));
        assertThat(sel.excludeTags()).containsExactly("slow");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void cli_blank_value_clears_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow"]
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--exclude-tags", ""));
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void all_clears_baseline_tags_and_is_final(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["slow", "integration"]
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--all"));
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
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "--all", "--exclude-tags", "bench"));
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.excludeTags()).containsExactly("bench");
        assertThat(sel.tagsResolved()).isTrue();
    }

    @Test
    void a_profiles_all_suites_widens_the_suites_and_keeps_its_tags(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["integration"]

                [profiles.integration]
                include-tags = ["integration"]
                exclude-tags = []
                all-suites = true
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "-p", "integration"));
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.includeTags()).containsExactly("integration");
        assertThat(sel.excludeTags()).isEmpty();

        var fast = BuildFlags.testSelection(parse("-C", dir.toString()));
        assertThat(fast.allSuites()).as("the switch is the profile's alone").isFalse();
    }

    @Test
    void a_named_suite_or_the_gate_wins_over_a_profiles_all_suites(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [profiles.integration]
                include-tags = ["integration"]
                all-suites = true
                """);
        var named = BuildFlags.testSelection(parse("-C", dir.toString(), "-p", "integration", "-s", "e2e"));
        assertThat(named.allSuites()).isFalse();
        assertThat(named.suites()).containsExactly("e2e");

        var gate = BuildFlags.testSelection(parse("-C", dir.toString(), "-p", "integration", "--guard"));
        assertThat(gate.allSuites()).isFalse();
        assertThat(gate.suites()).containsExactly("test", "integration");
    }

    @Test
    void a_profiles_all_suites_must_be_a_boolean(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [profiles.integration]
                all-suites = "yes"
                """);
        assertThatThrownBy(() -> BuildFlags.testSelection(parse("-C", dir.toString(), "-p", "integration")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profiles.integration.all-suites must be true or false");
    }

    @Test
    void an_inheriting_profile_gets_its_parents_tags_and_all_suites(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [test]
                exclude-tags = ["integration"]

                [profiles.integration]
                include-tags = ["integration"]
                exclude-tags = []
                all-suites = true

                [profiles.nightly]
                inherits = "integration"
                """);
        var sel = BuildFlags.testSelection(parse("-C", dir.toString(), "-p", "nightly"));
        assertThat(sel.includeTags()).containsExactly("integration");
        assertThat(sel.excludeTags()).isEmpty();
        assertThat(sel.allSuites()).isTrue();
        assertThat(sel.tagsResolved()).isTrue();
    }

    /**
     * {@code --no-profile} reaches the engine as the no-profile marker, so the engine applies none of
     * the profile ({@code env}, {@code jvm-args}, {@code javac}) and resolves tags without it, which
     * leaves the selection itself unresolved.
     */
    @Test
    void no_profile_sends_the_engine_no_profile(@TempDir Path dir) throws Exception {
        writeToml(dir, """
                [profiles.integration]
                include-tags = ["integration"]
                env = { ONLY_HERE = "1" }
                """);
        var flags = BuildFlags.from(parse("-C", dir.toString(), "-p", "integration", "-n"));
        assertThat(flags.profile()).isEqualTo(Profiles.NO_PROFILE);
        assertThat(flags.testSelection().includeTags()).isEmpty();
        assertThat(flags.testSelection().tagsResolved()).isFalse();
        assertThat(BuildFlags.from(parse("-C", dir.toString(), "-n")).profile())
                .as("not even the CI-selected profile")
                .isEqualTo(Profiles.NO_PROFILE);
        assertThat(BuildFlags.from(parse("-C", dir.toString(), "-p", "integration"))
                        .profile())
                .isEqualTo("integration");
    }

    @Test
    void silent_run_stays_unresolved(@TempDir Path dir) throws Exception {
        writeToml(dir, "");
        var sel = BuildFlags.testSelection(parse("-C", dir.toString()));
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
        var guard = BuildFlags.testSelection(parse("-C", dir.toString(), "--guard"));
        assertThat(guard.guard()).isTrue();
        assertThat(guard.suites()).containsExactly("test", "integration");
        assertThat(guard.excludeTags()).containsExactly("slow", "network", "bench");
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
