// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoRootTest {

    @Test
    void a_checkout_nested_in_the_scanned_one_is_not_its_sources(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Path worktree = Files.createDirectories(root.resolve(".claude/worktrees/site"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../../.git/worktrees/site\n");
        Path module = Files.createDirectories(root.resolve("shared/core"));

        assertThat(RepoRoot.otherCheckout(worktree, root))
                .as("a worktree's .git file")
                .isTrue();
        assertThat(RepoRoot.otherCheckout(root.resolve(".git"), root)).isTrue();
        assertThat(RepoRoot.otherCheckout(root, root)).as("the scan's own root").isFalse();
        assertThat(RepoRoot.otherCheckout(module, root)).isFalse();
        assertThat(RepoRoot.otherCheckout(root.resolve(".claude"), root)).isFalse();
    }

    @Test
    void java_under_a_root_leaves_out_a_nested_checkout(@TempDir Path root) throws Exception {
        Files.writeString(Files.createDirectories(root.resolve("src")).resolve("A.java"), "class A {}");
        Path worktree = Files.createDirectories(root.resolve("wt/src"));
        Files.writeString(root.resolve("wt/.git"), "gitdir: elsewhere\n");
        Files.writeString(worktree.resolve("B.java"), "class B {}");

        assertThat(SourceText.javaUnder(root)).containsExactly(root.resolve("src/A.java"));
    }
}
