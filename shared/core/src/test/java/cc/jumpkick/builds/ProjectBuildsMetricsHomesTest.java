// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBuildsMetricsHomesTest {

    @Test
    void listProjectHomesForMetrics_prefers_lock_source_over_stale_sibling(@TempDir Path root) throws Exception {
        Path projects = root.resolve("projects");
        Path lockHome = projects.resolve("lock-id");
        Path staleHome = projects.resolve("stale-id");
        Files.createDirectories(lockHome);
        Files.createDirectories(staleHome);
        String path = "/Users/me/jk";
        Files.writeString(lockHome.resolve(ProjectBuilds.IDENTITY), """
                id = "lock-id"
                coord = "cc.jumpkick:jk"
                path = "%s"
                source = "lock"
                """.formatted(path));
        Files.writeString(staleHome.resolve(ProjectBuilds.IDENTITY), """
                id = "stale-id"
                coord = "cc.jumpkick:jk"
                path = "%s"
                source = "path"
                """.formatted(path));
        // Stale has more runs but lock still wins.
        Files.createDirectories(staleHome.resolve(ProjectBuilds.RUNS).resolve("1"));
        Files.createDirectories(staleHome.resolve(ProjectBuilds.RUNS).resolve("2"));
        Files.createDirectories(staleHome.resolve(ProjectBuilds.RUNS).resolve("3"));

        var homes = ProjectBuilds.listProjectHomesForMetrics(root);
        assertThat(homes).contains(lockHome).doesNotContain(staleHome);
    }

    @Test
    void same_source_rekey_prefers_the_recently_active_home_over_run_count(@TempDir Path root) throws Exception {
        // lock→lock re-key: the stale home accumulated more runs, but the new home is the one
        // still receiving builds — recency must outrank raw run count or the active
        // home is never harvested.
        Path projects = root.resolve("projects");
        Path oldHome = projects.resolve("old-id");
        Path newHome = projects.resolve("new-id");
        Files.createDirectories(oldHome);
        Files.createDirectories(newHome);
        String path = "/Users/me/jk";
        for (var e : Map.of(oldHome, "old-id", newHome, "new-id").entrySet()) {
            Files.writeString(e.getKey().resolve(ProjectBuilds.IDENTITY), """
                    id = "%s"
                    coord = "cc.jumpkick:jk"
                    path = "%s"
                    source = "lock"
                    """.formatted(e.getValue(), path));
        }
        for (int i = 1; i <= 5; i++) {
            Path run = oldHome.resolve(ProjectBuilds.RUNS).resolve(String.valueOf(i));
            Files.createDirectories(run);
            Files.setLastModifiedTime(run, FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L));
        }
        Path fresh = newHome.resolve(ProjectBuilds.RUNS).resolve("1");
        Files.createDirectories(fresh);
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(System.currentTimeMillis()));

        var homes = ProjectBuilds.listProjectHomesForMetrics(root);
        assertThat(homes).contains(newHome).doesNotContain(oldHome);
    }
}
