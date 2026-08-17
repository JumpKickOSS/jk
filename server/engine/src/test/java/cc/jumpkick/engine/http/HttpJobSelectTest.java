// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpJobSelectTest {

    @Test
    void empty_modules_means_no_hint(@TempDir Path root) throws Exception {
        writeWorkspace(root);
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        assertThat(HttpJobSelect.dirtyHint(root, build, List.of())).isNull();
        assertThat(HttpJobSelect.dirtyHint(root, build, null)).isNull();
    }

    @Test
    void unknown_module_fails(@TempDir Path root) throws Exception {
        writeWorkspace(root);
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        assertThatThrownBy(() -> HttpJobSelect.dirtyHint(root, build, List.of("nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no module matched");
    }

    @Test
    void selected_module_includes_prereqs(@TempDir Path root) throws Exception {
        writeWorkspace(root);
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        Set<Path> selected = HttpJobSelect.selected(root, build, List.of("api"));
        Set<Path> dirty = HttpJobSelect.dirtyHint(root, build, List.of("api"));
        assertThat(selected).containsExactly(BuildGraph.canonicalPath(root.resolve("api")));
        assertThat(dirty)
                .containsExactlyInAnyOrder(
                        BuildGraph.canonicalPath(root.resolve("api")), BuildGraph.canonicalPath(root.resolve("core")));
    }

    @Test
    void tags_are_final_when_set() {
        TestSelection sel = HttpJobSelect.testSelection(List.of("network"), List.of(), List.of());
        assertThat(sel.includeTags()).containsExactly("network");
        assertThat(sel.tagsResolved()).isTrue();
        assertThat(HttpJobSelect.testSelection(List.of(), List.of(), List.of())).isEqualTo(TestSelection.DEFAULT);
    }

    private static void writeWorkspace(Path root) throws Exception {
        Files.createDirectories(root.resolve("core"));
        Files.createDirectories(root.resolve("api"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "api"]
                """);
        Files.writeString(root.resolve("core/jk.toml"), """
                group = "com.ex"
                name = "core"
                version = "1.0.0"
                java = 25
                """);
        Files.writeString(root.resolve("api/jk.toml"), """
                group = "com.ex"
                name = "api"
                version = "1.0.0"
                java = 25

                [dependencies]
                core.workspace = true
                """);
    }
}
