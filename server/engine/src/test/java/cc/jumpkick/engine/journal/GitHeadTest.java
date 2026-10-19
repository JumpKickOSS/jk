// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commit stamp read from {@code .git} is the one {@code git rev-parse --short HEAD} prints. */
class GitHeadTest {

    @Test
    void a_fresh_repository_reads_as_git_says(@TempDir Path dir) throws Exception {
        Path repo = repoWithCommits(dir.resolve("r"), 2);

        assertThat(GitHead.of(repo).commit()).isEqualTo(gitShort(repo));
        assertThat(GitHead.of(Files.createDirectories(repo.resolve("sub/deeper")))
                        .commit())
                .as("from a module below the root")
                .isEqualTo(gitShort(repo));
    }

    @Test
    void packed_refs_and_a_detached_head_read_as_git_says(@TempDir Path dir) throws Exception {
        Path repo = repoWithCommits(dir.resolve("r"), 2);
        git(repo, "pack-refs", "--all");
        assertThat(GitHead.of(repo).commit()).isEqualTo(gitShort(repo));

        git(repo, "checkout", "-q", "--detach", "HEAD~1");
        assertThat(GitHead.of(repo).commit()).isEqualTo(gitShort(repo));
    }

    @Test
    void a_linked_worktree_reads_its_own_head(@TempDir Path dir) throws Exception {
        Path repo = repoWithCommits(dir.resolve("r"), 2);
        Path tree = dir.resolve("wt");
        git(repo, "worktree", "add", "-q", "-b", "side", tree.toString(), "HEAD~1");

        assertThat(GitHead.of(tree).commit()).isEqualTo(gitShort(tree)).isNotEqualTo(gitShort(repo));
    }

    @Test
    void this_checkout_reads_as_git_says() throws Exception {
        Path here = Path.of("").toAbsolutePath();
        String expected = gitShort(here);
        assumeTrue(expected != null, "not run from a git checkout");
        assertThat(GitHead.of(here).commit()).isEqualTo(expected);
    }

    @Test
    void no_repository_is_known_to_have_no_commit(@TempDir Path dir) {
        GitHead.Head head = GitHead.of(dir);
        assumeTrue(head.known(), "a .git above the temp dir");
        assertThat(head.commit()).isNull();
    }

    @Test
    void the_abbreviation_grows_with_the_object_count_as_git_s_does() {
        assertThat(GitHead.abbrevFor(0)).isEqualTo(7);
        assertThat(GitHead.abbrevFor(16_383)).isEqualTo(7);
        assertThat(GitHead.abbrevFor(16_384)).isEqualTo(8);
        assertThat(GitHead.abbrevFor(65_536)).isEqualTo(9);
        assertThat(GitHead.abbrevFor(4_000_000)).isEqualTo(11);
    }

    private static Path repoWithCommits(Path repo, int commits) throws Exception {
        Files.createDirectories(repo);
        assumeTrue(git(repo, "init", "-q") != null, "git is not installed");
        for (int i = 0; i < commits; i++) {
            Files.writeString(repo.resolve("f.txt"), "v" + i);
            git(repo, "add", "f.txt");
            git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c" + i);
        }
        return repo;
    }

    private static @Nullable String gitShort(Path dir) throws Exception {
        String out = git(dir, "rev-parse", "--short", "HEAD");
        return out == null ? null : out.trim();
    }

    /** {@code git args} in {@code dir}: its stdout, or null when git is missing or fails. */
    private static @Nullable String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return null;
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
        return out;
    }
}
