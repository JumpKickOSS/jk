// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.model.command.Invocation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code jk guard} subcommands are positionals the parser must let through; the command routes them. */
class GuardCommandParseTest {

    private static Invocation parse(String... args) throws Exception {
        return ArgParser.parse(new GuardCommand(), List.of(args));
    }

    @Test
    void explain_with_and_without_an_id_and_with_a_schema() throws Exception {
        assertThat(parse("explain").positionals()).containsExactly("explain");
        assertThat(parse("explain", "one-digest-surface").positionals())
                .containsExactly("explain", "one-digest-surface");
        Invocation schema = parse("explain", "--schema", "forbid");
        assertThat(schema.value("schema")).contains("forbid");
        assertThat(parse("--schema", "guard-test").value("schema")).contains("guard-test");
    }

    @Test
    void freeze_takes_the_id_and_its_reason() throws Exception {
        Invocation in = parse("freeze", "one-digest-surface", "--reason", "legacy sites");
        assertThat(in.positionals()).containsExactly("freeze", "one-digest-surface");
        assertThat(in.value("reason")).contains("legacy sites");
        assertThat(parse("freeze", "x", "--retire").isSet("retire")).isTrue();
    }

    @Test
    void bare_guard_is_the_build_with_the_gate_on() throws Exception {
        assertThat(parse().positionals()).isEmpty();
        assertThat(parse("--profile", "ci").value("profile")).contains("ci");
        assertThatThrownBy(() -> parse("--bogus")).hasMessageContaining("bogus");
    }

    @Test
    void test_is_a_bare_positional() throws Exception {
        assertThat(parse("test").positionals()).containsExactly("test");
    }

    @Test
    void commit_msg_takes_the_file_and_hooks_takes_install_with_replace() throws Exception {
        assertThat(parse("commit-msg", ".git/COMMIT_EDITMSG").positionals())
                .containsExactly("commit-msg", ".git/COMMIT_EDITMSG");
        assertThat(parse("hooks").positionals()).containsExactly("hooks");
        Invocation install = parse("hooks", "install", "--replace");
        assertThat(install.positionals()).containsExactly("hooks", "install");
        assertThat(install.isSet("replace")).isTrue();
    }
}
