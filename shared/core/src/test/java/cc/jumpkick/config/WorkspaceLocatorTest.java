// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLocatorTest {

    private static final String ROOT = """
            group    = "cc.jumpkick"
            name     = "jk"
            version  = "0.1.0"

            [workspace]
            modules = ["core"]
            """;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * A sandbox OUTSIDE the repo tree. The build points {@code java.io.tmpdir} at {@code build/tmp}
     * (inside the checkout), so a plain {@code @TempDir} has the repo's own workspace {@code jk.toml}
     * as an ancestor — which strict-ancestor "no enclosing workspace" assertions would wrongly find
     * (JK-2314). Rooting under the user home escapes the checkout.
     */
    private static Path isolatedRoot() throws IOException {
        return Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".jk-wsl-test-");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void finds_enclosing_workspace_for_unlisted_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), ROOT);
        // `app` is NOT in modules yet — findEnclosingWorkspace must still find the root.
        Path app = Files.createDirectories(tmp.resolve("app"));

        assertThat(WorkspaceLocator.findEnclosingWorkspace(app))
                .contains(tmp.toAbsolutePath().normalize());
    }

    @Test
    void finds_enclosing_workspace_for_nested_path(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), ROOT);
        Path nested = Files.createDirectories(tmp.resolve("packages/foo"));

        assertThat(WorkspaceLocator.findEnclosingWorkspace(nested))
                .contains(tmp.toAbsolutePath().normalize());
    }

    @Test
    void workspace_root_is_not_its_own_enclosing_workspace() throws IOException {
        Path tmp = isolatedRoot();
        try {
            write(tmp.resolve("jk.toml"), ROOT);
            // Strict-ancestor search: the root dir itself has no enclosing workspace.
            assertThat(WorkspaceLocator.findEnclosingWorkspace(tmp)).isEmpty();
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void standalone_project_has_no_enclosing_workspace() throws IOException {
        Path tmp = isolatedRoot();
        try {
            write(tmp.resolve("jk.toml"), """
                    group    = "com.example"
                    name     = "widget"
                    version  = "0.1.0"
                    """);
            Path sub = Files.createDirectories(tmp.resolve("sub"));
            assertThat(WorkspaceLocator.findEnclosingWorkspace(sub)).isEmpty();
        } finally {
            deleteRecursively(tmp);
        }
    }
}
