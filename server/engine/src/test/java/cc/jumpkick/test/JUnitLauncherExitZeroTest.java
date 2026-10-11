// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test JVM that exits 0 before finishing its work — a test, or the code under it, called {@code
 * System.exit(0)} after a few tests passed — is a red run in every mode, never a green one the next
 * build skips. The fake {@code java} here speaks the runner's protocol up to that exit: one-shot, it
 * reports two of three discovered tests and quits without {@code plan_finished}; as a pull worker,
 * it runs the one class it was sent and quits without waiting for {@code DONE}.
 */
@Tag("integration")
class JUnitLauncherExitZeroTest {

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_one_shot_fork_that_exits_0_mid_plan_fails_the_run(@TempDir Path dir) throws Exception {
        Path home = fakeJdk(dir);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));

        TestSummary summary = new JUnitLauncher()
                .withModuleLabel("g:app")
                .run(home, classes, List.of(), cache, 1, Map.of(), TestProgressListener.noop());

        assertThat(summary.allPassed()).isFalse();
        assertThat(summary.succeeded()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failures()).singleElement().satisfies(row -> {
            assertThat(row.method()).isEqualTo("(test run)");
            assertThat(row.message())
                    .startsWith("worker exited 0 before finishing its plan (2 of 3 discovered tests reported)");
        });
    }

    @Test
    void a_pull_worker_that_exits_0_before_done_fails_the_run_and_names_the_classes_left(@TempDir Path dir)
            throws Exception {
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        Path home = fakeJdk(dir);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));

        TestSummary summary = new JUnitLauncher()
                .withModuleLabel("g:app")
                .run(home, classes, List.of(), cache, 2, Map.of(), TestProgressListener.noop());

        assertThat(summary.workers()).isEqualTo(2);
        assertThat(summary.allPassed()).isFalse();
        assertThat(summary.succeeded())
                .as("one class per worker ran before it quit")
                .isEqualTo(2);
        assertThat(summary.failed())
                .as("two workers quit early, one class never ran")
                .isEqualTo(3);
        List<TestFailureInfo> rows = summary.failures();
        assertThat(rows).hasSize(3);
        assertThat(rows.subList(0, 2)).allSatisfy(row -> {
            assertThat(row.method()).startsWith("(worker ");
            assertThat(row.className()).startsWith("demo.");
            assertThat(row.message())
                    .startsWith("test worker exited 0 before it was told DONE (last class dispatched: demo.");
        });
        assertThat(rows.get(2).method()).isEqualTo("(test run)");
        assertThat(rows.get(2).message()).isEqualTo("test run incomplete: 1 class never ran");
        assertThat(RunShortfall.rows(rows)).isEqualTo(1);
    }

    /**
     * Lists {@code demo.A}, {@code demo.B}, {@code demo.C}; one-shot, reports {@code A} and {@code
     * B} green and exits 0 with no {@code plan_finished}; as a pull worker, reports the one class it
     * is sent green and exits 0 without reading on to {@code DONE}.
     */
    private static Path fakeJdk(Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        Path home = dir.resolve("jdk");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path java = bin.resolve("java");
        String script = """
                #!/bin/sh
                e() { printf '%s\\n' "##JKT:$1"; }
                t() { e "{\\"event\\":\\"finished\\",\\"type\\":\\"TEST\\",\\"status\\":\\"SUCCESSFUL\\",\\"uniqueId\\":\\"[engine:junit-jupiter]/[class:$1]/[method:t()]\\",\\"testClass\\":\\"$1\\",\\"testMethod\\":\\"t()\\",\\"duration_ms\\":1}"; }
                case " $* " in
                  *" --list-only "*)
                    e '{"event":"discovered","class":"demo.A"}'
                    e '{"event":"discovered","class":"demo.B"}'
                    e '{"event":"discovered","class":"demo.C"}'
                    e '{"event":"discovery_total","classes":3,"tests":3}'
                    exit 0 ;;
                  *" --pull "*)
                    e '{"event":"ready"}'
                    read -r line
                    t "${line#RUN }"
                    exit 0 ;;
                  *)
                    e '{"event":"discovery_total","classes":3,"tests":3}'
                    t demo.A
                    t demo.B
                    exit 0 ;;
                esac
                """;
        Files.writeString(java, script);
        Files.setPosixFilePermissions(
                java,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return home;
    }
}
