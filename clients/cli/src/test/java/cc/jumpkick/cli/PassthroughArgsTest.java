// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.CommandDispatch.Split;
import cc.jumpkick.command.interop.GradleCommand;
import cc.jumpkick.command.interop.MvnCommand;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@code jk mvn} and {@code jk gradle} forward. After the command name only the command's own
 * options are jk's; every other token — including spellings jk uses for its globals — reaches the
 * child tool untouched. jk's globals are read before the name.
 */
class PassthroughArgsTest {

    private static Invocation parse(CliCommand cmd, List<String> leading, String... rest) throws Exception {
        return CommandDispatch.parsePassthrough(cmd, new Split(leading, List.of(rest)));
    }

    @Test
    void mvn_dash_v_is_mavens_version_not_jks_verbose() throws Exception {
        Invocation in = parse(new MvnCommand(), List.of(), "-v");

        assertThat(in.positionals()).containsExactly("-v");
        assertThat(in.isSet("verbose")).isFalse();
        assertThat(in.isSet("version")).isFalse();
    }

    @Test
    void gradle_dash_q_stays_on_gradles_command_line() throws Exception {
        Invocation in = parse(new GradleCommand(), List.of(), "-q", "build");

        assertThat(in.positionals()).containsExactly("-q", "build");
        assertThat(in.isSet("quiet")).isFalse();
    }

    @Test
    void mvn_dash_c_is_strict_checksums_and_does_not_eat_the_goal() throws Exception {
        Invocation in = parse(new MvnCommand(), List.of(), "-C", "install");

        assertThat(in.positionals()).containsExactly("-C", "install");
        assertThat(in.value("dir")).isEmpty();
    }

    @Test
    void jks_globals_before_the_command_name_still_apply() throws Exception {
        Invocation in = parse(new MvnCommand(), List.of("-C", "app", "-q"), "install");

        assertThat(in.value("dir")).contains("app");
        assertThat(in.isSet("quiet")).isTrue();
        assertThat(in.positionals()).containsExactly("install");
    }

    @Test
    void the_commands_own_options_are_matched_exactly_among_the_forwarded_tokens() throws Exception {
        Invocation in =
                parse(new MvnCommand(), List.of(), "--no-discover", "-DskipTests", "--no-transfer-progress", "verify");

        assertThat(in.isSet("no-discover")).isTrue();
        assertThat(in.positionals()).containsExactly("-DskipTests", "--no-transfer-progress", "verify");
    }

    @Test
    void help_and_version_after_the_name_belong_to_the_tool() throws Exception {
        Invocation in = parse(new GradleCommand(), List.of(), "--help", "-V");

        assertThat(in.positionals()).containsExactly("--help", "-V");
        assertThat(in.isSet("help")).isFalse();
        assertThat(in.isSet("version")).isFalse();
    }
}
