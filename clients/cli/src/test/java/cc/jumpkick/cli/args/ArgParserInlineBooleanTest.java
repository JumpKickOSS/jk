// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.args;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A boolean flag given an inline value takes that value: {@code --skip-tests=false} leaves tests on
 * and {@code --no-executable=false} leaves the flag on. A value that is not a boolean is refused.
 */
class ArgParserInlineBooleanTest {

    private static Command cmd(List<Opt> opts) {
        return new Command() {
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
                return opts;
            }

            @Override
            public List<Param> parameters() {
                return List.of();
            }
        };
    }

    private static final Command SKIP = cmd(List.of(CommonOpts.skipTests(), Opt.value("<n>", "count", "--count")));
    private static final Command EXEC =
            cmd(List.of(Opt.flag("executable", "--executable").negate()));

    @Test
    void skip_tests_false_does_not_skip_tests() throws Exception {
        Invocation in = ArgParser.parse(SKIP, List.of("--skip-tests=false"));
        assertThat(in.isSet("skip-tests")).as("--skip-tests=false").isFalse();
    }

    @Test
    void skip_tests_alone_and_true_still_skip() throws Exception {
        assertThat(ArgParser.parse(SKIP, List.of("--skip-tests")).isSet("skip-tests"))
                .isTrue();
        assertThat(ArgParser.parse(SKIP, List.of("--skip-tests=true")).isSet("skip-tests"))
                .isTrue();
        assertThat(ArgParser.parse(SKIP, List.of("-S")).isSet("skip-tests")).isTrue();
    }

    @Test
    void an_abbreviated_flag_honours_its_value_too() throws Exception {
        assertThat(ArgParser.parse(SKIP, List.of("--skip=false")).isSet("skip-tests"))
                .isFalse();
    }

    @Test
    void no_flag_false_does_not_turn_the_flag_off() throws Exception {
        assertThat(ArgParser.parse(EXEC, List.of("--no-executable=false")).flag("executable"))
                .isEqualTo(Optional.of(true));
        assertThat(ArgParser.parse(EXEC, List.of("--no-executable")).flag("executable"))
                .isEqualTo(Optional.of(false));
    }

    @Test
    void any_other_inline_value_on_a_flag_is_refused() {
        assertThatThrownBy(() -> ArgParser.parse(SKIP, List.of("--skip-tests=maybe")))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("--skip-tests takes no value");
        assertThatThrownBy(() -> ArgParser.parse(EXEC, List.of("--no-executable=2")))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void an_inline_value_on_a_value_option_is_unaffected() throws Exception {
        assertThat(ArgParser.parse(SKIP, List.of("--count=false")).value("count"))
                .contains("false");
    }
}
