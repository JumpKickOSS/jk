// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A git command reads only a repository a test made when it runs in a temp root and names no path
 * outside one and no other host; git a test wrote, and git run in the workspace, read what they like.
 */
class GitRunsTest {

    @TempDir
    Path root;

    @Test
    void git_in_a_repository_a_test_made_reads_nothing_else() {
        assertThat(own("git -C " + temp().resolve("1/jk-junit-2/app") + " rev-parse --verify --quiet HEAD", null))
                .as("-C into a temp root")
                .isTrue();
        assertThat(own(
                        "git rev-parse --show-toplevel",
                        temp().resolve("1/jk-junit-2/proj").toString()))
                .as("started in a temp root")
                .isTrue();
        assertThat(own(
                        "git -C app commit -q -m initial commit",
                        temp().resolve("1").toString()))
                .as("a relative -C stays in it")
                .isTrue();
        assertThat(own("git diff --name-only --relative --end-of-options HEAD refs/heads/main", one()))
                .as("a ref with a slash is no path out")
                .isTrue();
        assertThat(own("git show HEAD:README.md", one()))
                .as("a revision and a path")
                .isTrue();
        assertThat(own("git fetch origin", one())).as("a remote by name").isTrue();
        assertThat(own("git -c core.longpaths=true status", one()))
                .as("with a setting")
                .isTrue();
        if (!Os.isWindows()) {
            assertThat(own("/usr/bin/git status", one()))
                    .as("the administered git")
                    .isTrue();
        }
        assertThat(own("git --version", null)).as("reads no repository").isTrue();
    }

    @Test
    void git_that_can_read_past_the_tests_own_files_is_a_program_started() {
        assertThat(own("git rev-parse --show-toplevel", null))
                .as("in the JVM's own directory, the workspace")
                .isFalse();
        assertThat(own("git -C " + ws() + " status", one()))
                .as("-C into the workspace")
                .isFalse();
        assertThat(own("git -C ../.. status", one()))
                .as("-C out of the temp root")
                .isFalse();
        assertThat(own("git clone " + ws().resolve("fixture") + " repo", one()))
                .as("a path in the workspace")
                .isFalse();
        assertThat(own("git clone file://" + ws().resolve("fixture") + " repo", one()))
                .as("a file URL in the workspace")
                .isFalse();
        assertThat(own("git clone file://" + temp().resolve("1/origin") + " repo", one()))
                .as("a file URL in the temp root")
                .isTrue();
        assertThat(own("git --git-dir=" + ws().resolve(".git") + " log", one()))
                .as("an option's value naming the workspace")
                .isFalse();
        assertThat(own("git clone https://example.com/x.git", one()))
                .as("another host")
                .isFalse();
        assertThat(own("git fetch git@example.com:x/y.git", one())).isFalse();
        assertThat(own("git fetch example.com:x/y.git", one()))
                .as("host:path where a remote is read")
                .isFalse();
        assertThat(own(temp().resolve("1/jk-junit-2/git") + " status", one()))
                .as("a git the test wrote")
                .isFalse();
        assertThat(own("sh -c git status", one())).isFalse();
        assertThat(GitRuns.ownRepository("git status", one(), ws(), List.of()))
                .as("no temp roots")
                .isFalse();
    }

    @Test
    void a_temp_root_inside_a_repository_is_not_the_tests_own() throws Exception {
        Path tmp = root;
        Path free = Files.createDirectories(tmp.resolve("free/tmp"));
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path inside = Files.createDirectories(repo.resolve("nested/tmp"));
        List<Path> kept = GitRuns.unenclosed(List.of(free, inside));
        assertThat(kept)
                .as("git walking up from the inner root finds the repository")
                .doesNotContain(inside);
        // A host whose home is itself a repository encloses every temp root under it.
        if (!GitRuns.unenclosed(List.of(tmp)).isEmpty()) assertThat(kept).containsExactly(free);
    }

    private Path ws() {
        return root.resolve("ws/project");
    }

    private Path temp() {
        return root.resolve("tmp");
    }

    private String one() {
        return temp().resolve("1").toString();
    }

    private boolean own(String command, @Nullable String directory) {
        return GitRuns.ownRepository(command, directory, ws(), List.of(temp()));
    }
}
