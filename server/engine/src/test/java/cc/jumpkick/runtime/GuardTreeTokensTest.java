// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tree lane's key covers what its text rules read: one walk for the run and the forecast. */
class GuardTreeTokensTest {

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static List<String> rels(Path root) throws Exception {
        return GuardKeys.treeLaneTokens(root).stream()
                .map(t -> t.substring("tree:".length(), t.lastIndexOf(':')))
                .toList();
    }

    @Test
    void jk_state_and_a_package_named_target_are_read_and_output_is_not(@TempDir Path root) throws Exception {
        write(root.resolve("jk.toml"), "name = \"m\"\n");
        write(root.resolve(".jk/ci/ci-bootstrap-version"), "1\n");
        write(root.resolve("src/main/java/com/acme/target/Foo.java"), "class Foo {}\n");
        write(root.resolve("target/classes/Out.txt"), "built\n");

        assertThat(rels(root))
                .contains(".jk/ci/ci-bootstrap-version", "jk.toml", "src/main/java/com/acme/target/Foo.java")
                .noneMatch(r -> r.startsWith("target/"));
    }

    @Test
    void an_edit_under_a_package_named_target_moves_the_key(@TempDir Path root) throws Exception {
        Path foo = root.resolve("src/main/java/com/acme/target/Foo.java");
        write(foo, "class Foo {}\n");
        List<String> before = GuardKeys.treeLaneTokens(root);
        write(foo, "class Foo { int x; }\n");
        assertThat(GuardKeys.treeLaneTokens(root)).isNotEqualTo(before);
    }
}
