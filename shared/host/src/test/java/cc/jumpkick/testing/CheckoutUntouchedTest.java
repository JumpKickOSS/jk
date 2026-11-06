// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckoutUntouchedTest {

    @Test
    void a_clean_file_that_becomes_dirty_a_dirty_one_that_moves_and_one_restored_are_changes(@TempDir Path repo)
            throws Exception {
        assumeTrue(git(repo, "init", "-q") == 0, "git is on PATH");
        Files.writeString(repo.resolve("clean.toml"), "a = 1\n");
        Files.writeString(repo.resolve("dirty.toml"), "b = 1\n");
        Files.writeString(repo.resolve("restored.toml"), "c = 1\n");
        Files.writeString(repo.resolve("idle.toml"), "d = 1\n");
        assertThat(git(repo, "add", ".")).isZero();
        assertThat(git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-qm", "init"))
                .isZero();
        // The developer's own edits, there before the class starts.
        Files.writeString(repo.resolve("dirty.toml"), "b = 2\n");
        Files.writeString(repo.resolve("restored.toml"), "c = 2\n");
        Files.writeString(repo.resolve("idle.toml"), "d = 2\n");

        CheckoutUntouched.Snapshot before = Objects.requireNonNull(CheckoutUntouched.take(repo));
        assertThat(before.dirty()).containsOnlyKeys("dirty.toml", "restored.toml", "idle.toml");

        Files.writeString(repo.resolve("clean.toml"), "a = 2\n");
        Files.writeString(repo.resolve("dirty.toml"), "b = 33\n");
        Files.setLastModifiedTime(
                repo.resolve("dirty.toml"),
                FileTime.fromMillis(
                        Files.getLastModifiedTime(repo.resolve("dirty.toml")).toMillis() + 5_000));
        Files.writeString(repo.resolve("restored.toml"), "c = 1\n");
        Files.writeString(repo.resolve("untracked.toml"), "e = 1\n");

        CheckoutUntouched.Snapshot after = Objects.requireNonNull(CheckoutUntouched.take(repo));
        assertThat(CheckoutUntouched.changed(before, after))
                .isEqualTo(List.of("clean.toml", "dirty.toml", "restored.toml"));
        assertThat(CheckoutUntouched.changed(after, after)).isEmpty();
    }

    @Test
    void outside_a_git_work_tree_there_is_nothing_to_compare(@TempDir Path dir) throws Exception {
        assumeTrue(git(dir, "rev-parse", "--git-dir") > 0, "the temp root is not inside a git work tree");
        assertThat(CheckoutUntouched.take(dir)).isNull();
    }

    private static int git(Path dir, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException noGit) {
            return -1;
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }
}
