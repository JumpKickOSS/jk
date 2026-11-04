// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace root's {@code jk: always} after-build script — jk's own copies build output into
 * {@code target/dist} this way — runs in every build that builds a member, the same build: when a
 * member's sources moved the forecast walks every module instead of reading its memo, and the
 * root, whose own inputs did not move, must not be left for the build after.
 */
@Tag("integration")
class AlwaysAfterBuildTest {

    @Test
    void the_roots_always_script_runs_in_the_build_that_rebuilt_a_member(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Path cache = TestCaches.dir("always-after-build");
        Path marker = root.resolve("runs.txt");

        WorkspaceResult first = build(root, cache, null);
        assertThat(first.success()).as("%s", first.errors()).isTrue();
        assertThat(Files.readAllLines(marker)).hasSize(1);

        // No dirty memo to read: the forecast walks every module and finds each one's inputs where
        // the last build left them, so the root runs on its always script alone — as when another
        // command rebuilt a member and the checkout says nothing changed since the root last ran.
        Files.deleteIfExists(PreflightMemo.memoFile(root));
        WorkspaceResult second = build(root, cache, null);
        assertThat(second.success()).as("%s", second.errors()).isTrue();
        assertThat(Files.readAllLines(marker))
                .as("the root's always script ran in that build")
                .hasSize(2);

        // A selected build (a dirty hint without the root) runs it too.
        Files.writeString(root.resolve("app/src/main/java/demo/App.java"), source("3"));
        WorkspaceResult selected = build(root, cache, Set.of(root.resolve("app")));
        assertThat(selected.success()).as("%s", selected.errors()).isTrue();
        assertThat(Files.readAllLines(marker)).hasSize(3);
    }

    private static WorkspaceResult build(Path root, Path cache, @Nullable Set<Path> hint) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(root, cache, null, 1, null, true, false, 1, hint, false, true)
                        .withSpec(WorkspaceSpec.DEFAULT),
                new WorkspaceBuildListener() {});
    }

    private static Path workspace(Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                name = "ws"
                group = "demo"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Path scripts = Files.createDirectories(root.resolve(".jk/scripts"));
        Files.writeString(scripts.resolve("after-build.kts"), """
                // jk: always
                val marker = projectDir.resolve("runs.txt")
                java.nio.file.Files.writeString(
                    marker,
                    "run\\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)
                """);
        Path app = Files.createDirectories(root.resolve("app/src/main/java/demo"));
        Files.writeString(root.resolve("app/jk.toml"), "name = \"app\"\n");
        Files.writeString(app.resolve("App.java"), source("1"));
        return root;
    }

    private static String source(String v) {
        return "package demo;\npublic final class App { public static String v() { return \"" + v + "\"; } }\n";
    }
}
