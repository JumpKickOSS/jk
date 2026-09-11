// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.util.GuardBaselineMarker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardHooksTest {

    @Test
    void install_writes_both_hooks_and_the_protected_list_and_keeps_a_foreign_hook(@TempDir Path tmp)
            throws IOException {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        GuardHooks.Installed first = GuardHooks.install(repo, false);
        assertThat(first.written()).containsExactly("commit-msg", "pre-commit");
        assertThat(first.refused()).isEmpty();
        Path commitMsg = repo.resolve(".git/hooks/commit-msg");
        assertThat(commitMsg).exists();
        assertThat(Files.readString(commitMsg)).startsWith("#!/bin/sh").contains("exec jk guard commit-msg \"$1\"");
        assertThat(Files.isExecutable(commitMsg)).isTrue();
        assertThat(Files.readString(repo.resolve(".git/hooks/pre-commit")))
                .contains("jk-guards-baseline.toml")
                .contains("jk-guard-freeze");
        assertThat(Files.readString(first.protectedList())).isEqualTo("jk-guards-baseline.toml\njk-guards.toml\n");

        // same content again: idempotent, not a refusal
        assertThat(GuardHooks.install(repo, false).written()).containsExactly("commit-msg", "pre-commit");

        // someone else's hook: kept without --force, replaced with it
        Files.writeString(commitMsg, "#!/bin/sh\necho mine\n");
        GuardHooks.Installed kept = GuardHooks.install(repo, false);
        assertThat(kept.written()).containsExactly("pre-commit");
        assertThat(kept.refused())
                .singleElement()
                .asString()
                .contains("commit-msg")
                .contains("--replace");
        assertThat(Files.readString(commitMsg)).contains("echo mine");
        GuardHooks.Installed forced = GuardHooks.install(repo, true);
        assertThat(forced.refused()).isEmpty();
        assertThat(Files.readString(commitMsg)).contains("exec jk guard commit-msg");
    }

    @Test
    void a_worktree_installs_into_the_common_git_dir_and_no_repo_is_refused(@TempDir Path tmp) throws IOException {
        Path main = Files.createDirectories(tmp.resolve("main/.git"));
        Path wtGit = Files.createDirectories(main.resolve("worktrees/feature"));
        Files.writeString(wtGit.resolve("commondir"), "../..\n");
        Path worktree = Files.createDirectories(tmp.resolve("feature"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + wtGit + "\n");
        GuardHooks.Installed done = GuardHooks.install(worktree, false);
        assertThat(done.hooksDir()).isEqualTo(main.resolve("hooks"));
        assertThat(main.resolve("hooks/commit-msg")).exists();
        assertThat(GuardHooks.preCommit())
                .as("the hook names the marker the shared helper writes")
                .contains("$git_dir/" + GuardBaselineMarker.NAME);

        Path bare = Files.createDirectories(tmp.resolve("nowhere"));
        assertThatThrownBy(() -> GuardHooks.install(bare, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not inside a git repository");
    }

    @Test
    void render_prints_every_hook_with_its_path() {
        String text = GuardHooks.render();
        assertThat(text).contains("# ---- .git/hooks/commit-msg ----").contains("# ---- .git/hooks/pre-commit ----");
    }
}
