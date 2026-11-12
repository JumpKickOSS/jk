// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the workspace scope covers: the checkout, and not another checkout nested inside it. */
class WorkspaceInputTokensTest {

    /**
     * A git worktree (a {@code .git} pointer file) or a nested clone (a {@code .git} directory) below
     * the root is another branch's tree: none of its files is a token, and editing one leaves the
     * tokens as they were. The root's own {@code .git} does not stop the walk.
     */
    @Test
    void a_nested_checkout_is_not_part_of_the_workspace(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("ws");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("jk.toml"), "[workspace]\n");
        Files.createDirectories(root.resolve("app/src"));
        Files.writeString(root.resolve("app/src/A.java"), "class A {}\n");

        Path worktree = Files.createDirectories(root.resolve(".claude/worktrees/one"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../../.git/worktrees/one\n");
        Files.createDirectories(worktree.resolve("app/src"));
        Files.writeString(worktree.resolve("app/src/A.java"), "class A {}\n");
        Path clone = Files.createDirectories(root.resolve("vendor/lib"));
        Files.createDirectories(clone.resolve(".git"));
        Files.writeString(clone.resolve("README.md"), "another repository\n");

        List<String> tokens = BuildLogicSupport.workspaceInputTokens(root);
        assertThat(tokens)
                .anyMatch(t -> t.startsWith("ws:app/src/A.java:"))
                .anyMatch(t -> t.startsWith("ws:jk.toml:"))
                .noneMatch(t -> t.startsWith("ws:.claude/"))
                .noneMatch(t -> t.startsWith("ws:vendor/"));

        Files.writeString(worktree.resolve("app/src/A.java"), "class A { int edited; }\n");
        Files.writeString(clone.resolve("README.md"), "edited\n");
        assertThat(BuildLogicSupport.workspaceInputTokens(root)).isEqualTo(tokens);
    }
}
