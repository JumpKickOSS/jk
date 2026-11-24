// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A kept walk answers while no directory it listed changed, and walks again the moment one does. */
class TreeWalksTest {

    @TempDir
    Path root;

    @BeforeEach
    void tree() throws Exception {
        TreeWalks.clear();
        write("jk.toml", "");
        write("src/main/java/a/B.java", "class B {}\n");
        write("src/main/java/a/c/D.java", "class D {}\n");
        write("target/classes/a/B.class", "x");
        write("docs/README.md", "# hi\n");
    }

    private void write(String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** Every directory as last written an hour ago, out of the racy window. */
    private void age() throws Exception {
        FileTime old = FileTime.from(Instant.now().minusSeconds(3600));
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isDirectory).toList()) Files.setLastModifiedTime(p, old);
        }
    }

    private List<String> java() {
        return TextView.keepingWalk(root, List.of(Path.of(".")), null).files("**/*.java");
    }

    @Test
    void an_unchanged_tree_is_not_walked_again() throws Exception {
        age();
        List<String> first = java();
        assertThat(first).containsExactly("src/main/java/a/B.java", "src/main/java/a/c/D.java");
        assertThat(java()).as("the kept walk's own matches").isSameAs(first);
    }

    @Test
    void a_file_added_deep_in_the_tree_is_listed() throws Exception {
        age();
        java();
        write("src/main/java/a/c/E.java", "class E {}\n");
        assertThat(java()).contains("src/main/java/a/c/E.java");
    }

    @Test
    void a_deleted_file_is_dropped() throws Exception {
        age();
        java();
        Files.delete(root.resolve("src/main/java/a/c/D.java"));
        assertThat(java()).containsExactly("src/main/java/a/B.java");
    }

    @Test
    void a_walk_that_saw_a_directory_written_just_now_is_not_kept() {
        List<String> first = java();
        assertThat(java()).isEqualTo(first).isNotSameAs(first);
    }

    @Test
    void a_pruned_output_directory_that_becomes_a_module_is_read() throws Exception {
        age();
        assertThat(TextView.keepingWalk(root, List.of(Path.of(".")), null).files("**/*.class"))
                .isEmpty();
        write("target/jk.toml", "");
        assertThat(TextView.keepingWalk(root, List.of(Path.of(".")), null).files("**/*.class"))
                .containsExactly("target/classes/a/B.class");
    }

    @Test
    void a_source_root_that_appears_is_walked() throws Exception {
        age();
        List<Path> sources = List.of(Path.of("src"), Path.of("extra"));
        assertThat(TextView.keepingWalk(root, sources, null).files("**/*.kt")).isEmpty();
        write("extra/K.kt", "val k = 1\n");
        assertThat(TextView.keepingWalk(root, sources, null).files("**/*.kt")).containsExactly("extra/K.kt");
    }
}
