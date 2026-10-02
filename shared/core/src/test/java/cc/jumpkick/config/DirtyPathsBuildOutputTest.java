// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a build writes into a module's {@code target/} is not a change to the module. */
class DirtyPathsBuildOutputTest {

    @Test
    void a_module_s_build_output_is_not_a_change(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "name = \"ws\"\n");
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/jk.toml"), "name = \"app\"\n");
        Files.createDirectories(root.resolve("legacy"));
        Files.writeString(root.resolve("legacy/pom.xml"), "<project/>");

        assertThat(DirtyPaths.withoutBuildOutput(
                        root,
                        List.of(
                                "app/target/test-classes/BarTest.class",
                                "legacy/target/classes/A.class",
                                "target/jk-results.md",
                                "app/src/main/java/com/acme/target/Aim.java",
                                "docs/target/notes.md",
                                "app/src/main/java/com/acme/App.java")))
                .containsExactly(
                        "app/src/main/java/com/acme/target/Aim.java",
                        "docs/target/notes.md",
                        "app/src/main/java/com/acme/App.java");
    }
}
