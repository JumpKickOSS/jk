// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.List;
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
}
