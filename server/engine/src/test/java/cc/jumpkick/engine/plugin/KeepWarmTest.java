// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.testing.FakeClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What keeps a warm worker: requests about its projects and edits to their sources, so a project
 * being worked on keeps its compiler through builds that compile nothing.
 */
class KeepWarmTest {

    @AfterEach
    void systemClock() {
        ProjectActivity.forTests(Clock.SYSTEM);
    }

    @Test
    void a_request_from_a_member_module_counts_for_its_workspace(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("ws");
        Path app = Files.createDirectories(root.resolve("app/src/main/java/demo"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "g"
                name = "ws"
                version = "1"

                [workspace]
                modules = ["app"]
                """);
        Files.writeString(root.resolve("app/jk.toml"), "name = \"app\"\n");
        FakeClock clock = new FakeClock();
        ProjectActivity.forTests(clock);
        Path project = ProjectActivity.projectOf(app);
        assertThat(project).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(ProjectActivity.lastNanos(Set.of(project)))
                .as("never worked on")
                .isEqualTo(Long.MIN_VALUE);

        clock.advance(Duration.ofMinutes(3));
        ProjectActivity.touch(root.resolve("app").toString());
        assertThat(ProjectActivity.lastNanos(Set.of(project))).isEqualTo(clock.nanos());
    }

    @Test
    void the_window_restarts_at_the_last_activity() {
        long linger = TimeUnit.MINUTES.toNanos(10);
        assertThat(KeepWarm.untilDeadline(TimeUnit.MINUTES.toNanos(12), TimeUnit.MINUTES.toNanos(5), linger))
                .isEqualTo(TimeUnit.MINUTES.toNanos(3));
        assertThat(KeepWarm.untilDeadline(TimeUnit.MINUTES.toNanos(16), TimeUnit.MINUTES.toNanos(5), linger))
                .isNegative();
    }

    @Test
    void an_edit_after_the_last_activity_is_found_and_a_hidden_directory_is_not_read(@TempDir Path tmp)
            throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java/demo"));
        Path a = Files.writeString(src.resolve("A.java"), "class A {}");
        Path hidden = Files.createDirectories(tmp.resolve("src/main/java/.idea"));
        Path h = Files.writeString(hidden.resolve("x"), "x");
        long since = System.currentTimeMillis();
        Files.setLastModifiedTime(a, FileTime.fromMillis(since - 60_000));
        Files.setLastModifiedTime(h, FileTime.fromMillis(since + 60_000));
        Path root = tmp.resolve("src/main/java");
        long budget = TimeUnit.SECONDS.toNanos(5);
        assertThat(KeepWarm.editedSince(List.of(root), since, budget))
                .as("only a hidden file moved")
                .isFalse();

        Files.setLastModifiedTime(a, FileTime.fromMillis(since + 1_000));
        assertThat(KeepWarm.editedSince(List.of(root), since, budget)).isTrue();
    }

    @Test
    void a_check_that_runs_out_of_time_keeps_the_worker(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("src");
        for (int i = 0; i < 5; i++) Files.createDirectories(root.resolve("p" + i));
        // Every reading of this clock is a second later: the budget is gone at the first directory.
        Clock slow = new Clock() {
            long n;

            @Override
            public long millis() {
                return 0;
            }

            @Override
            public long nanos() {
                return n += TimeUnit.SECONDS.toNanos(1);
            }
        };
        assertThat(KeepWarm.editedSince(List.of(root), Long.MAX_VALUE, TimeUnit.MILLISECONDS.toNanos(100), slow))
                .isTrue();
    }

    @Test
    void a_compiles_source_root_leaves_out_what_the_build_generates(@TempDir Path tmp) {
        Path main = tmp.resolve("app/src/main/java");
        Path generated = tmp.resolve("app/target/generated-sources/annotations");
        List<Path> sources = List.of(
                main.resolve("demo/a/A.java"), main.resolve("demo/b/B.java"), generated.resolve("demo/Gen.java"));
        assertThat(KeepWarm.sourceRoot(sources, List.of(tmp.resolve("app/target"))))
                .isEqualTo(main.resolve("demo").toAbsolutePath().normalize());
        assertThat(KeepWarm.sourceRoot(List.of(Path.of("/A.java")), List.of()))
                .as("too shallow")
                .isNull();
        assertThat(KeepWarm.sourceRoot(List.of(generated.resolve("demo/Gen.java")), List.of(tmp.resolve("app/target"))))
                .as("only generated sources")
                .isNull();
    }
}
