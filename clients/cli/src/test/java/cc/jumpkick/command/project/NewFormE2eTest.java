// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk new -t} with no terminal, through the engine: {@code --help} lists the template's
 * questions from its form, defaults apply, and a required question with no default is an error that
 * names the flag answering it.
 */
@Tag("integration")
class NewFormE2eTest {

    @Test
    void help_lists_the_templates_questions(@TempDir Path dir) throws Exception {
        Path template = template(dir);
        String[] out = new String[1];
        int[] code = new int[1];
        Capture.stderr(
                () -> out[0] = Capture.stdout(() -> code[0] = Jk.execute("new", "-t", template.toString(), "--help")));

        assertThat(code[0]).isZero();
        assertThat(out[0])
                .contains("Template " + template)
                .contains("database: none|postgres (required)  # Database")
                .contains("native: true|false = false  # Native binary");
    }

    @Test
    void a_required_question_without_a_default_names_its_flag_and_an_answer_scaffolds(@TempDir Path dir)
            throws Exception {
        Path template = template(dir);
        Path missing = dir.resolve("missing");
        int[] code = new int[1];
        String err = Capture.stderr(() ->
                Capture.stdout(() -> code[0] = Jk.execute("new", missing.toString(), "-t", template.toString(), "-N")));

        assertThat(code[0]).isNotZero();
        assertThat(err).contains("database needs a value: -p database=none|postgres");
        assertThat(missing.resolve("jk.toml")).doesNotExist();

        Path made = dir.resolve("made");
        String log = Capture.stderr(() -> Capture.stdout(() -> code[0] =
                Jk.execute("new", made.toString(), "-t", template.toString(), "-N", "-p", "database=postgres")));
        assertThat(code[0]).as(log).isZero();
        assertThat(made.resolve("answers.txt"))
                .content()
                .contains("db = \"postgres\"")
                .doesNotContain("native");
    }

    /** A template asking a required choice and a defaulted bool. */
    private static Path template(Path dir) throws IOException {
        Path g8 = dir.resolve("templates/java/none/asking.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "asking"
                layouts = ["traditional"]

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres"]

                [[ask]]
                key = "native"
                label = "Native binary"
                kind = "bool"
                default = false
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), """
                group = "com.example"
                name = "$name$"
                version = "0.1.0"
                java = 25
                """);
        Files.writeString(g8.resolve("src/main/g8/answers.txt"), """
                db = "$database$"
                $if(native.truthy)$
                native
                $endif$
                """);
        return g8;
    }
}
