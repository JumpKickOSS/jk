// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.FakeWorkerShell;
import cc.jumpkick.orchestrator.TestProgressListener;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pull worker, or the one suite JVM of a single-worker run, that dies in the middle of a class
 * fails that class: its row names the class and the exit code, and the listener hears it as the class's failure, so the run's transcript names a
 * class a gate can run again. A worker that dies after it finished its classes names none: the
 * class it last ran passed. The fake {@code java} here speaks the runner's protocol: it lists
 * {@code demo.A}, {@code demo.B} and {@code demo.C} and, as a pull worker, runs each class it is
 * sent green and asks for another, until the class or the moment {@code DIE} names, where it exits
 * 3; as the single suite JVM it runs the three in turn, dying the same way.
 */
@Tag("integration")
class JUnitLauncherWorkerDeathTest {

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void a_worker_killed_mid_class_fails_that_class_with_its_exit_code(@TempDir Path dir) throws Exception {
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        var heard = new CopyOnWriteArrayList<Heard>();

        TestSummary summary = run(dir, "demo.B", heard);

        assertThat(summary.allPassed()).isFalse();
        assertThat(summary.succeeded())
                .as("A and C ran on the worker that lived")
                .isEqualTo(2);
        assertThat(summary.failures()).singleElement().satisfies(row -> {
            assertThat(row.className()).isEqualTo("demo.B");
            assertThat(row.method()).startsWith("(worker ");
            assertThat(row.message()).isEqualTo("test worker exited 3 mid-run (last class: demo.B)");
        });
        assertThat(heard)
                .as("the failure reaches the listener, which writes it to the run's transcript")
                .singleElement()
                .satisfies(failure -> {
                    assertThat(failure.className()).isEqualTo("demo.B");
                    assertThat(failure.message()).contains("exited 3");
                });
    }

    @Test
    void a_worker_that_dies_after_its_last_class_blames_no_class(@TempDir Path dir) throws Exception {
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        var heard = new CopyOnWriteArrayList<Heard>();

        TestSummary summary = run(dir, "after-done", heard);

        assertThat(summary.succeeded()).isEqualTo(3);
        List<TestFailureInfo> rows = summary.failures();
        assertThat(rows).isNotEmpty().allSatisfy(row -> {
            assertThat(row.className()).as("every class it ran passed").isEmpty();
            assertThat(row.message()).isEqualTo("test worker exited 3 mid-run");
        });
        assertThat(heard).hasSameSizeAs(rows).allSatisfy(failure -> assertThat(failure.className())
                .isEmpty());
    }

    @Test
    void a_single_fork_killed_mid_class_fails_that_class(@TempDir Path dir) throws Exception {
        var heard = new CopyOnWriteArrayList<Heard>();

        TestSummary summary = run(dir, "demo.B", heard, 1);

        assertThat(summary.allPassed()).isFalse();
        assertThat(summary.succeeded()).as("A ran before the fork died").isEqualTo(1);
        assertThat(summary.failures()).singleElement().satisfies(row -> {
            assertThat(row.className()).isEqualTo("demo.B");
            assertThat(row.method()).isEqualTo("(test run)");
            assertThat(row.message()).isEqualTo("worker exited 3");
        });
        assertThat(heard)
                .as("the failure reaches the listener, which writes it to the run's transcript")
                .singleElement()
                .satisfies(failure -> {
                    assertThat(failure.className()).isEqualTo("demo.B");
                    assertThat(failure.message()).isEqualTo("worker exited 3");
                });
    }

    private record Heard(String className, String message) {}

    private static TestSummary run(Path dir, String die, List<Heard> heard) throws Exception {
        return run(dir, die, heard, 2);
    }

    private static TestSummary run(Path dir, String die, List<Heard> heard, int workers) throws Exception {
        Path home = fakeJdk(dir, die);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        TestProgressListener listener = new TestProgressListener() {
            @Override
            public void onFailure(
                    String id,
                    String label,
                    String exClass,
                    String message,
                    String stack,
                    String engine,
                    String className,
                    String method,
                    int workerId) {
                heard.add(new Heard(className, message));
            }
        };
        return new JUnitLauncher()
                .withModuleLabel("g:app")
                .run(home, classes, List.of(), cache, workers, Map.of(), listener);
    }

    /**
     * The fake JDK. {@code die} is the class whose {@code RUN} the worker dies in, after its first
     * test started and before it finished, or {@code after-done} to die on {@code DONE}, every class
     * it was sent reported.
     */
    private static Path fakeJdk(Path dir, String die) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "bash required");
        Path home = dir.resolve("jdk");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path java = bin.resolve("java");
        String script = FakeWorkerShell.SHEBANG + "\n"
                + """
                e() { printf '%s\\n' "##JKT:$1"; }
                id() { printf '[engine:junit-jupiter]/[class:%s]' "$1"; }
                started() { e "{\\"event\\":\\"started\\",\\"type\\":\\"CONTAINER\\",\\"uniqueId\\":\\"$(id "$1")\\",\\"testClass\\":\\"$1\\"}"; }
                t() { e "{\\"event\\":\\"finished\\",\\"type\\":\\"TEST\\",\\"status\\":\\"SUCCESSFUL\\",\\"uniqueId\\":\\"$(id "$1")/[method:t()]\\",\\"testClass\\":\\"$1\\",\\"testMethod\\":\\"t()\\",\\"duration_ms\\":1}"; }
                finished() { e "{\\"event\\":\\"finished\\",\\"type\\":\\"CONTAINER\\",\\"status\\":\\"SUCCESSFUL\\",\\"uniqueId\\":\\"$(id "$1")\\",\\"testClass\\":\\"$1\\",\\"duration_ms\\":1}"; }
                case " $* " in
                  *" --list-only "*)
                    e '{"event":"discovered","class":"demo.A"}'
                    e '{"event":"discovered","class":"demo.B"}'
                    e '{"event":"discovered","class":"demo.C"}'
                    e '{"event":"discovery_total","classes":3,"tests":3}'
                    exit 0 ;;
                  *" --pull "*)
                    @CONNECT@
                    while true; do
                      e '{"event":"ready"}'
                      read -r line <&3 || exit 0
                      if [ "$line" = DONE ]; then
                        [ "@DIE@" = after-done ] && exit 3
                        exit 0
                      fi
                      cls="${line#RUN }"
                      started "$cls"
                      [ "$cls" = "@DIE@" ] && exit 3
                      t "$cls"
                      finished "$cls"
                    done ;;
                  *" --scan-classpath="*)
                    e '{"event":"discovery_total","classes":3,"tests":3}'
                    for cls in demo.A demo.B demo.C; do
                      started "$cls"
                      [ "$cls" = "@DIE@" ] && exit 3
                      t "$cls"
                      finished "$cls"
                    done
                    e '{"event":"plan_finished"}'
                    exit 0 ;;
                esac
                exit 2
                """.replace("@CONNECT@", FakeWorkerShell.CONNECT).replace("@DIE@", die);
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
