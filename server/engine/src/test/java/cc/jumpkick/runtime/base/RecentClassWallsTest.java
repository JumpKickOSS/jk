// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.test.TestWorkers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The newest run's class wall wins per class; the harvested mean does not override it. */
class RecentClassWallsTest {

    @TempDir
    Path state;

    @BeforeEach
    void isolateState() {
        System.setProperty("jk.env.JK_STATE_DIR", state.resolve("state").toString());
        RecentClassWalls.clear();
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @AfterEach
    void restoreState() {
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

    @Test
    void concurrent_misses_scan_once_and_a_new_run_is_the_only_file_parsed_again() throws Exception {
        Path module = Files.createDirectories(state.resolve("app"));
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, module);
        writeRun(home, module, 1, """
                [test-class."_"."com.example"]
                A = 100
                """);
        writeRun(home, module, 2, """
                [test-class."_"."com.example"]
                B = 200
                """);
        RecentClassWalls.clear();
        long before = RecentClassWalls.PARSES.get();
        // Every module of a prepare asks at once, the first lookup of a build.
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Map<String, Long>>> asks = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 16; i++) {
                asks.add(pool.submit(() -> {
                    go.await();
                    return RecentClassWalls.forModule(module);
                }));
            }
            go.countDown();
            for (Future<Map<String, Long>> ask : asks) {
                assertThat(ask.get()).containsKeys("com.example.A", "com.example.B");
            }
        }
        assertThat(RecentClassWalls.PARSES.get() - before)
                .as("one scan, each run parsed once")
                .isEqualTo(2);

        writeRun(home, module, 3, """
                [test-class."_"."com.example"]
                C = 300
                """);
        Path runs = home.resolve(ProjectBuilds.RUNS);
        Files.setLastModifiedTime(
                runs, FileTime.fromMillis(Files.getLastModifiedTime(runs).toMillis() + 10_000));
        assertThat(RecentClassWalls.forModule(module)).containsKeys("com.example.A", "com.example.B", "com.example.C");
        assertThat(RecentClassWalls.PARSES.get() - before)
                .as("only the new run is parsed")
                .isEqualTo(3);
    }

    @Test
    void a_pruned_oldest_run_takes_only_the_walls_it_alone_supplied() throws Exception {
        Path module = Files.createDirectories(state.resolve("pruned"));
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, module);
        writeRun(home, module, 1, """
                [test-class."_"."com.example"]
                A = 100
                B = 100
                """);
        writeRun(home, module, 2, """
                [test-class."_"."com.example"]
                B = 200
                """);
        RecentClassWalls.clear();
        assertThat(RecentClassWalls.forModule(module))
                .containsEntry("com.example.A", 100L)
                .containsEntry("com.example.B", 200L);
        long before = RecentClassWalls.PARSES.get();

        // A build adds run 3 and retention drops run 1.
        writeRun(home, module, 3, """
                [test-class."_"."com.example"]
                C = 300
                """);
        Path runs = home.resolve(ProjectBuilds.RUNS);
        Files.delete(runs.resolve("1").resolve(ProjectBuilds.METRICS));
        Files.delete(runs.resolve("1").resolve(ProjectBuilds.CHECKOUT));
        Files.delete(runs.resolve("1"));
        Files.setLastModifiedTime(
                runs, FileTime.fromMillis(Files.getLastModifiedTime(runs).toMillis() + 10_000));

        assertThat(RecentClassWalls.forModule(module))
                .doesNotContainKey("com.example.A")
                .containsEntry("com.example.B", 200L)
                .containsEntry("com.example.C", 300L);
        assertThat(RecentClassWalls.PARSES.get() - before)
                .as("only the new run is parsed")
                .isEqualTo(1);

        // A run rewritten in the middle: everything is merged again.
        Files.writeString(runs.resolve("2").resolve(ProjectBuilds.METRICS), """
                [test-class."_"."com.example"]
                B = 2500
                """);
        Files.setLastModifiedTime(
                runs, FileTime.fromMillis(Files.getLastModifiedTime(runs).toMillis() + 10_000));
        assertThat(RecentClassWalls.forModule(module)).containsEntry("com.example.B", 2_500L);
        assertThat(RecentClassWalls.PARSES.get() - before).isEqualTo(3);
    }

    private static void writeRun(Path home, Path module, int n, String metrics) throws Exception {
        Path run = home.resolve(ProjectBuilds.RUNS).resolve(Integer.toString(n));
        Files.createDirectories(run);
        Files.writeString(run.resolve(ProjectBuilds.CHECKOUT), module.toAbsolutePath() + "\n");
        Files.writeString(run.resolve(ProjectBuilds.METRICS), metrics);
    }
}
