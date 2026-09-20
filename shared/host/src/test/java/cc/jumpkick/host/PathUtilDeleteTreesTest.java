// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The pooled delete: several roots at once, post-order inside each, one tally for all. */
class PathUtilDeleteTreesTest {

    /** A tree of {@code depth} levels, {@code fanout} directories and files per level. */
    private static long plant(Path root, int depth, int fanout) throws IOException {
        Files.createDirectories(root);
        long files = 0;
        for (int i = 0; i < fanout; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "x".repeat(10 + i));
            files++;
            if (depth > 1) files += plant(root.resolve("d" + i), depth - 1, fanout);
        }
        return files;
    }

    @Test
    void three_roots_go_at_once_and_the_tally_is_their_sum(@TempDir Path tmp) throws Exception {
        List<Path> roots = new ArrayList<>();
        long expectedFiles = 0;
        for (int r = 0; r < 3; r++) {
            Path root = tmp.resolve("root" + r);
            expectedFiles += plant(root, 4, 3);
            roots.add(root);
        }
        PathUtil.Removed measured = PathUtil.measureTrees(roots);
        assertThat(measured.files()).isEqualTo(expectedFiles);

        var tally = new PathUtil.Removed();
        PathUtil.deleteTrees(roots, tally);

        for (Path root : roots) assertThat(root).doesNotExist();
        assertThat(tally.files()).isEqualTo(expectedFiles);
        assertThat(tally.bytes()).isEqualTo(measured.bytes());
    }

    @Test
    void every_width_removes_the_same_tree_and_counts_the_same_files(@TempDir Path tmp) throws Exception {
        for (int width : new int[] {1, 2, 8}) {
            Path root = tmp.resolve("w" + width);
            long planted = plant(root, 4, 3);
            var tally = new PathUtil.Removed();
            PathUtil.deleteTrees(List.of(root), tally, width);
            assertThat(root).as("width " + width).doesNotExist();
            assertThat(tally.files()).as("width " + width).isEqualTo(planted);
        }
    }

    @Test
    void a_mixed_set_of_roots_is_fine(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("dir");
        plant(dir, 2, 2);
        Path file = Files.writeString(tmp.resolve("lone.txt"), "lone");
        Path absent = tmp.resolve("never-was");

        var tally = new PathUtil.Removed();
        PathUtil.deleteTrees(List.of(dir, file, absent), tally);

        assertThat(dir).doesNotExist();
        assertThat(file).doesNotExist();
        assertThat(tally.files()).isEqualTo(2 + 2 + 2 + 1);
    }

    @Test
    void a_link_inside_the_tree_is_a_leaf_in_the_count_and_in_the_delete(@TempDir Path tmp) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Path tree = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(tree.resolve("own.txt"), "0123456789");
        Symlinks.create(tree.resolve("to-outside"), outside);

        PathUtil.Removed measured = PathUtil.measureTrees(List.of(tree));
        assertThat(measured.files()).as("the link is not a regular file").isEqualTo(1);
        assertThat(measured.bytes()).isEqualTo(10);

        var tally = new PathUtil.Removed();
        PathUtil.deleteTrees(List.of(tree), tally);
        assertThat(tree).doesNotExist();
        assertThat(outside.resolve("keep.txt")).exists();
        assertThat(tally.files()).isEqualTo(1);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // no POSIX permission bits to make a directory unlistable
    void a_failing_subtree_does_not_strand_its_siblings(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("root");
        Path good = root.resolve("good");
        Path bad = root.resolve("bad");
        plant(good, 3, 3);
        plant(bad, 2, 2);
        Path locked = bad.resolve("locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("stuck.txt"), "stuck");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var tally = new PathUtil.Removed();
            assertThatThrownBy(() -> PathUtil.deleteTrees(List.of(root), tally)).isInstanceOf(IOException.class);
            assertThat(good).as("the sibling subtree still went").doesNotExist();
            assertThat(locked.resolve("stuck.txt")).exists();
            assertThat(tally.files()).isEqualTo(3 + 9 + 27 + 2 + 4);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
