// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rarely run test tier keeps its class walls however many runs of another tier come after it:
 * the integration suite's classes survive a few hundred default-tier runs.
 */
class ProjectStoreRetentionTest {

    @Test
    void a_rare_tier_keeps_its_class_walls_past_stale_runs_of_another(@TempDir Path home) {
        ProjectStore.foldRun(home, """
                workspace.wall-ms = 700000
                module.server_io.task.run-tests.wall1-ms = 650000

                [test-class."server/io@integration"."com.example"]
                IoIT = 650000
                """);
        String defaultTier = """
                workspace.wall-ms = 40000
                module.server_io.task.run-tests.wall1-ms = 40000

                [test-class."server/io"."com.example"]
                IoTest = 40000
                """;
        for (int i = 0; i <= ProjectStore.STALE_RUNS; i++) ProjectStore.foldRun(home, defaultTier);

        List<String> suites = new ArrayList<>();
        ProjectStore.scan(home, null, (dir, fqcn, m) -> suites.add(dir + " " + fqcn));
        System.out.println("class rows after " + (ProjectStore.STALE_RUNS + 1) + " default-tier runs: " + suites);
        assertThat(suites)
                .as("class rows after %d default-tier runs", ProjectStore.STALE_RUNS + 1)
                .contains("server/io@integration com.example.IoIT");
    }
}
