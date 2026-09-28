// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The containment row of {@code jk engine status}. */
class EngineStatusContainmentTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void phrases_match_the_three_modes() {
        assertThat(EngineStatusCommand.describeContainment(null, null, -1)).isNull();
        assertThat(EngineStatusCommand.describeContainment("none", "", -1)).isEqualTo("none");
        assertThat(EngineStatusCommand.describeContainment("score-only", "cannot create a child cgroup", -1))
                .isEqualTo("score-only (cannot create a child cgroup)");
        assertThat(EngineStatusCommand.describeContainment("score-only", "", -1))
                .isEqualTo("score-only");
        assertThat(EngineStatusCommand.describeContainment("cgroup", "", 14 * GIB))
                .isEqualTo("cgroup (max 14.0 GiB)");
    }

    @Test
    void the_worker_line_names_the_budget_source_and_the_jvm_cap() {
        assertThat(EngineStatusCommand.describeWorkers(-1, 0, 0, 0, null, -1, -1, false))
                .isNull();
        assertThat(EngineStatusCommand.describeWorkers(14 * GIB, 3 * GIB + GIB / 2, 0, 2, "host", 4, 16, false))
                .isEqualTo("14.0 GiB budget (host), 3.5 GiB leased, 0 MiB overbooked, 2 queued, 4/16 JVMs");
        assertThat(EngineStatusCommand.describeWorkers(512L << 20, 64L << 20, 0, 0, "cgroup", 0, 8, false))
                .isEqualTo("512 MiB budget (cgroup), 64 MiB leased, 0 MiB overbooked, 0 queued, 0/8 JVMs");
        assertThat(EngineStatusCommand.describeWorkers(14 * GIB, 15 * GIB, GIB / 2, 2, "host", 9, 8, false))
                .isEqualTo("14.0 GiB budget (host), 15.0 GiB leased, 512 MiB overbooked, 2 queued, 9/8 JVMs");
        assertThat(EngineStatusCommand.describeWorkers(3 * GIB / 2, 672L << 20, 0, 3, "override", 2, 8, true))
                .isEqualTo(
                        "1.5 GiB budget (override JK_WORKER_BUDGET_MB), 672 MiB leased, 0 MiB overbooked, 3 queued, 2/8 JVMs, overbooking off");
        assertThat(EngineStatusCommand.describeWorkers(14 * GIB, GIB, -1, 0, null, -1, -1, false))
                .isEqualTo("14.0 GiB budget, 1.0 GiB leased, 0 MiB overbooked, 0 queued");
        assertThat(EngineStatusCommand.describeWorkers(14 * GIB, GIB, 0, 1, "cgroup", -1, -1, true))
                .isEqualTo("14.0 GiB budget (cgroup), 1.0 GiB leased, 0 MiB overbooked, 1 queued, overbooking off");
    }
}
