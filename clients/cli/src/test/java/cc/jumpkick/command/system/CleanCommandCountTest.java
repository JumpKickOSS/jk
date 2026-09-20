// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bar's total is the pre-count, and the pre-count is exactly what the delete then tallies. */
class CleanCommandCountTest {

    private static void plant(Path dir, int files) throws IOException {
        Files.createDirectories(dir);
        for (int i = 0; i < files; i++) Files.writeString(dir.resolve("f" + i + ".class"), "x".repeat(i + 1));
    }

    @Test
    void the_count_before_a_full_clean_equals_the_tally_after_it(@TempDir Path ws) throws Exception {
        plant(ws.resolve("target/classes/a/b/c"), 7);
        plant(ws.resolve("target/app/classes/d"), 5);
        plant(ws.resolve("app/target/old"), 3);
        Path outside = Files.createDirectories(ws.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Symlinks.create(ws.resolve("target/app/link-out"), outside);

        List<Path> roots = CleanCommand.deleteRoots(ws, List.of(ws, ws.resolve("app")), false);
        PathUtil.Removed counted = PathUtil.measureTrees(roots);
        assertThat(counted.files()).as("regular files only; the link is a leaf").isEqualTo(15);

        var tally = new PathUtil.Removed();
        PathUtil.deleteTrees(roots, tally);

        assertThat(tally.files()).isEqualTo(counted.files());
        assertThat(tally.bytes()).isEqualTo(counted.bytes());
        assertThat(ws.resolve("target")).doesNotExist();
        assertThat(ws.resolve("app/target")).doesNotExist();
        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void keep_artifacts_counts_only_the_intermediates_it_will_delete(@TempDir Path ws) throws Exception {
        plant(ws.resolve("target/app/classes"), 4);
        plant(ws.resolve("target/app/reports"), 2);
        Files.writeString(ws.resolve("target/app/app-1.0.0.jar"), "jar-bytes");

        List<Path> roots = CleanCommand.deleteRoots(ws, List.of(ws, ws.resolve("app")), true);
        assertThat(PathUtil.measureTrees(roots).files()).isEqualTo(6);

        var tally = new PathUtil.Removed();
        PathUtil.deleteTrees(roots, tally);
        assertThat(tally.files()).isEqualTo(6);
        assertThat(ws.resolve("target/app/app-1.0.0.jar")).exists();
    }

    @Test
    void nothing_to_remove_is_a_zero_count(@TempDir Path ws) throws Exception {
        List<Path> roots = CleanCommand.deleteRoots(ws, List.of(ws), false);
        assertThat(PathUtil.measureTrees(roots).files()).isZero();
    }
}
