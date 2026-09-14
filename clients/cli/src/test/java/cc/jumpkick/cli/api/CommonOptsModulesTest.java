// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.args.ArgParser;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code -m}/{@code --modules} accumulates: a user who repeats the flag the way every other CLI
 * allows selects every module named, the same as one comma-joined value.
 */
class CommonOptsModulesTest {

    private static final Command SELECTIVE = new Command() {
        @Override
        public String name() {
            return "demo";
        }

        @Override
        public String description() {
            return "demo";
        }

        @Override
        public List<Opt> options() {
            return CommonOpts.moduleSelection();
        }

        @Override
        public List<Param> parameters() {
            return List.of();
        }
    };

    private static Invocation parse(String... args) throws Exception {
        return ArgParser.parse(SELECTIVE, List.of(args));
    }

    @Test
    void a_repeated_selector_accumulates_in_the_order_given() throws Exception {
        assertThat(CommonOpts.modulesSpec(parse("-m", "a", "-m", "b"))).isEqualTo("a,b");
        assertThat(CommonOpts.modulesSpec(parse("--modules", "server/*", "-m", "clients/cli")))
                .isEqualTo("server/*,clients/cli");
    }

    @Test
    void a_repeated_selector_and_a_comma_joined_one_read_the_same() throws Exception {
        assertThat(CommonOpts.modulesSpec(parse("-m", "a", "-m", "b")))
                .isEqualTo(CommonOpts.modulesSpec(parse("-m", "a,b")));
    }

    @Test
    void an_absent_selector_is_null_and_the_help_names_both_spellings() throws Exception {
        assertThat(CommonOpts.modulesSpec(parse())).isNull();
        Opt modules = CommonOpts.moduleSelection().get(0);
        assertThat(modules.repeatable()).isTrue();
        assertThat(modules.description()).contains("repeatable");
    }
}
