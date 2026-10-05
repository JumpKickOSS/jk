// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A node build at a workspace root reads its {@code [node] inputs}, not the whole workspace. */
class NodeKeysScopeTest {

    @Test
    void a_scoped_build_reads_only_its_inputs(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("package.json"), "{}");
        Files.createDirectories(root.resolve("src/main/js"));
        Files.writeString(root.resolve("src/main/js/app.js"), "a");
        Files.createDirectories(root.resolve("core/src/main/java"));
        Files.writeString(root.resolve("core/src/main/java/A.java"), "class A {}");
        List<String> scope = List.of("package.json", "src/main/js");

        var before = NodeKeys.scoped(root, scope, List.of("dist"));
        Files.writeString(root.resolve("core/src/main/java/A.java"), "class A { int x; }");
        assertThat(NodeKeys.scoped(root, scope, List.of("dist")))
                .as("a Java edit elsewhere in the workspace is not the front end's input")
                .isEqualTo(before)
                .containsKeys("input:package.json", "input:src/main/js/app.js")
                .doesNotContainKey("input:core/src/main/java/A.java");
        Files.writeString(root.resolve("src/main/js/app.js"), "b");
        assertThat(NodeKeys.scoped(root, scope, List.of("dist"))).isNotEqualTo(before);
        assertThat(NodeKeys.scoped(root, List.of(), List.of("dist")))
                .as("unscoped, the build reads the whole tree")
                .containsKey("core/src/main/java/A.java");
    }
}
