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

    /** {@code resources/} and {@code test/resources/} made after the loop starts are watched, and their files start a pass. */
    @Test
    void a_default_root_made_after_open_is_watched(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\nlayout = \"simple\"\n");
        Files.createDirectories(project.resolve("src"));
        Path resources = project.resolve("resources");
        Path testResources = project.resolve("test/resources");

        List<Path> roots = SourceWatch.defaultRoots(project);
        assertThat(roots).contains(resources, testResources);
        try (SourceWatch watch = SourceWatch.open(project, roots, 0)) {
            Files.createDirectories(resources);
            Files.writeString(resources.resolve("app.properties"), "greeting=hi\n");
            SourceWatch.Changes made =
                    Objects.requireNonNull(watch.event(project, StandardWatchEventKinds.ENTRY_CREATE, resources));
            assertThat(made).as("the new root's file").isEqualTo(new SourceWatch.Changes(false, true, false));
            assertThat(watch.watches(resources)).isTrue();

            Files.createDirectories(testResources);
            Files.writeString(testResources.resolve("fixture.txt"), "x\n");
            SourceWatch.Changes test = Objects.requireNonNull(
                    watch.event(project, StandardWatchEventKinds.ENTRY_CREATE, project.resolve("test")));
            assertThat(test).as("a root made with its parent").isEqualTo(new SourceWatch.Changes(false, true, false));
            assertThat(watch.watches(testResources)).isTrue();
        }
    }

    /** {@code jk dev} watches a sibling member's trees, outside the project; one missing at open is watched for. */
    @Test
    void a_root_outside_the_project_missing_at_open_is_watched_for(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path libResources = lib.resolve("resources");

        try (SourceWatch watch = SourceWatch.open(project, List.of(lib.resolve("src"), libResources), 0)) {
            assertThat(watch.watches(lib))
                    .as("the module the root will appear in")
                    .isTrue();
            assertThat(watch.event(
                            lib,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            Files.writeString(lib.resolve("notes.txt"), "x\n")))
                    .as("a file beside the roots")
                    .isNull();

            Files.createDirectories(libResources);
            Files.writeString(libResources.resolve("lib.properties"), "k=v\n");
            SourceWatch.Changes made =
                    Objects.requireNonNull(watch.event(lib, StandardWatchEventKinds.ENTRY_CREATE, libResources));
            assertThat(made).isEqualTo(new SourceWatch.Changes(false, true, false));
            assertThat(watch.watches(libResources)).isTrue();
        }
    }

    /** A source-named file kept as a resource, such as a code template, is copied, not compiled. */
    @Test
    void a_source_named_file_under_the_simple_layouts_resources_is_a_resource_change(@TempDir Path tmp)
            throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\nlayout = \"simple\"\n");
        Files.createDirectories(project.resolve("src"));
        Path resources = Files.createDirectories(project.resolve("resources"));
        Path testResources = Files.createDirectories(project.resolve("test/resources"));
        Files.createDirectories(project.resolve("test/src"));

        try (SourceWatch watch = SourceWatch.open(project, SourceWatch.defaultRoots(project), 0)) {
            for (Path dir : List.of(resources, testResources)) {
                for (String name : List.of("Foo.java", "Foo.kt")) {
                    Path file = Files.writeString(dir.resolve(name), "class Foo\n");
                    assertThat(Pass.of(Objects.requireNonNull(watch.classify(dir, file))))
                            .as(project.relativize(file).toString())
                            .isEqualTo(Pass.COPY_RESOURCES);
                }
            }
        }
    }

    /** A test suite made while {@code jk watch} runs is taken up once it holds sources; a stray file in it wakes nothing. */
    @Test
    void a_suite_made_after_open_is_watched_once_it_holds_sources(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\nlayout = \"simple\"\n");
        Files.createDirectories(project.resolve("src"));
        Path suite = project.resolve("integration");
        Path suiteSrc = suite.resolve("src");
        Path suiteResources = suite.resolve("resources");

        try (SourceWatch watch = SourceWatch.openDefault(project, 0, List.of())) {
            Files.createDirectories(suite);
            assertThat(watch.event(project, StandardWatchEventKinds.ENTRY_CREATE, suite))
                    .as("an empty suite directory")
                    .isNull();
            assertThat(watch.watches(suite))
                    .as("the directory a suite may become")
                    .isTrue();
            assertThat(watch.event(
                            suite,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            Files.writeString(suite.resolve("README.md"), "x\n")))
                    .as("a stray file in it")
                    .isNull();

            Path pkg = Files.createDirectories(suiteSrc.resolve("ex"));
            assertThat(watch.event(suite, StandardWatchEventKinds.ENTRY_CREATE, suiteSrc))
                    .as("its src/ with no sources yet")
                    .isNull();
            assertThat(watch.watches(pkg)).as("a directory under its src/").isTrue();

            Path test = Files.writeString(pkg.resolve("FlowIT.java"), "class FlowIT {}\n");
            SourceWatch.Changes first =
                    Objects.requireNonNull(watch.event(pkg, StandardWatchEventKinds.ENTRY_CREATE, test));
            assertThat(Pass.of(first)).as("its first source").isEqualTo(Pass.RECOMPILE);

            Files.createDirectories(suiteResources);
            Files.writeString(suiteResources.resolve("it.properties"), "k=v\n");
            assertThat(watch.event(suite, StandardWatchEventKinds.ENTRY_CREATE, suiteResources))
                    .as("its resources/, made once it is a suite")
                    .isEqualTo(new SourceWatch.Changes(false, true, false));
            assertThat(watch.event(
                            suite,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            Files.writeString(suite.resolve("README.md"), "y\n")))
                    .as("a stray file beside a suite's trees")
                    .isNull();
        }
    }

    /** A suite made whole, a directory not named as one, and one on disk with no sources at open. */
    @Test
    void only_a_directory_named_as_a_suite_becomes_one(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\nlayout = \"simple\"\n");
        Files.createDirectories(project.resolve("src"));
        Path waiting = Files.createDirectories(project.resolve("e2e/src"));

        try (SourceWatch watch = SourceWatch.openDefault(project, 0, List.of())) {
            assertThat(watch.watches(waiting))
                    .as("a suite's src/ on disk with no sources")
                    .isTrue();
            Path late = Files.writeString(waiting.resolve("E2eTest.java"), "class E2eTest {}\n");
            assertThat(watch.event(waiting, StandardWatchEventKinds.ENTRY_CREATE, late))
                    .isEqualTo(new SourceWatch.Changes(true, false, false));

            Path whole = Files.createDirectories(project.resolve("perf/src"));
            Files.writeString(whole.resolve("PerfTest.kt"), "class PerfTest\n");
            assertThat(watch.event(project, StandardWatchEventKinds.ENTRY_CREATE, project.resolve("perf")))
                    .as("a suite made with its sources")
                    .isEqualTo(new SourceWatch.Changes(true, false, false));
            assertThat(watch.watches(whole)).isTrue();

            for (String name : List.of("docs", "Demo", "fixtures")) {
                Path dir = Files.createDirectories(project.resolve(name).resolve("src"));
                Files.writeString(dir.resolve("X.java"), "class X {}\n");
                assertThat(watch.event(project, StandardWatchEventKinds.ENTRY_CREATE, project.resolve(name)))
                        .as(name)
                        .isNull();
                assertThat(watch.watches(dir)).as(name).isFalse();
            }
        }
    }

    /** A sibling member's directory, deleted and made again, is seen from the workspace root it lies in. */
    @Test
    void a_sibling_member_made_again_is_watched_again(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("ws"));
        Path project = Files.createDirectories(workspace.resolve("app"));
        Path lib = workspace.resolve("lib");
        Path libSrc = Files.createDirectories(lib.resolve("src"));
        Path libResources = Files.createDirectories(lib.resolve("resources"));

        try (SourceWatch watch = SourceWatch.open(project, List.of(libSrc, libResources), 0)) {
            assertThat(watch.watches(workspace))
                    .as("the directory the member is made in")
                    .isTrue();
            assertThat(watch.event(
                            workspace,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            Files.writeString(workspace.resolve("notes.txt"), "x\n")))
                    .as("a file beside the member")
                    .isNull();

            PathUtil.deleteRecursively(lib);
            Files.createDirectories(libSrc);
            Files.createDirectories(libResources);
            Files.writeString(libSrc.resolve("Lib.java"), "class Lib {}\n");
            Files.writeString(libResources.resolve("lib.properties"), "k=v\n");
            assertThat(watch.event(workspace, StandardWatchEventKinds.ENTRY_CREATE, lib))
                    .as("both trees of the member made again")
                    .isEqualTo(new SourceWatch.Changes(true, true, false));
            assertThat(watch.watches(lib)).isTrue();
        }
    }

    /** A sibling member missing at open is watched for from the workspace root. */
    @Test
    void a_sibling_member_missing_at_open_is_watched_for(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectories(tmp.resolve("ws"));
        Path project = Files.createDirectories(workspace.resolve("app"));
        Path lib = workspace.resolve("lib");

        try (SourceWatch watch = SourceWatch.open(project, List.of(lib.resolve("src")), 0)) {
            assertThat(watch.watches(workspace)).isTrue();
            Files.createDirectories(lib.resolve("src"));
            Files.writeString(lib.resolve("src/Lib.java"), "class Lib {}\n");
            assertThat(watch.event(workspace, StandardWatchEventKinds.ENTRY_CREATE, lib))
                    .isEqualTo(new SourceWatch.Changes(true, false, false));
            assertThat(watch.watches(lib.resolve("src"))).isTrue();
        }
    }

    /** {@code test/} restored with both {@code src/} and {@code resources/} in it brings back both roots. */
    @Test
    void a_directory_made_with_two_roots_in_it_takes_up_both(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\nlayout = \"simple\"\n");
        Path testSrc = project.resolve("test/src");
        Path testResources = project.resolve("test/resources");

        try (SourceWatch watch = SourceWatch.open(project, List.of(testSrc, testResources), 0)) {
            Files.createDirectories(testSrc);
            Files.createDirectories(testResources);
            Files.writeString(testSrc.resolve("MainTest.java"), "class MainTest {}\n");
            Files.writeString(testResources.resolve("fixture.txt"), "x\n");
            assertThat(watch.event(project, StandardWatchEventKinds.ENTRY_CREATE, project.resolve("test")))
                    .isEqualTo(new SourceWatch.Changes(true, true, false));
            assertThat(watch.watches(testSrc)).isTrue();
            assertThat(watch.watches(testResources)).isTrue();
        }
    }
}
