// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.CommandModels;
import cc.jumpkick.cli.HelpRenderer;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.args.ParseException;
import cc.jumpkick.command.project.ExplainCommand;
import cc.jumpkick.command.toolchain.ToolInstallCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Short-flag parity for {@code jk test}: {@code -s}/{@code --suite}, {@code -p}/{@code --profile}. */
class TestCommandOptionsTest {

    private static Invocation parse(String... args) throws Exception {
        return ArgParser.parse(BuildVerbCommand.test(), List.of(args));
    }

    @Test
    void suite_and_profile_declare_short_forms() {
        List<Opt> opts = BuildVerbCommand.test().options();
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
    void a_class_method_selector_rides_class_and_a_bare_hash_is_refused() throws Exception {
        assertThat(BuildFlags.testSelection(parse("--class", "OrdersTest#refunds", "--class", "BarTest"))
                        .classes())
                .containsExactly("OrdersTest#refunds", "BarTest");
        assertThatThrownBy(() -> BuildFlags.testSelection(parse("--class", "OrdersTest#")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names no method after '#'");
    }

    @Test
    void short_suite_is_repeatable() throws Exception {
        Invocation in = parse("-s", "test", "-s", "integration");
        assertThat(in.values("suite")).containsExactly("test", "integration");
    }

    /** A hermetic JDK root is one flag on every verb that resolves a JDK for a build, install included. */
    @Test
    void jdks_dir_is_on_every_build_type_verb() {
        for (var verb : List.<Supplier<List<Opt>>>of(
                () -> BuildVerbCommand.build().options(),
                () -> BuildVerbCommand.test().options(),
                () -> BuildVerbCommand.image().options(),
                () -> BuildVerbCommand.nativeImage().options(),
                () -> new ToolInstallCommand().options(),
                () -> new ExplainCommand().options())) {
            assertThat(verb.get().stream()
                            .filter(o -> o.canonicalName().equals(CommonOpts.JDKS_DIR))
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void install_parses_the_jdks_dir_it_hands_the_pre_flight_and_the_engine() throws Exception {
        Invocation in = ArgParser.parse(new ToolInstallCommand(), List.of("--jdks-dir", "/opt/jdks", "--skip-tests"));
        assertThat(CommonOpts.jdksDirValue(in)).isEqualTo(Path.of("/opt/jdks"));
    }

    /** One flag, one meaning, on every verb that builds through the test stage. */
    @Test
    void guard_is_on_every_build_type_verb() {
        for (var verb : List.<Supplier<List<Opt>>>of(
                () -> BuildVerbCommand.build().options(),
                () -> BuildVerbCommand.test().options(),
                () -> new AssemblyCommand().options(),
                () -> BuildVerbCommand.image().options(),
                () -> BuildVerbCommand.nativeImage().options(),
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
        List<Opt> opts = BuildVerbCommand.test().options();
        Opt guard = opts.stream()
                .filter(o -> o.names().contains("--guard"))
                .findFirst()
                .orElseThrow();
        assertThat(guard.names()).containsExactly("--guard");
        assertThat(BuildFlags.guardRequested(parse("--guard"))).isTrue();
        String help = HelpRenderer.renderHelp(CommandModels.from(BuildVerbCommand.test(), "jk test", List.of()), false);
        assertThat(help).contains("--guard");
        assertThat(help).doesNotContain("--gate").doesNotContain("--pre-merge");
        Opt buildGuard = BuildVerbCommand.build().options().stream()
                .filter(o -> o.names().contains("--guard"))
                .findFirst()
                .orElseThrow();
        assertThat(buildGuard.names()).containsExactly("--guard");
    }

    @Test
    void affected_and_affected_since_both_declared() {
        List<Opt> opts = BuildVerbCommand.test().options();
        assertThat(opts.stream().anyMatch(o -> o.names().contains("--affected")))
                .isTrue();
        assertThat(opts.stream().anyMatch(o -> o.names().contains("--affected-since")))
                .isTrue();
        assertThat(BuildVerbCommand.build().options().stream()
                        .anyMatch(o -> o.names().contains("--affected")))
                .isTrue();
    }

    @Test
    void affected_parses_as_flag() throws Exception {
        Invocation in = parse("--affected");
        assertThat(in.isSet("affected")).isTrue();
    }

    @Test
    void test_affected_help_says_list_only() {
        String help = HelpRenderer.renderHelp(CommandModels.from(BuildVerbCommand.test(), "jk test", List.of()), false);
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
        assertThat(BuildVerbCommand.test().options().stream()
                        .map(Opt::names)
                        .anyMatch(n -> n.contains("--scripts-only")))
                .isTrue();
        assertThat(BuildVerbCommand.test().options().stream().map(Opt::names).anyMatch(n -> n.contains("--no-scripts")))
                .isTrue();
        assertThat(BuildVerbCommand.build().options().stream()
                        .map(Opt::names)
                        .anyMatch(n -> n.contains("--scripts-only")))
                .isTrue();
        assertThat(BuildVerbCommand.build().options().stream()
                        .map(Opt::names)
                        .anyMatch(n -> n.contains("--no-scripts")))
                .isTrue();
    }

    @Test
    void exclusive_is_a_flag_with_a_short_form_and_generic_help() throws Exception {
        Opt exclusive = BuildVerbCommand.test().options().stream()
                .filter(o -> o.names().contains("--exclusive"))
                .findFirst()
                .orElseThrow();
        assertThat(exclusive.names()).containsExactly("-X", "--exclusive");
        assertThat(exclusive.takesValue()).isFalse();
        assertThat(parse("-m", "app", "-X").isSet("exclusive")).isTrue();
        assertThat(parse("-m", "app", "--exclusive").isSet("exclusive")).isTrue();
        assertThat(parse("-m", "app").isSet("exclusive")).isFalse();
        String help = HelpRenderer.renderHelp(CommandModels.from(BuildVerbCommand.test(), "jk test", List.of()), false);
        assertThat(help).contains("Selected modules only (no prereqs)");
    }

    @Test
    void a_jvm_flag_is_refused_with_a_jvm_arg_hint_instead_of_bundling_into_dash_x() {
        for (String jvm : List.of("-Xmx2g", "-Xms512m", "-Xss4m", "-XX:+UseZGC", "-Xlog:gc")) {
            assertThatThrownBy(() -> parse(jvm))
                    .isInstanceOf(ParseException.class)
                    .hasMessageContaining("is a JVM option")
                    .hasMessageContaining("--jvm-arg=" + jvm)
                    .extracting(e -> ((ParseException) e).kind())
                    .isEqualTo(ParseException.Kind.JVM_OPTION);
        }
    }
}
