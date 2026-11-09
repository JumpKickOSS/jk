// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateLintTest {

    @Test
    void a_tree_lints_every_template_under_it_naming_each_failing_file_and_property(@TempDir Path temp)
            throws Exception {
        template(temp, "java/none/good.g8", "java", "none", "good", "name=x\n");
        template(temp, "java/none/bad.g8", "java", "none", "bad", "name=x\nflavour=y\n");
        template(temp, "kotlin/none/moved.g8", "kotlin", "none", "elsewhere", "name=x\n");

        TemplateLint.Result result = TemplateLint.run(temp, List.of("."));

        assertThat(result.checked()).isEqualTo(3);
        assertThat(result.failures()).hasSize(2);
        assertThat(result.failures().get(0))
                .startsWith(Path.of("java/none/bad.g8", ".jk-template.toml") + ": ")
                .contains("default.properties sets 'flavour'");
        assertThat(result.failures().get(1))
                .startsWith(Path.of("kotlin/none/moved.g8", ".jk-template.toml") + ": ")
                .contains("declares kotlin/none/elsewhere but sits at kotlin/none/moved");
    }

    @Test
    void one_template_directory_is_linted_on_its_own(@TempDir Path temp) throws Exception {
        Path good = template(temp, "good.g8", "java", "none", "good", "name=x\n");
        TemplateLint.Result result = TemplateLint.run(temp, List.of(good.toString()));
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void a_path_with_no_template_fails(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("empty"));
        assertThat(TemplateLint.run(temp, List.of("empty", "missing")).failures())
                .containsExactly("empty: no .jk-template.toml under it", "missing: not a directory");
    }

    /** The command over the official catalogue's tree, which the public catalogue mirrors. */
    @Test
    void every_template_under_templates_passes() {
        Path repo = RepoRoot.find(TemplateLintTest.class);
        TemplateLint.Result result = TemplateLint.run(repo, List.of("templates"));
        assertThat(result.failures()).isEmpty();
        assertThat(result.checked()).isGreaterThanOrEqualTo(10);
    }

    private static Path template(Path root, String rel, String lang, String framework, String name, String props)
            throws Exception {
        Path dir = root.resolve(rel);
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve(JkTemplateToml.FILE_NAME),
                "language = \"" + lang + "\"\nframework = \"" + framework + "\"\nname = \"" + name + "\"\n");
        Files.writeString(dir.resolve("default.properties"), props);
        return dir;
    }
}
