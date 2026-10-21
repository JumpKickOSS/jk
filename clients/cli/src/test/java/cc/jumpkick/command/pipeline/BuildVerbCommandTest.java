// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.cli.args.ParseException;
import cc.jumpkick.model.command.Opt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every build command takes the shared options from one place, once, by how far its goal runs. */
class BuildVerbCommandTest {

    private static final List<BuildVerbCommand> VERBS = List.of(
            BuildVerbCommand.compile(),
            BuildVerbCommand.test(),
            BuildVerbCommand.build(),
            BuildVerbCommand.nativeImage(),
            BuildVerbCommand.image(),
            BuildVerbCommand.train());

    @Test
    void every_build_command_declares_each_option_name_once() {
        for (BuildVerbCommand verb : VERBS) {
            Map<String, Integer> seen = new HashMap<>();
            for (Opt opt : verb.options()) {
                for (String name : opt.names()) seen.merge(name, 1, Integer::sum);
            }
            assertThat(seen).as("jk %s", verb.name()).allSatisfy((name, count) -> assertThat(count)
                    .as("jk %s %s", verb.name(), name)
                    .isEqualTo(1));
        }
    }

    @Test
    void the_shared_options_follow_how_far_the_goal_runs() {
        assertThat(names(BuildVerbCommand.compile()))
                .contains("-p", "-m", "--continue", "--jdks-dir", "--cache-dir", "--skip-node", "--release")
                .doesNotContain("-s", "-w", "--skip-tests", "--guard");
        assertThat(names(BuildVerbCommand.test()))
                .contains("-p", "-m", "-s", "-w", "--guard", "--continue", "--test-failures")
                .doesNotContain("--skip-tests");
        assertThat(names(BuildVerbCommand.build()))
                .contains("-p", "-m", "-s", "-w", "--guard", "--continue", "--test-failures", "--skip-tests");
    }

    @Test
    void compile_parses_the_core_flags_and_refuses_the_test_ones() {
        assertThatCode(() -> ArgParser.parse(
                        BuildVerbCommand.compile(),
                        List.of("-p", "ci", "--continue", "--jdks-dir", "/jdks", "--skip-node", "-m", "app")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ArgParser.parse(BuildVerbCommand.compile(), List.of("--skip-tests")))
                .isInstanceOf(ParseException.class);
        assertThatThrownBy(() -> ArgParser.parse(BuildVerbCommand.test(), List.of("--skip-tests")))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void train_names_its_own_profile_apart_from_the_build_profile() throws Exception {
        var in = ArgParser.parse(BuildVerbCommand.train(), List.of("-p", "ci", "-T", "startup"));
        assertThat(in.value("profile")).contains("ci");
        assertThat(in.value("train-profile")).contains("startup");
    }

    @Test
    void a_verb_that_redeclares_a_shared_option_is_refused() {
        assertThatThrownBy(() -> BuildVerbCommand.requireNoSharedOptions(
                        "demo",
                        BuildFlags.Affected.MODULES,
                        List.of(Opt.value("<name>", "Another profile", "-p", "--profile"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jk demo declares the shared build option -p itself");
        assertThatCode(() -> BuildVerbCommand.requireNoSharedOptions(
                        "demo", BuildFlags.Affected.MODULES, List.of(Opt.flag("Own", "--own"))))
                .doesNotThrowAnyException();
    }

    private static List<String> names(BuildVerbCommand verb) {
        List<String> out = new ArrayList<>();
        for (Opt opt : verb.options()) out.addAll(opt.names());
        return out;
    }
}
