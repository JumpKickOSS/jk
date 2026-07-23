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

/** JK-1100: local dirty-set memo hit/miss. */
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
    void source_change_misses_memo(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        PreflightMemo.storeDirty(tmp, graph, false, Set.of());

        Files.writeString(tmp.resolve("src/main/java/App.java"), "class App { int x = 2; }\n");
        assertThat(PreflightMemo.tryLoadDirty(tmp, graph, false)).isEmpty();
    }

    @Test
    void forecastDirtyDirs_uses_memo_on_second_call(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Session session = Session.defaults().withConfig(JkConfig.empty());
        // First call: miss → compute (pessimistic empty cache → dirty) → store
        Set<Path> first = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(PreflightMemo.memoFile(tmp)).exists();
        // Second call: hit — same set without needing action cache
        Set<Path> second = SessionContext.where(
                session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache"), false, tmp));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void successful_build_overwrites_memo_with_all_clean(@TempDir Path tmp) throws Exception {
        writeProject(tmp);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        // Stale "everything dirty" memo as if left from a pre-build forecast
        PreflightMemo.storeDirty(
                tmp, graph, false, Set.of(graph.topoOrder().getFirst().dir().toAbsolutePath().normalize()));
        // Simulate post-success write (BuildService on full success)
        PreflightMemo.storeDirty(tmp, graph, false, Set.of());
        Optional<Set<Path>> hit = PreflightMemo.tryLoadDirty(tmp, graph, false);
        assertThat(hit).isPresent();
        assertThat(hit.get()).isEmpty();
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
