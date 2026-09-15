// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Java samples are a record + optional {@code @NullMarked}, never {@code @Data}. */
class NewScaffolderSampleTest {

    /** Every coordinate's newest stable is this number: no test reaches a repository. */
    private static final ScaffoldVersions VERSIONS = (group, artifact) -> "1.2.3";

    @Test
    void jspecify_pick_writes_a_pinned_catalog_one_liner(@TempDir Path dir) throws IOException {
        NewScaffolder.write(library(dir, List.of("jspecify")), true, VERSIONS);
        String toml = Files.readString(dir.resolve("jk.toml"));
        assertThat(toml).contains("jspecify = \"1.2.3\"").doesNotContain("latest");
    }

    @Test
    void java_sample_is_a_record_without_lombok(@TempDir Path dir) throws IOException {
        NewScaffolder.write(library(dir, List.of()), true, VERSIONS);

        String calc = Files.readString(dir.resolve("src/main/java/com/example/Calc.java"));
        assertThat(calc).contains("public record Calc(int value)");
        assertThat(calc).doesNotContain("lombok").doesNotContain("@Data");
        assertThat(dir.resolve("src/main/java/com/example/package-info.java")).doesNotExist();
        assertThat(dir.resolve("AGENTS.md")).exists();
        assertThat(Files.readString(dir.resolve("AGENTS.md"))).contains("jk manual");
    }

    @Test
    void workspace_module_skips_agents_and_gitignore(@TempDir Path dir) throws IOException {
        NewScaffolder.write(library(dir, List.of()), false, VERSIONS);
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
                null,
                null,
                false,
                false,
                true,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                null,
                List.of(),
                true,
                dir);
        NewScaffolder.write(inputs, true, VERSIONS);
        assertThat(dir.resolve("AGENTS.md")).exists();
        assertThat(dir.resolve("src/main/resources/templates/java/foo/hello.g8/src/main/g8/AGENTS.md"))
                .exists();
    }

    @Test
    void jspecify_writes_nullmarked_package_info(@TempDir Path dir) throws IOException {
        NewScaffolder.write(library(dir, List.of("jspecify")), true, VERSIONS);

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
                null,
                null,
                false,
                false,
                NewInputs.Language.JAVA,
                Layout.TRADITIONAL,
                null,
                deps,
                true,
                dir);
    }
}
