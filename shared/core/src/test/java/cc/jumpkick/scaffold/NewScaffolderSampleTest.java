// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Java samples are a record + optional {@code @NullMarked}, never {@code @Data}. */
class NewScaffolderSampleTest {

    @Test
    void java_sample_is_a_record_without_lombok(@TempDir Path dir) throws IOException {
        NewScaffolder.write(library(dir, List.of()));

        String calc = Files.readString(dir.resolve("src/main/java/com/example/Calc.java"));
        assertThat(calc).contains("public record Calc(int value)");
        assertThat(calc).doesNotContain("lombok").doesNotContain("@Data");
        assertThat(dir.resolve("src/main/java/com/example/package-info.java")).doesNotExist();
        assertThat(dir.resolve("AGENTS.md")).exists();
        assertThat(Files.readString(dir.resolve("AGENTS.md"))).contains("jk manual");
    }

    @Test
    void workspace_module_skips_agents_and_gitignore(@TempDir Path dir) throws IOException {
        NewScaffolder.write(library(dir, List.of()), false);
        assertThat(dir.resolve("jk.toml")).exists();
        assertThat(dir.resolve("AGENTS.md")).doesNotExist();
        assertThat(dir.resolve(".gitignore")).doesNotExist();
    }

    @Test
    void plugin_project_writes_agents_guide(@TempDir Path dir) throws IOException {
        NewInputs inputs = new NewInputs(
                "com.example",
                "foo",
                "25",
                25,
                25,
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                true,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                Optional.empty(),
                List.of(),
                true,
                dir);
        NewScaffolder.write(inputs, true);
        assertThat(dir.resolve("AGENTS.md")).exists();
        assertThat(dir.resolve("src/main/resources/templates/java/foo/hello.g8/src/main/g8/AGENTS.md"))
                .exists();
    }

    @Test
    void jspecify_writes_nullmarked_package_info(@TempDir Path dir) throws IOException {
        NewScaffolder.write(library(dir, List.of("jspecify")));

        String info = Files.readString(dir.resolve("src/main/java/com/example/package-info.java"));
        assertThat(info).contains("@org.jspecify.annotations.NullMarked");
        assertThat(info).contains("package com.example;");
    }

    private static NewInputs library(Path dir, List<String> deps) {
        return new NewInputs(
                "com.example",
                "widget",
                "25",
                25,
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                Optional.empty(),
                deps,
                true,
                dir);
    }
}
