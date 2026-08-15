// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewParentDirGuessTest {

    @Test
    void well_known_src_beats_home(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path src = home.resolve("src");
        Files.createDirectories(src);
        Files.createDirectories(home.resolve("Documents"));
        assertThat(NewParentDirGuess.guess(home, List.of()))
                .isEqualTo(src.toAbsolutePath().normalize());
    }

    @Test
    void history_common_parent_wins_over_well_known(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path code = home.resolve("code");
        Path a = code.resolve("alpha");
        Path b = code.resolve("beta");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.createDirectories(home.resolve("src")); // well-known, but history should win
        Path guess = NewParentDirGuess.guess(home, List.of(a, b));
        assertThat(guess).isEqualTo(code.toAbsolutePath().normalize());
    }

    @Test
    void falls_back_to_home_when_nothing_else(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home);
        assertThat(NewParentDirGuess.guess(home, List.of()))
                .isEqualTo(home.toAbsolutePath().normalize());
    }

    @Test
    void git_cluster_under_projects(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projects = home.resolve("my-stuff");
        Path r1 = projects.resolve("r1");
        Path r2 = projects.resolve("r2");
        Files.createDirectories(r1.resolve(".git"));
        Files.createDirectories(r2.resolve(".git"));
        Path guess = NewParentDirGuess.guess(home, List.of());
        assertThat(guess).isEqualTo(projects.toAbsolutePath().normalize());
    }

    @Test
    void git_worktree_file_markers_count_as_repos(@TempDir Path temp) throws Exception {
        // Linked worktrees mark the repo with a `.git` file, not a directory.
        Path home = temp.resolve("home");
        Path trees = home.resolve("worktrees");
        Files.createDirectories(trees.resolve("wt1"));
        Files.createDirectories(trees.resolve("wt2"));
        Files.writeString(trees.resolve("wt1/.git"), "gitdir: /elsewhere/repo/.git/worktrees/wt1\n");
        Files.writeString(trees.resolve("wt2/.git"), "gitdir: /elsewhere/repo/.git/worktrees/wt2\n");
        assertThat(NewParentDirGuess.guess(home, List.of()))
                .isEqualTo(trees.toAbsolutePath().normalize());
    }

    @Test
    void longest_common_prefix() {
        Path a = Path.of("/home/u/src/a");
        Path b = Path.of("/home/u/src/b");
        assertThat(NewParentDirGuess.longestCommonPrefix(a, b)).isEqualTo(Path.of("/home/u/src"));
    }
}
