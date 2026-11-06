// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The things {@code jk new -t} decides before it ever reaches the engine. Everything
 * past these arms needs a running engine and lives in {@code NewCommandTest}.
 */
class NewTemplateTest {

    @BeforeEach
    @AfterEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    private static NewTemplate.Args args(List<String> params) {
        return new NewTemplate.Args("hello", params, null, null, null, null, null, Path.of("/tmp"), null, true, null);
    }

    @Test
    void a_param_without_an_equals_is_a_usage_error_and_never_reaches_the_engine() {
        int[] code = {0};
        String err = TestAnsi.strip(Capture.stderr(() -> code[0] = NewTemplate.apply(args(List.of("oops")))));
        assertThat(code[0]).isEqualTo(Exit.USAGE);
        assertThat(err).contains("--param expects key=value, got: oops");
    }

    @Test
    void a_leading_equals_is_an_empty_key_and_is_rejected_too() {
        int[] code = {0};
        String err = TestAnsi.strip(Capture.stderr(() -> code[0] = NewTemplate.apply(args(List.of("=v")))));
        assertThat(code[0]).isEqualTo(Exit.USAGE);
        assertThat(err).contains("--param expects key=value");
    }

    @Test
    void a_ref_naming_a_template_directory_becomes_its_absolute_path(@TempDir Path cwd) throws IOException {
        Files.createDirectories(cwd.resolve("g8/src/main/g8"));
        Files.createDirectories(cwd.resolve("described"));
        Files.writeString(cwd.resolve("described/.jk-template.toml"), "name = \"described\"\n");
        Files.createDirectories(cwd.resolve("props"));
        Files.writeString(cwd.resolve("props/default.properties"), "name=props\n");
        Files.createDirectories(cwd.resolve("app/src"));

        assertThat(NewTemplate.localRef("./g8", cwd))
                .isEqualTo(cwd.resolve("g8").toString());
        assertThat(NewTemplate.localRef("described", cwd))
                .isEqualTo(cwd.resolve("described").toString());
        assertThat(NewTemplate.localRef("props", cwd))
                .isEqualTo(cwd.resolve("props").toString());
        // Not a template, or not there: the catalogue, a remote or the engine decides.
        assertThat(NewTemplate.localRef("app", cwd)).isEqualTo("app");
        assertThat(NewTemplate.localRef("spring-boot/hello", cwd)).isEqualTo("spring-boot/hello");
        assertThat(NewTemplate.localRef("https://github.com/acme/t.g8", cwd)).isEqualTo("https://github.com/acme/t.g8");
    }
}
