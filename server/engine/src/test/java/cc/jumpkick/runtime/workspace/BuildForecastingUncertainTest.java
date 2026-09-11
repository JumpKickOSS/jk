// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Os;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module whose inputs the preflight could not read is scheduled on that fact, and {@code jk
 * explain} says so in words — where a random digest used to force the same rebuild while reading
 * as an input change.
 */
class BuildForecastingUncertainTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void an_unreadable_input_schedules_the_module_and_explain_says_why(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows());
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"
                java = 25
                """);
        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "class App {}\n");
        Files.createDirectories(BuildLayout.moduleTargetDir(tmp, tmp));
        Path lock = tmp.resolve("jk-lock.toml");
        Files.writeString(lock, """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        assertThat(graph.hasErrors()).isFalse();
        Path module = tmp.toAbsolutePath().normalize();

        Set<PosixFilePermission> was = Files.getPosixFilePermissions(lock);
        Files.setPosixFilePermissions(lock, Set.of());
        assumeFalse(Files.isReadable(lock), "running as a user the permission bits do not bind");
        try {
            Path cache = tmp.resolve("cache");
            var preflight =
                    BuildForecasting.forecastWithFingerprints(graph, cache, false, tmp, WorkspaceTarget.PACKAGE);
            assertThat(preflight.dirty()).contains(module);
            assertThat(preflight.reasons()).containsKey(module);
            assertThat(preflight.reasons().get(module))
                    .startsWith("rebuilt because the preflight could not read ")
                    .contains("jk-lock.toml");

            ExplainPlan plan = BuildForecasting.explainFromGraph(graph, cache, false, tmp);
            assertThat(plan.hasErrors()).isFalse();
            TaskForecast.Module explained = plan.modules().stream()
                    .filter(m -> m.dir().toAbsolutePath().normalize().equals(module))
                    .findFirst()
                    .orElseThrow();
            assertThat(explained.reason()).isEqualTo(preflight.reasons().get(module));
            assertThat(explained.dirty()).isTrue();
        } finally {
            Files.setPosixFilePermissions(lock, was);
        }
    }
}
