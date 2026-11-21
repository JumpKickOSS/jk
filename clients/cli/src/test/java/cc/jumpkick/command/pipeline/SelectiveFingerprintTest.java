// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code jk selective run} counts as a change to a module: every input root its build and its
 * tests read, and the workspace root's manifest it inherits; an upstream sibling's tests are not.
 */
class SelectiveFingerprintTest {

    private static Path workspace(Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["api", "core"]
                """);
        for (String m : new String[] {"api", "core"}) {
            Path dir = Files.createDirectories(root.resolve(m));
            Files.writeString(dir.resolve("jk.toml"), "name = \"" + m + "\"\n");
            Files.createDirectories(dir.resolve("src/ex"));
            Files.writeString(dir.resolve("src/ex/" + m + ".java"), "package ex; class " + m + " {}\n");
            Files.createDirectories(dir.resolve("resources"));
            Files.writeString(dir.resolve("resources/app.properties"), "a=1\n");
            Files.createDirectories(dir.resolve("test/src/ex"));
            Files.writeString(dir.resolve("test/src/ex/" + m + "Test.java"), "package ex; class " + m + "Test {}\n");
            Files.createDirectories(dir.resolve("test/resources"));
            Files.writeString(dir.resolve("test/resources/fixture.json"), "{}\n");
        }
        return root.resolve("api");
    }

    @Test
    void an_edit_to_tests_resources_or_test_resources_dirties_the_module(@TempDir Path root) throws Exception {
        Path api = workspace(root);
        for (String rel :
                List.of("test/src/ex/apiTest.java", "resources/app.properties", "test/resources/fixture.json")) {
            String before = SelectiveCommand.fingerprint(root, api, List.of());
            Files.writeString(api.resolve(rel), "// edited\n", StandardOpenOption.APPEND);
            assertThat(SelectiveCommand.fingerprint(root, api, List.of()))
                    .as(rel)
                    .isNotEqualTo(before);
        }
    }

    @Test
    void an_edit_to_the_workspace_root_manifest_dirties_a_member(@TempDir Path root) throws Exception {
        Path api = workspace(root);
        String before = SelectiveCommand.fingerprint(root, api, List.of());
        Files.writeString(
                root.resolve("jk.toml"),
                "\n[dependencies]\n\"org.slf4j:slf4j-api\" = \"2.0.17\"\n",
                StandardOpenOption.APPEND);
        assertThat(SelectiveCommand.fingerprint(root, api, List.of())).isNotEqualTo(before);
    }

    @Test
    void an_upstream_siblings_main_files_count_and_its_tests_do_not(@TempDir Path root) throws Exception {
        Path api = workspace(root);
        List<Path> upstreams = List.of(root.resolve("core"));
        String before = SelectiveCommand.fingerprint(root, api, upstreams);

        Files.writeString(root.resolve("core/test/src/ex/coreTest.java"), "package ex; class coreTest { int x; }\n");
        assertThat(SelectiveCommand.fingerprint(root, api, upstreams))
                .as("core's tests do not reach api")
                .isEqualTo(before);

        Files.writeString(root.resolve("core/resources/app.properties"), "a=2\n");
        assertThat(SelectiveCommand.fingerprint(root, api, upstreams))
                .as("core's resources are on api's classpath")
                .isNotEqualTo(before);
    }
}
