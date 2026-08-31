// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirtyPathsTest {

    @Test
    void working_tree_includes_untracked_and_falls_back_to_last_commit(@TempDir Path dir) throws Exception {
        git(dir, "init");
        git(dir, "config", "user.email", "t@t");
        git(dir, "config", "user.name", "t");
        Files.writeString(dir.resolve("a.txt"), "one", StandardCharsets.UTF_8);
        git(dir, "add", "a.txt");
        git(dir, "commit", "-m", "one");
        Files.writeString(dir.resolve("b.txt"), "two", StandardCharsets.UTF_8);
        List<String> wt = DirtyPaths.wip(dir);
        assertThat(wt).contains("b.txt");

        git(dir, "add", "b.txt");
        git(dir, "commit", "-m", "two");
        List<String> afterCommit = DirtyPaths.wip(dir);
        assertThat(afterCommit).contains("b.txt");
    }

    @Test
    void nested_workspace_root_gets_workspace_relative_paths(@TempDir Path repo) throws Exception {
        // The workspace lives one level below the git root (monorepo). Diff paths must come back
        // relative to the workspace root — not the repo root — and dirt outside the workspace
        // (which no module can own) must not leak in (JK-2611).
        git(repo, "init");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        Path ws = Files.createDirectories(repo.resolve("ws"));
        Files.writeString(ws.resolve("tracked.java"), "class A {}", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("outside.java"), "class B {}", StandardCharsets.UTF_8);
        git(repo, "add", ".");
        git(repo, "commit", "-m", "base");

        Files.writeString(ws.resolve("tracked.java"), "class A { int x; }", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("outside.java"), "class B { int x; }", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve("untracked.java"), "class C {}", StandardCharsets.UTF_8);

        List<String> wip = DirtyPaths.wip(ws);
        assertThat(wip).containsExactlyInAnyOrder("tracked.java", "untracked.java");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        assertThat(p.waitFor()).isZero();
    }
}
