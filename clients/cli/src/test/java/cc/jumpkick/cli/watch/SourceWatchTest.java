// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.watch.AppWatchLoop.Pass;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.testing.FakeClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the watch groups a burst of events into one pass, and which roots it keeps watching. */
class SourceWatchTest {

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    @Test
    void a_burst_ends_once_it_has_been_quiet_for_the_window_after_its_last_event() {
        FakeClock clock = new FakeClock();
        SourceWatch.Settle settle = new SourceWatch.Settle(clock, 150);
        assertThat(millis(settle.remainingNanos())).isEqualTo(150);

        clock.advance(Duration.ofMillis(100));
        settle.event();
        assertThat(millis(settle.remainingNanos()))
                .as("a second event 100 ms in restarts the quiet window")
                .isEqualTo(150);

        clock.advance(Duration.ofMillis(149));
        assertThat(settle.remainingNanos()).isPositive();
        clock.advance(Duration.ofMillis(1));
        assertThat(settle.remainingNanos()).as("150 ms of quiet").isLessThanOrEqualTo(0);
    }

    @Test
    void a_burst_that_never_goes_quiet_still_ends_at_the_max_wait() {
        FakeClock clock = new FakeClock();
        SourceWatch.Settle settle = new SourceWatch.Settle(clock, 150);
        for (long at = 100; at < SourceWatch.MAX_WAIT_MILLIS; at += 100) {
            clock.advance(Duration.ofMillis(100));
            settle.event();
            assertThat(settle.remainingNanos()).as("%d ms in", at).isPositive();
        }
        clock.advance(Duration.ofMillis(100));
        settle.event();
        assertThat(settle.remainingNanos())
                .as("an event every 100 ms holds the pass back no longer than the max wait")
                .isLessThanOrEqualTo(0);
    }

    @Test
    void a_long_debounce_window_stretches_the_max_wait_to_several_windows() {
        FakeClock clock = new FakeClock();
        SourceWatch.Settle settle = new SourceWatch.Settle(clock, 1_000);
        clock.advance(Duration.ofMillis(3_500));
        settle.event();
        assertThat(millis(settle.remainingNanos()))
                .as("capped at %d windows from the first event", SourceWatch.MAX_WAIT_WINDOWS)
                .isEqualTo(SourceWatch.MAX_WAIT_WINDOWS * 1_000 - 3_500);
    }

    @Test
    void a_scala_edit_is_a_source_change(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path src = Files.createDirectories(project.resolve("src/main/scala/ex"));
        try (SourceWatch watch = SourceWatch.open(project, List.of(project.resolve("src")), 0)) {
            Path file = Files.writeString(src.resolve("Main.scala"), "object Main\n");
            assertThat(Pass.of(Objects.requireNonNull(watch.classify(src, file))))
                    .isEqualTo(Pass.RECOMPILE);
        }
    }

    /** {@code test/src} is a root below a directory that is not one; deleting and restoring it must not end its watch. */
    @Test
    void a_nested_root_deleted_and_made_again_is_watched_again(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\n");
        Path src = Files.createDirectories(project.resolve("src"));
        Path testSrc = Files.createDirectories(project.resolve("test/src"));
        Files.writeString(testSrc.resolve("MainTest.java"), "class MainTest {}\n");

        try (SourceWatch watch = SourceWatch.open(project, List.of(src, testSrc), 0)) {
            assertThat(watch.watches(project.resolve("test")))
                    .as("the directory a nested root is made in")
                    .isTrue();

            PathUtil.deleteRecursively(testSrc);
            Files.createDirectories(testSrc);
            Files.writeString(testSrc.resolve("OtherTest.java"), "class OtherTest {}\n");
            SourceWatch.Changes made = Objects.requireNonNull(
                    watch.event(project.resolve("test"), StandardWatchEventKinds.ENTRY_CREATE, testSrc));
            assertThat(Pass.of(made)).as("the restored root's files").isEqualTo(Pass.RECOMPILE);
            assertThat(watch.watches(testSrc)).as("the restored root").isTrue();

            assertThat(watch.event(
                            project.resolve("test"),
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            Files.writeString(project.resolve("test/notes.txt"), "x\n")))
                    .as("a file beside the root, in a directory watched only to see the root appear")
                    .isNull();
        }
    }

    /** A resource-only edit in the simple layout is under a default root, so it starts a pass. */
    @Test
    void the_default_roots_hold_the_simple_layouts_resources(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\nlayout = \"simple\"\n");
        Files.createDirectories(project.resolve("src"));
        Path resources = Files.createDirectories(project.resolve("resources"));
        Path testResources = Files.createDirectories(project.resolve("test/resources"));
        Files.createDirectories(project.resolve("test/src"));

        List<Path> roots = SourceWatch.defaultRoots(project);
        assertThat(roots).contains(resources, testResources);
        try (SourceWatch watch = SourceWatch.open(project, roots, 0)) {
            assertThat(watch.watches(resources)).isTrue();
            assertThat(watch.watches(testResources)).isTrue();
            for (Path dir : List.of(resources, testResources)) {
                Path file = Files.writeString(dir.resolve("app.properties"), "greeting=hi\n");
                SourceWatch.Changes changes =
                        Objects.requireNonNull(watch.event(dir, StandardWatchEventKinds.ENTRY_MODIFY, file));
                assertThat(Pass.of(changes)).as(dir.toString()).isEqualTo(Pass.COPY_RESOURCES);
            }
        }
    }
}
