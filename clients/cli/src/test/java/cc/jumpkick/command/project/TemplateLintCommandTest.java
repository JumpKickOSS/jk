// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class TemplateLintCommandTest {

    @Test
    void a_clean_template_exits_zero(@TempDir Path temp) throws Exception {
        Path good = template(temp, "java/good.g8", "good", "name=x\n");
        Run run = jk("template", "lint", good.toString());
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.output()).contains("1 template pass lint");
    }

    @Test
    void a_template_with_an_undeclared_property_exits_non_zero_naming_it(@TempDir Path temp) throws Exception {
        template(temp, "java/good.g8", "good", "name=x\n");
        template(temp, "java/bad.g8", "bad", "name=x\nflavour=y\n");
        Run run = jk("-C", temp.toString(), "template", "lint", ".");
        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output())
                .contains("java/bad.g8/.jk-template.toml")
                .contains("default.properties sets 'flavour'")
                .contains("1 failure in 2 templates")
                .doesNotContain("good.g8/.jk-template.toml");
    }

    private static Path template(Path root, String rel, String name, String props) throws Exception {
        Path dir = root.resolve(rel);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".jk-template.toml"), "language = \"java\"\nname = \"" + name + "\"\n");
        Files.writeString(dir.resolve("default.properties"), props);
        return dir;
    }

    private record Run(int exit, String output) {}

    private static Run jk(String... argv) {
        int[] exit = new int[1];
        String[] out = new String[1];
        String err = Capture.stderr(() -> out[0] = Capture.stdout(() -> exit[0] = Jk.execute(argv)));
        return new Run(exit[0], TestAnsi.strip(out[0] + "\n" + err));
    }
}
