// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install handoff plan is parse + cache-install only: jars stay on disk from the first pass,
 * and the new engine restamps the shelf without packaging or testing again.
 */
class InstallPlansReshelveTest {

    @Test
    void reshelve_plan_is_parse_then_cache_install_only(@TempDir Path tmp) throws Exception {
        Path module = tmp.resolve("lib");
        Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "ex"
                name = "lib"
                version = "1.0.0"
                java = 25
                """);

        BuildPlan plan = InstallPlans.reshelveBuildPlan(module, tmp.resolve("cache"), tmp.resolve("m2"));

        assertThat(plan.steps().stream().map(Task::name).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(TaskNames.PARSE_BUILD, TaskNames.CACHE_INSTALL);
        assertThat(plan.steps().stream()
                        .filter(t -> TaskNames.CACHE_INSTALL.equals(t.name()))
                        .findFirst()
                        .orElseThrow()
                        .requires())
                .containsExactly(TaskNames.PARSE_BUILD);
    }
}
