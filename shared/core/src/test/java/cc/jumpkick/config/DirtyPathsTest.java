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

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        assertThat(p.waitFor()).isZero();
    }
}
