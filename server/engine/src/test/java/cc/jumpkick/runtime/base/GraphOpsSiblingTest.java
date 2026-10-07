// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A query that names a workspace member finds its directory, since a sibling has no lock row. */
class GraphOpsSiblingTest {

    @Test
    void a_member_is_found_by_name_or_coordinate_from_the_root_or_a_member(@TempDir Path root) throws IOException {
        workspace(root);
        assertThat(GraphOps.siblingNamed(root, "core")).isEqualTo("libs/core");
        assertThat(GraphOps.siblingNamed(root, "com.example:core")).isEqualTo("libs/core");
        assertThat(GraphOps.siblingNamed(root, "com.example:core:1.0.0")).isEqualTo("libs/core");
        assertThat(GraphOps.siblingNamed(root.resolve("app"), "core")).isEqualTo("libs/core");
    }

    @Test
    void a_coordinate_no_member_has_is_not_a_sibling(@TempDir Path root) throws IOException {
        workspace(root);
        assertThat(GraphOps.siblingNamed(root, "guava")).isNull();
        assertThat(GraphOps.siblingNamed(root, "org.other:core")).isNull();
    }

    @Test
    void a_standalone_project_has_no_siblings(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), member("app"));
        assertThat(GraphOps.siblingNamed(dir, "app")).isNull();
    }

    private static void workspace(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "1.0.0"

                [workspace]
                modules = ["app", "libs/core"]
                """);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/jk.toml"), member("app"));
        Files.createDirectories(root.resolve("libs/core"));
        Files.writeString(root.resolve("libs/core/jk.toml"), member("core"));
    }

    private static String member(String name) {
        return "group = \"com.example\"\nname = \"" + name + "\"\nversion = \"1.0.0\"\n";
    }
}
