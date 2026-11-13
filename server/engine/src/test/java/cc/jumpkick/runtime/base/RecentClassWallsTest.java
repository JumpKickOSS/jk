// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectStore;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.test.TestWorkers;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Each class reads the wall it ran in the newest run that ran it, not its mean. */
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
        ProjectStore.foldRun(home, """
                [test-class."_"."com.example"]
                Slow = 10000
                Ancient = 5000
                """);
        ProjectStore.foldRun(home, """
                [test-class."_"."com.example"]
                Slow = 4000
                Fast = 100
                """);

        Map<String, Long> walls = RecentClassWalls.forModule(module);
        assertThat(walls).containsEntry("com.example.Slow", 4_000L);
        assertThat(walls).containsEntry("com.example.Fast", 100L);
        assertThat(walls).containsEntry("com.example.Ancient", 5_000L);
        // Ancient is the longest, so two workers cover the suite. Slow's mean of 7000 would not.
        assertThat(TestWorkers.autoCount(24, walls, List.of(), 0)).isEqualTo(TestWorkers.clampByHeap(2));
    }

    @Test
    void a_workspace_member_is_keyed_by_its_path_under_the_root_and_its_tier() throws Exception {
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
        ProjectStore.foldRun(home, """
                [test-class."server/engine"."com.example"]
                Slow = 1000
                Fast = 100

                [test-class."server/engine@integration"."com.example"]
                SlowIT = 9000
                """);

        assertThat(RecentClassWalls.forModule(member, "run-tests"))
                .containsOnlyKeys("com.example.Slow", "com.example.Fast")
                .containsEntry("com.example.Slow", 1_000L);
    }

    @Test
    void concurrent_misses_read_the_store_once_and_a_fold_is_read_again() throws Exception {
        Path module = Files.createDirectories(state.resolve("app"));
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, module);
        ProjectStore.foldRun(home, """
                [test-class."_"."com.example"]
                A = 100
                B = 200
                """);
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
                .as("one read serves every ask")
                .isEqualTo(1);

        ProjectStore.foldRun(home, """
                [test-class."_"."com.example"]
                C = 300
                """);
        assertThat(RecentClassWalls.forModule(module)).containsKeys("com.example.A", "com.example.B", "com.example.C");
    }

    @Test
    void no_store_is_no_walls() throws Exception {
        Path module = Files.createDirectories(state.resolve("cold"));
        assertThat(RecentClassWalls.forModule(module)).isEmpty();
    }
}
