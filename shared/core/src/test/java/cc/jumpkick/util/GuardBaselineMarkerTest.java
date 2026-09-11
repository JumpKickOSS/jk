// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The marker lands in the common git dir, from a worktree too, and nowhere outside a repository. */
class GuardBaselineMarkerTest {

    @Test
    void a_worktree_s_marker_lands_in_the_common_git_dir(@TempDir Path tmp) throws IOException {
        Path main = Files.createDirectories(tmp.resolve("main/.git"));
        Path wtGit = Files.createDirectories(main.resolve("worktrees/feature"));
        Files.writeString(wtGit.resolve("commondir"), "../..\n");
        Path worktree = Files.createDirectories(tmp.resolve("feature"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + wtGit + "\n");

        assertThat(GuardBaselineMarker.gitCommonDir(worktree.resolve("sub/dir")))
                .isEqualTo(main);
        GuardBaselineMarker.leave(worktree, "jk guard (tightened)");
        assertThat(main.resolve(GuardBaselineMarker.NAME)).hasContent("jk guard (tightened)\n");
    }

    @Test
    void a_dot_git_file_that_is_not_a_worktree_pointer_marks_nothing(@TempDir Path tmp) throws IOException {
        Path odd = Files.createDirectories(tmp.resolve("odd"));
        Files.writeString(odd.resolve(".git"), "not a pointer\n");
        assertThat(GuardBaselineMarker.gitCommonDir(odd.resolve("sub"))).isNull();
        GuardBaselineMarker.leave(odd, "x");
        assertThat(odd.resolve(GuardBaselineMarker.NAME)).doesNotExist();
    }
}
