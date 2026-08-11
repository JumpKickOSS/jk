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

/**
 * /1104: force/rebuild must not pay for a full per-step forecast walk — dirty set is the
 * whole graph.
 */
class BuildServiceForecastDirtyTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static JkConfig withRebuild(boolean rebuild) {
        return JkConfig.empty().withRebuild(Optional.of(rebuild));
    }

    private static JkConfig withForce(boolean force) {
        return JkConfig.empty().withForce(Optional.of(force));
    }

    @Test
    void rebuild_marks_every_module_dirty_without_per_step_forecast(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        for (String m : new String[] {"a", "b"}) {
            Path dir = tmp.resolve(m);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("jk.toml"), """
                    [project]
                    group = "t"
                    name = "%s"
                    version = "0.1.0"
                    jdk = 25
                    java = 25
                    """.formatted(m));
        }

        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        assertThat(graph.hasErrors()).isFalse();
        assertThat(graph.topoOrder()).hasSize(2);

        Session session = Session.defaults().withConfig(withRebuild(true));
        Set<Path> dirty =
                SessionContext.where(session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache")));
        assertThat(dirty)
                .containsExactlyInAnyOrderElementsOf(graph.topoOrder().stream()
                        .map(BuildGraph.BuildUnit::dir)
                        .toList());
    }

    @Test
    void force_marks_every_module_dirty_without_per_step_forecast(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "only"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);

        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Session session = Session.defaults().withConfig(withForce(true));
        Set<Path> dirty =
                SessionContext.where(session, () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache2")));
        assertThat(dirty).containsExactly(graph.topoOrder().getFirst().dir());
    }
}
