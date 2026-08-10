// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBuildsMetricsHomesTest {

    @Test
    void listProjectHomesForMetrics_prefers_lock_source_over_stale_sibling(@TempDir Path root)
            throws Exception {
        Path projects = root.resolve("projects");
        Path lockHome = projects.resolve("lock-id");
        Path staleHome = projects.resolve("stale-id");
        Files.createDirectories(lockHome);
        Files.createDirectories(staleHome);
        String path = "/Users/me/jk";
        Files.writeString(
                lockHome.resolve(ProjectBuilds.IDENTITY),
                """
                id = "lock-id"
                coord = "cc.jumpkick:jk"
                path = "%s"
                source = "lock"
                """
                        .formatted(path));
        Files.writeString(
                staleHome.resolve(ProjectBuilds.IDENTITY),
                """
                id = "stale-id"
                coord = "cc.jumpkick:jk"
                path = "%s"
                source = "path"
                """
                        .formatted(path));
        // Stale has more runs but lock still wins.
        Files.createDirectories(staleHome.resolve(ProjectBuilds.RUNS).resolve("1"));
        Files.createDirectories(staleHome.resolve(ProjectBuilds.RUNS).resolve("2"));
        Files.createDirectories(staleHome.resolve(ProjectBuilds.RUNS).resolve("3"));

        var homes = ProjectBuilds.listProjectHomesForMetrics(root);
        assertThat(homes).contains(lockHome).doesNotContain(staleHome);
    }
}
