// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The newest run's class wall wins per class; the harvested mean does not override it. */
class RecentClassWallsTest {

    @TempDir
    Path state;

    private String prevStateDir;

    @BeforeEach
    void isolateState() {
        prevStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", state.resolve("state").toString());
        RecentClassWalls.clear();
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @AfterEach
    void restoreState() {
        if (prevStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevStateDir);
        RecentClassWalls.clear();
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @Test
    void the_newest_run_wins_and_an_older_class_is_kept() throws Exception {
        Path module = Files.createDirectories(state.resolve("app"));
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, module);
        writeRun(home, module, 1, """
                [test-class."_"."com.example"]
                Slow = 10000
                Ancient = 5000
                """);
        writeRun(home, module, 2, """
                [test-class."_"."com.example"]
                Slow = 4000
                Fast = 100
                """);
        // A trimmed mean that must not win over the runs.
        Files.writeString(home.resolve(ProjectBuilds.PROJECT_METRICS), """
                [test-class."_"."com.example"]
                Slow = 99999
                """);
        RecentClassWalls.clear();

        Map<String, Long> walls = RecentClassWalls.forModule(module);
        assertThat(walls).containsEntry("com.example.Slow", 4_000L);
        assertThat(walls).containsEntry("com.example.Fast", 100L);
        assertThat(walls).containsEntry("com.example.Ancient", 5_000L);
        // Ancient is the longest, so two workers cover the suite. The mean of 99999 would not.
        assertThat(TestWorkers.autoCount(24, walls, List.of(), 0)).isEqualTo(TestWorkers.clampByHeap(2));
    }

    @Test
    void a_workspace_member_is_keyed_by_its_path_under_the_root() throws Exception {
        Path root = Files.createDirectories(state.resolve("ws"));
        Path member = Files.createDirectories(root.resolve("server/engine"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1"
                java = 25

                [workspace]
                modules = ["server/engine"]
                """);
        Files.writeString(member.resolve("jk.toml"), """
                name = "engine"
                """);
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, root);
        writeRun(home, root, 1, """
                [test-class."server/engine"."com.example"]
                Slow = 1000
                Fast = 100
                """);
        RecentClassWalls.clear();

        assertThat(RecentClassWalls.forModule(member))
                .containsEntry("com.example.Slow", 1_000L)
                .containsEntry("com.example.Fast", 100L);
    }

    @Test
    void no_runs_falls_back_to_the_ledger() throws Exception {
        Path module = Files.createDirectories(state.resolve("cold"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "com.example"
                name = "cold"
                version = "1"
                java = 25
                """);
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, module);
        Files.createDirectories(home);
        Files.writeString(home.resolve(ProjectBuilds.PROJECT_METRICS), """
                [test-class."_"."com.example"]
                Only = 8000
                """);
        RecentClassWalls.clear();
        BuildMetrics.clearSessionAggregatesMemo();

        Map<String, Long> walls = SessionContext.where(
                Session.defaults().withWorkingDir(module), () -> RecentClassWalls.forModule(module));
        assertThat(walls).containsEntry("com.example.Only", 8_000L);
    }

    private static void writeRun(Path home, Path module, int n, String metrics) throws Exception {
        Path run = home.resolve(ProjectBuilds.RUNS).resolve(Integer.toString(n));
        Files.createDirectories(run);
        Files.writeString(run.resolve(ProjectBuilds.CHECKOUT), module.toAbsolutePath() + "\n");
        Files.writeString(run.resolve(ProjectBuilds.METRICS), metrics);
    }
}
