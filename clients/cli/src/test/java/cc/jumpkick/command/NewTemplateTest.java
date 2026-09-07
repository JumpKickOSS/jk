// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two things {@code jk new --template} decides before it ever reaches the engine. Everything
 * past these arms needs a running engine and lives in {@code NewCommandTest}.
 */
class NewTemplateTest {

    @BeforeEach
    @AfterEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    private static NewTemplate.Args args(List<String> params, boolean plugin) {
        return new NewTemplate.Args("hello", params, null, null, null, null, null, Path.of("/tmp"), plugin, true, true);
    }

    @Test
    void template_and_plugin_cannot_be_combined() {
        int[] code = {0};
        String err = TestAnsi.strip(Capture.stderr(() -> code[0] = NewTemplate.apply(args(List.of(), true))));
        assertThat(code[0]).isEqualTo(Exit.USAGE);
        assertThat(err).contains("--template cannot be combined with --plugin");
    }

    @Test
    void a_param_without_an_equals_is_a_usage_error_and_never_reaches_the_engine() {
        int[] code = {0};
        String err = TestAnsi.strip(Capture.stderr(() -> code[0] = NewTemplate.apply(args(List.of("oops"), false))));
        assertThat(code[0]).isEqualTo(Exit.USAGE);
        assertThat(err).contains("--param expects key=value, got: oops");
    }

    @Test
    void a_leading_equals_is_an_empty_key_and_is_rejected_too() {
        int[] code = {0};
        String err = TestAnsi.strip(Capture.stderr(() -> code[0] = NewTemplate.apply(args(List.of("=v"), false))));
        assertThat(code[0]).isEqualTo(Exit.USAGE);
        assertThat(err).contains("--param expects key=value");
    }
}
