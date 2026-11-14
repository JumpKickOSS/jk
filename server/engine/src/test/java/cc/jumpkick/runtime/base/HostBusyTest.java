// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.builds.ProjectStore;
import cc.jumpkick.host.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A wall measured under one external load is priced for a run under another. */
class HostBusyTest {

    @Test
    void a_replay_under_more_load_than_its_sample_is_priced_higher_by_the_elasticity() {
        assertThat(HostBusy.factor(0.5, 0)).isCloseTo(Math.pow(1.5, HostBusy.ELASTICITY), within(1e-9));
        assertThat(HostBusy.factor(0.6, 0.3))
                .as("twice its sample's load")
                .isCloseTo(Math.pow(1.6 / 1.3, HostBusy.ELASTICITY), within(1e-9))
                .isGreaterThan(1.0);
        assertThat(HostBusy.factor(0, 0.5))
                .as("a quieter host than the sample's")
                .isLessThan(1.0);
        assertThat(HostBusy.factor(-1, 0.5)).as("load unknown now").isEqualTo(1.0);
        assertThat(HostBusy.factor(0.5, -1))
                .as("a sample from before load was recorded")
                .isEqualTo(1.0);
        assertThat(HostBusy.factor(5, 0))
                .as("a share past every core reads as every core")
                .isEqualTo(HostBusy.factor(1, 0));
    }

    @Test
    void a_proc_stat_cpu_line_reads_as_total_and_busy_jiffies() {
        // user nice system idle iowait irq softirq steal guest guest_nice
        long[] t = HostBusy.parse("cpu  100 5 50 800 40 3 2 0 7 0");
        assertThat(t[0]).isEqualTo(100 + 5 + 50 + 800 + 40 + 3 + 2);
        assertThat(t[1])
                .as("idle and iowait are not busy; guest time is inside user")
                .isEqualTo(160);
    }

    @Test
    void the_ledger_reads_each_wall_as_an_idle_hosts_by_its_own_runs_load(@TempDir Path home) {
        RunShapes.Shape shape = new RunShapes.Shape("", false, false);
        Set<String> frontier = Set.of("/ws/a");
        // The same work: 2 s idle, 2.6 s and 2.6 s under half the host busy elsewhere.
        double loaded = Math.round(2_000 / HostBusy.factor(0, 0.5));
        for (double[] run : new double[][] {{0, 2_000}, {0.5, loaded}, {0.5, loaded}}) {
            StepLedger.append(
                    home,
                    shape,
                    frontier,
                    Map.of(),
                    Map.of(),
                    StepLedger.UNKNOWN_WARMTH,
                    run[0],
                    List.of(new StepLedger.Sample("/ws/a", "guard", "SUCCESS", (long) run[1])));
        }
        assertThat(StepLedger.load(home)
                        .price("/ws/a", "guard", "SUCCESS", shape, frontier)
                        .orElse(0))
                .as("every sample read as the idle host's 2 s, not the loaded median")
                .isBetween(1_999L, 2_001L);
    }

    @Test
    void a_proc_pid_stat_line_reads_as_its_own_and_reaped_childrens_ticks() {
        String line = "4242 (java (main) x) S 1 4242 4242 0 -1 4194560 100 0 0 0 70 30 500 200 20 0 41 0 9 0 0";
        assertThat(HostBusy.parseStat(line))
                .as("utime + stime + cutime + cstime")
                .isEqualTo(70 + 30 + 500 + 200);
        assertThat(HostBusy.statTicks(Path.of("/proc/self/stat-that-is-not-there")))
                .isEqualTo(-1);
    }

    @Test
    void a_run_whose_load_is_unknown_records_it_as_unknown(@TempDir Path home) {
        StepLedger.append(
                home,
                RunShapes.Shape.DEFAULT,
                null,
                Map.of(),
                Map.of(),
                StepLedger.UNKNOWN_WARMTH,
                StepLedger.UNKNOWN_BUSY,
                List.of(new StepLedger.Sample("/ws/a", "guard", "SUCCESS", 2_000)));
        assertThat(ProjectStore.runs(home)).singleElement().asString().contains("\"busy\":\"unknown\"");
        assertThat(StepLedger.load(home).price("/ws/a", "guard", "SUCCESS", RunShapes.Shape.DEFAULT, Set.of()))
                .as("read as it ran")
                .hasValue(2_000);
    }

    @Test
    @Timeout(60)
    void an_idle_engine_stops_sampling_and_its_next_read_still_has_an_interval() throws Exception {
        Assumptions.assumeTrue(Files.isReadable(Path.of("/proc/stat")), "Linux only");
        HostBusy.forTests(null);
        HostBusy.keepWarmForTests(Duration.ofMillis(1_500));
        try {
            HostBusy.jobStarted(-42);
            long atStart = HostBusy.SAMPLED.get();
            Thread.sleep(3_000);
            assertThat(HostBusy.SAMPLED.get() - atStart)
                    .as("samples while a job runs")
                    .isGreaterThanOrEqualTo(2);
            HostBusy.jobEnded(-42);
            Thread.sleep(1_500 + 2_000);
            assertThat(HostBusy.sampling())
                    .as("sampling past the last job and the keep-warm")
                    .isFalse();
            long idle = HostBusy.SAMPLED.get();
            Thread.sleep(2_500);
            assertThat(HostBusy.SAMPLED.get())
                    .as("samples taken by an idle engine")
                    .isEqualTo(idle);

            long before = Clock.SYSTEM.nanos();
            double read = HostBusy.external();
            long tookMs = (Clock.SYSTEM.nanos() - before) / 1_000_000;
            assertThat(read).as("the first read after idle").isBetween(0.0, 1.0);
            assertThat(tookMs).isBetween(HostBusy.SHORT_READ.toMillis() - 20, HostBusy.SHORT_READ.toMillis() + 500);
        } finally {
            HostBusy.keepWarmForTests(null);
        }
    }
}
