// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandModels;
import cc.jumpkick.cli.HelpRenderer;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.command.project.ExplainCommand;
import cc.jumpkick.command.toolchain.ToolInstallCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Short-flag parity for {@code jk test}: {@code -s}/{@code --suite}, {@code -p}/{@code --profile}. */
class TestCommandOptionsTest {

    private static Invocation parse(String... args) throws Exception {
        return ArgParser.parse(new TestCommand(), List.of(args));
    }

    @Test
    void suite_and_profile_declare_short_forms() {
        List<Opt> opts = new TestCommand().options();
        Opt suite = opts.stream()
                .filter(o -> o.canonicalName().equals("suite"))
                .findFirst()
                .orElseThrow();
        assertThat(suite.names()).containsExactly("-s", "--suite");

        Opt profile = opts.stream()
                .filter(o -> o.canonicalName().equals("profile"))
                .findFirst()
                .orElseThrow();
        assertThat(profile.names()).containsExactly("-p", "--profile");
    }

    @Test
    void short_suite_and_profile_parse_to_canonical_keys() throws Exception {
        Invocation in = parse("-s", "integration", "-p", "ci");
        assertThat(in.values("suite")).containsExactly("integration");
        assertThat(in.value("profile")).contains("ci");
    }

    @Test
    void short_suite_is_repeatable() throws Exception {
        Invocation in = parse("-s", "test", "-s", "integration");
        assertThat(in.values("suite")).containsExactly("test", "integration");
    }

    /** One flag, one meaning, on every verb that builds through the test stage. */
    @Test
    void guard_is_on_every_build_type_verb() {
        for (var verb : List.<Supplier<List<Opt>>>of(
                () -> new BuildCommand().options(),
                () -> new TestCommand().options(),
                () -> new AssemblyCommand().options(),
                () -> new ImageCommand().options(),
                () -> new NativeCommand().options(),
                () -> new ToolInstallCommand().options(),
                () -> new ExplainCommand().options())) {
            assertThat(verb.get().stream()
                            .filter(o -> o.names().contains("--guard"))
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void guard_is_one_option_with_no_alias() throws Exception {
        List<Opt> opts = new TestCommand().options();
        Opt guard = opts.stream()
                .filter(o -> o.names().contains("--guard"))
                .findFirst()
                .orElseThrow();
        assertThat(guard.names()).containsExactly("--guard");
        assertThat(TestCommand.guardRequested(parse("--guard"))).isTrue();
        String help = HelpRenderer.renderHelp(CommandModels.from(new TestCommand(), "jk test", List.of()), false);
        assertThat(help).contains("--guard");
        assertThat(help).doesNotContain("--gate").doesNotContain("--pre-merge");
        Opt buildGuard = new BuildCommand()
                .options().stream()
                        .filter(o -> o.names().contains("--guard"))
                        .findFirst()
                        .orElseThrow();
        assertThat(buildGuard.names()).containsExactly("--guard");
    }

    @Test
    void affected_and_affected_since_both_declared() {
        List<Opt> opts = new TestCommand().options();
        assertThat(opts.stream().anyMatch(o -> o.names().contains("--affected")))
                .isTrue();
        assertThat(opts.stream().anyMatch(o -> o.names().contains("--affected-since")))
                .isTrue();
        assertThat(new BuildCommand().options().stream().anyMatch(o -> o.names().contains("--affected")))
                .isTrue();
    }

    @Test
    void affected_parses_as_flag() throws Exception {
        Invocation in = parse("--affected");
        assertThat(in.isSet("affected")).isTrue();
    }

    @Test
    void test_affected_help_says_list_only() {
        String help = HelpRenderer.renderHelp(CommandModels.from(new TestCommand(), "jk test", List.of()), false);
        assertThat(help).contains("Ranked WIP tests (does not run)");
        assertThat(help).contains("Ranked tests since ref (no run)");
        for (String line : help.split("\\R", -1)) {
            if (line.contains("--affected")) {
                assertThat(line).doesNotContain("testing modules");
            }
        }
    }

    @Test
    void scripts_only_and_no_scripts_are_on_test_and_build() {
        assertThat(new TestCommand().options().stream().map(Opt::names).anyMatch(n -> n.contains("--scripts-only")))
                .isTrue();
        assertThat(new TestCommand().options().stream().map(Opt::names).anyMatch(n -> n.contains("--no-scripts")))
                .isTrue();
        assertThat(new BuildCommand().options().stream().map(Opt::names).anyMatch(n -> n.contains("--scripts-only")))
                .isTrue();
        assertThat(new BuildCommand().options().stream().map(Opt::names).anyMatch(n -> n.contains("--no-scripts")))
                .isTrue();
    }
}
