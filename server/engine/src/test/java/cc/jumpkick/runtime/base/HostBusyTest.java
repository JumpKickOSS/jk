// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A wall measured under one external load is priced for a run under another (JK-6BJTV). */
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
}
