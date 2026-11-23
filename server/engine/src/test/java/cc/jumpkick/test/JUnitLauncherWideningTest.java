// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.FakeWorkerShell;
import cc.jumpkick.engine.plugin.OverbookSignals;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.orchestrator.TestProgressListener;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite that starts narrow because the build was wide widens once the share grows, and only as
 * far as the lease ledger would grant one more at once, past the budget when the host has room
 * to overbook: every pull worker it adds forks through a lease. The fake
 * {@code java} lists six classes and, as a pull worker, takes a second over each.
 */
@Tag("integration")
class JUnitLauncherWideningTest {

    private static final long GIB = 1L << 30;

    @AfterEach
    void reset() {
        WorkerLeases.engineForTests(null);
        SessionContext.reset();
    }

    @Test
    void a_suite_started_at_one_jvm_takes_a_second_once_the_share_grows(@TempDir Path dir) throws Exception {
        assumeTrue(TestWorkers.effectiveJobs() >= 2, "the jobs budget allows two test workers");
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 16 * GIB, () -> 8, id -> false);
        WorkerLeases.engineForTests(ledger);
        Path home = fakeJdk(dir);
        // The build is wide when the suite starts and has narrowed by the pool's first look.
        AtomicInteger reads = new AtomicInteger();
        RunningJvms peak = RunningJvms.watch(ledger);

        TestSummary summary = launcher(dir, () -> reads.getAndIncrement() == 0 ? 1 : 8)
                .run(home, classes(dir), List.of(), cache(dir), 1, Map.of(), TestProgressListener.noop());

        peak.stop();
        assertThat(summary.failures()).isEmpty();
        assertThat(summary.succeeded()).isEqualTo(6);
        assertThat(summary.workers()).as("the pool widened from one").isEqualTo(2);
        assertThat(pullWorkers(home)).isEqualTo(2);
        assertThat(reads.get())
                .as("the share was read again while classes were queued")
                .isGreaterThan(1);
        assertThat(peak.most()).as("both workers held a lease").isEqualTo(2);
    }

    @Test
    void a_suite_stays_at_what_the_ledger_has_slots_for(@TempDir Path dir) throws Exception {
        assumeTrue(TestWorkers.effectiveJobs() >= 2, "the jobs budget allows two test workers");
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 16 * GIB, () -> 1, id -> false);
        WorkerLeases.engineForTests(ledger);
        Path home = fakeJdk(dir);
        RunningJvms peak = RunningJvms.watch(ledger);

        TestSummary summary = launcher(dir, () -> 8)
                .run(home, classes(dir), List.of(), cache(dir), 1, Map.of(), TestProgressListener.noop());

        peak.stop();
        assertThat(summary.failures()).isEmpty();
        assertThat(summary.succeeded()).isEqualTo(6);
        assertThat(summary.workers()).isEqualTo(1);
        assertThat(pullWorkers(home)).isEqualTo(1);
        assertThat(peak.most()).isEqualTo(1);
    }

    @Test
    void a_suite_widens_past_the_budget_when_the_host_has_room_to_overbook(@TempDir Path dir) throws Exception {
        assumeTrue(TestWorkers.effectiveJobs() >= 2, "the jobs budget allows two test workers");
        assumeTrue(TestWorkers.clampByHeap(2) == 2, "host heap allows two test workers");
        OverbookSignals.Source healthy = () -> new OverbookSignals.Reading(true, 256 * GIB, 0, 0);
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 16 * GIB, () -> 8, id -> false, healthy);
        WorkerLeases.engineForTests(ledger);
        Path home = fakeJdk(dir);
        RunningJvms peak = RunningJvms.watch(ledger);

        TestSummary summary;
        // Another module holds all but 1 GiB of the budget, so a second worker fits only past it.
        try (WorkerLeases.Grant other = ledger.acquireBytes(15 * GIB, false, null)) {
            summary = launcher(dir, () -> 8)
                    .run(home, classes(dir), List.of(), cache(dir), 1, Map.of(), TestProgressListener.noop());
        }

        peak.stop();
        assertThat(summary.failures()).isEmpty();
        assertThat(summary.succeeded()).isEqualTo(6);
        assertThat(summary.workers()).as("the pool widened past the budget").isEqualTo(2);
        assertThat(pullWorkers(home)).isEqualTo(2);
        assertThat(peak.most()).as("both workers held a lease").isEqualTo(2);
    }

    private static JUnitLauncher launcher(Path dir, IntSupplier share) {
        SessionContext.install(SessionContext.current().withWorkingDir(dir).withJvm(PluginTuning.NONE));
        return new JUnitLauncher().withModuleLabel("g:app").withAutoShare(true).withWidening(share);
    }

    private static Path classes(Path dir) throws Exception {
        return Files.createDirectories(dir.resolve("classes"));
    }

    private static Path cache(Path dir) throws Exception {
        return Files.createDirectories(dir.resolve("cache"));
    }

    private static long pullWorkers(Path home) throws Exception {
        return Files.readAllLines(home.resolve("pulls")).size();
    }

    /** The most JVMs the ledger had leased at once, sampled every few milliseconds. */
    private static final class RunningJvms {
        private final AtomicInteger most = new AtomicInteger();
        private final Thread sampler;

        private RunningJvms(WorkerLeases.Ledger ledger) {
            sampler = Thread.ofPlatform().daemon().start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    most.accumulateAndGet(ledger.snapshot().runningJvms(), Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }

        static RunningJvms watch(WorkerLeases.Ledger ledger) {
            return new RunningJvms(ledger);
        }

        void stop() throws InterruptedException {
            sampler.interrupt();
            sampler.join();
        }

        int most() {
            return most.get();
        }
    }

    /**
     * Lists {@code demo.A} to {@code demo.F}. As a pull worker it notes its start in {@code pulls},
     * then passes each class it is sent after a second, until {@code DONE}.
     */
    private static Path fakeJdk(Path dir) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "bash required");
        Path home = dir.resolve("jdk");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path java = bin.resolve("java");
        String script = FakeWorkerShell.SHEBANG + "\n" + """
                e() { printf '%s\\n' "##JKT:$1"; }
                t() { e "{\\"event\\":\\"finished\\",\\"type\\":\\"TEST\\",\\"status\\":\\"SUCCESSFUL\\",\\"uniqueId\\":\\"[engine:junit-jupiter]/[class:$1]/[method:t()]\\",\\"testClass\\":\\"$1\\",\\"testMethod\\":\\"t()\\",\\"duration_ms\\":1}"; }
                case " $* " in
                  *" --list-only "*)
                    for c in demo.A demo.B demo.C demo.D demo.E demo.F; do e "{\\"event\\":\\"discovered\\",\\"class\\":\\"$c\\"}"; done
                    e '{"event":"discovery_total","classes":6,"tests":6}'
                    exit 0 ;;
                  *" --pull "*)
                    echo pull >> "$(dirname "$0")/../pulls"
                    @CONNECT@
                    while :; do
                      e '{"event":"ready"}'
                      read -r line <&3 || exit 0
                      case "$line" in
                        "RUN "*) sleep 1; t "${line#RUN }" ;;
                        *) exit 0 ;;
                      esac
                    done ;;
                esac
                exit 2
                """.replace("@CONNECT@", FakeWorkerShell.CONNECT);
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
