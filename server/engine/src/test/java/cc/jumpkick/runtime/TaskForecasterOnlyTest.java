// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A walk restricted to the modules a memo hit scheduled prices those and reports the rest with no
 * work, in graph order, without reading them: each unlocked module here would forecast a compile.
 */
class TaskForecasterOnlyTest {

    @Test
    void only_the_named_modules_are_walked(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        for (String m : List.of("a", "b")) {
            Path dir = Files.createDirectories(tmp.resolve(m));
            Files.writeString(dir.resolve("jk.toml"), """
                    group = "t"
                    name = "%s"
                    version = "0.1.0"
                    java = 25
                    """.formatted(m));
        }
        BuildGraph.Result graph = BuildGraph.resolve(tmp, JkBuildParser.parse(tmp.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        Path cache = tmp.resolve("cache");
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
        Path a = graph.topoOrder().stream()
                .filter(u -> u.dir().endsWith("a"))
                .findFirst()
                .orElseThrow()
                .dir();

        List<TaskForecast.Module> plan =
                TaskForecaster.of(graph, cas, actionCache, cache, true, WorkspaceTarget.PACKAGE, null, Set.of(a));

        Map<String, Boolean> dirty = plan.stream()
                .collect(Collectors.toMap(m -> m.dir().getFileName().toString(), TaskForecast.Module::dirty));
        assertThat(plan).hasSize(graph.topoOrder().size());
        assertThat(dirty).containsEntry("a", true).containsEntry("b", false);
    }
}
