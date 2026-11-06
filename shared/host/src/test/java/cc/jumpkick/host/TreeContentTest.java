// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeContentTest {

    @Test
    void dot_files_and_jk_named_files_are_content() {
        assertThat(TreeContent.isContent("templates/java/app/default.g8/.jk-template.toml"))
                .isTrue();
        assertThat(TreeContent.isContent(".jk-template.toml")).isTrue();
        assertThat(TreeContent.isContent(".DS_Store")).isTrue();
        assertThat(TreeContent.isContent("META-INF/.hidden")).isTrue();
        assertThat(TreeContent.isContent("com/example/App.class")).isTrue();
    }

    @Test
    void stamps_and_files_under_a_scratch_directory_are_not() {
        assertThat(TreeContent.isContent(".jstamp")).isFalse();
        assertThat(TreeContent.isContent("BOOT-INF/classes/.gstamp")).isFalse();
        assertThat(TreeContent.isContent(".jk-quarkus-bootstrap/m2/a.jar")).isFalse();
        assertThat(TreeContent.isContent("lib/.jk-staging/x.class")).isFalse();
        assertThat(TreeContent.isContent("lib\\.jk-staging\\x.class")).isFalse();
        assertThat(TreeContent.isContent(Path.of(".jk-staging", "x.class"))).isFalse();
    }

    @Test
    void the_walk_yields_exactly_the_content_files(@TempDir Path root) throws Exception {
        write(root.resolve("a/A.class"));
        write(root.resolve("t/.jk-template.toml"));
        write(root.resolve(".DS_Store"));
        write(root.resolve(".jstamp"));
        write(root.resolve(".jk-scratch/deep/x.jar"));
        write(root.resolve("templates/x.txt"));

        List<String> seen = new ArrayList<>();
        TreeContent.forEach(root, (rel, file, attrs) -> {
            assertThat(TreeContent.isContent(rel)).as(rel).isTrue();
            seen.add(rel);
        });
        assertThat(seen).containsExactlyInAnyOrder("a/A.class", "t/.jk-template.toml", ".DS_Store", "templates/x.txt");

        List<String> skipped = new ArrayList<>();
        TreeContent.forEach(root, dir -> dir.equals(root.resolve("templates")), (rel, file, attrs) -> skipped.add(rel));
        assertThat(skipped).containsExactlyInAnyOrder("a/A.class", "t/.jk-template.toml", ".DS_Store");
    }

    @Test
    void a_walk_rooted_in_a_scratch_directory_still_reads_it(@TempDir Path tmp) throws Exception {
        Path scratch = tmp.resolve(".jk-step");
        write(scratch.resolve("out/A.class"));
        List<String> seen = new ArrayList<>();
        TreeContent.forEach(scratch, (rel, file, attrs) -> seen.add(rel));
        assertThat(seen).containsExactly("out/A.class");
    }

    private static void write(Path file) throws Exception {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, file.getFileName().toString());
    }
}
