// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JK-1100/1108/1109: local dirty-set + graph memo. */
class PreflightMemoTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void store_then_load_hits_when_inputs_unchanged(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Set<Path> dirty = Set.of(graph.topoOrder().getFirst().dir().toAbsolutePath().normalize());
        PreflightMemo.storeDirty(tmp, graph, false, dirty);

        Optional<Set<Path>> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEqualTo(dirty);
    }

    @Test
    void source_content_change_misses_memo_even_if_size_unchanged(@TempDir Path tmp) throws Exception {
        // JK-1108: content-hash fingerprints (default), not mtime/size alone.
        writeProject(tmp);
        Path src = tmp.resolve("src/main/java/App.java");
        // Pad so size can stay similar after edit
        Files.writeString(src, "class App { int x = 1; }\n");
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        PreflightMemo.storeDirty(tmp, graph, false, Set.of());

        // Same length swap of digit — size may match; content hash must not.
        Files.writeString(src, "class App { int x = 2; }\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void forecastDirtyDirs_uses_memo_on_second_call(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Session session = Session.defaults().withConfig(JkConfig.empty());
        Set<Path> first = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(PreflightMemo.memoFile(tmp)).exists();
        Set<Path> second = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void successful_build_overwrites_memo_with_all_clean(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        PreflightMemo.storeDirty(
                tmp, graph, false, Set.of(graph.topoOrder().getFirst().dir().toAbsolutePath().normalize()));
        PreflightMemo.storeDirty(tmp, graph, false, Set.of());
        Optional<Set<Path>> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEmpty();
    }

    @Test
    void graph_structure_memo_matches_after_store(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        assertThat(PreflightMemo.graphStructureMatches(tmp, graph)).isFalse();
        PreflightMemo.storeGraph(tmp, graph);
        assertThat(PreflightMemo.graphStructureMatches(tmp, graph)).isTrue();
        assertThat(PreflightMemo.graphMemoFile(tmp)).exists();
    }

    private static void writeProject(Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "0.1.0"
                jdk = 21
                java = 21
                """);
        Path src = dir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        Files.writeString(
                dir.resolve("jk.lock"),
                """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
    }
}
