// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link PathUtil#pruneTree} keeps what it is told to and every directory that still holds something. */
class PathUtilPruneTreeTest {

    @Test
    void unkept_files_and_the_directories_they_empty_go_in_one_walk(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("classes"));
        Path kept = write(root.resolve("a/b/Kept.class"));
        Path staleBeside = write(root.resolve("a/b/Stale.class"));
        Path staleDeep = write(root.resolve("gone/deeper/Old.class"));
        Path emptyAlready = Files.createDirectories(root.resolve("empty/inside"));
        Path keptAtRoot = write(root.resolve(".jstamp"));
        Set<Path> keep = Set.of(kept, keptAtRoot);
        PathUtil.resetWalks();

        PathUtil.pruneTree(root, keep::contains);

        assertThat(PathUtil.walks()).as("one walk lists every directory").isEqualTo(1);
        assertThat(kept).exists();
        assertThat(keptAtRoot).exists();
        assertThat(staleBeside).doesNotExist();
        assertThat(staleDeep).doesNotExist();
        assertThat(root.resolve("gone")).doesNotExist();
        assertThat(emptyAlready).doesNotExist();
        assertThat(root.resolve("empty")).doesNotExist();
        assertThat(root).isDirectory();
    }

    @Test
    void the_root_stays_when_nothing_is_kept(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("classes"));
        write(root.resolve("x/Y.class"));

        PathUtil.pruneTree(root, p -> false);

        assertThat(root).isEmptyDirectory();
    }

    @Test
    void a_missing_root_is_a_no_op(@TempDir Path tmp) throws Exception {
        PathUtil.pruneTree(tmp.resolve("absent"), p -> false);

        assertThat(tmp.resolve("absent")).doesNotExist();
    }

    private static Path write(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, file.getFileName().toString());
    }
}
