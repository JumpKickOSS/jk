// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapNotes;
import cc.jumpkick.engine.plugin.LearnedHeaps;
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
 * Every pull worker passes one class and runs out of its planned heap on the next, leaving the
 * rest of the queue undispatched. The heap retry runs the dying workers' classes and the queued
 * ones on the next rung, so the suite ends green with no shortfall row.
 */
@Tag("integration")
class JUnitLauncherPoolRetryTest {

    @AfterEach
    void reset() {
        HeapNotes.clear();
        SessionContext.reset();
    }

    @Test
    void the_classes_a_heap_killed_pool_left_queued_run_on_the_next_rung(@TempDir Path dir) throws Exception {
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        Path home = fakeJdk(dir);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path cache = Files.createDirectories(dir.resolve("cache"));
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        JUnitLauncher launcher = new JUnitLauncher()
                .withModuleLabel("g:app")
                .withHeaps(new LearnedHeaps(dir.resolve("state"), 128L << 20));

        TestSummary summary = launcher.run(home, classes, List.of(), cache, 2, Map.of(), TestProgressListener.noop());

        assertThat(summary.failures()).isEmpty();
        assertThat(summary.failed()).isZero();
        assertThat(summary.succeeded())
                .as("two passed before the deaths, two died, two were left queued")
                .isEqualTo(6);
        assertThat(summary.total()).isEqualTo(6);
        assertThat(HeapNotes.drain()).containsExactly("retried with 256 MiB heap after running out of 128 MiB");
        assertThat(Files.readString(home.resolve("selections")).lines().toList())
                .singleElement()
                .satisfies(filter -> assertThat(filter).contains("demo.E", "demo.F"));
    }

    /**
     * Lists {@code demo.A} to {@code demo.F}. As a pull worker it passes the first class it is
     * sent and dies of heap on the second. One-shot at a larger heap it passes every class its {@code --filter} names,
     * logging that filter to {@code selections}, and runs nothing the filter leaves out.
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
                mx=""
                filter=""
                for a in "$@"; do
                  case "$a" in
                    -Xmx*) mx="$a" ;;
                    --filter=*) filter="${a#--filter=}" ;;
                  esac
                done
                case " $* " in
                  *" --list-only "*)
                    for c in demo.A demo.B demo.C demo.D demo.E demo.F; do e "{\\"event\\":\\"discovered\\",\\"class\\":\\"$c\\"}"; done
                    e '{"event":"discovery_total","classes":6,"tests":6}'
                    exit 0 ;;
                  *" --pull "*)
                    e '{"event":"ready"}'
                    read -r line
                    t "${line#RUN }"
                    e '{"event":"ready"}'
                    read -r line
                    echo "Terminating due to java.lang.OutOfMemoryError: Java heap space"
                    exit 3 ;;
                esac
                echo "$filter" >> "$(dirname "$0")/../selections"
                for c in demo.A demo.B demo.C demo.D demo.E demo.F; do
                  case "$filter" in *"$c"*) t "$c" ;; esac
                done
                e '{"event":"plan_finished","duration_ms":1,"failed":false}'
                exit 0
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
