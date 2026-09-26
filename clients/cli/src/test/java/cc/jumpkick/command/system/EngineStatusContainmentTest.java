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
    void the_worker_line_names_budget_leased_and_queued() {
        assertThat(EngineStatusCommand.describeWorkers(-1, 0, 0)).isNull();
        assertThat(EngineStatusCommand.describeWorkers(14 * GIB, 3 * GIB + GIB / 2, 2))
                .isEqualTo("14.0 GiB budget, 3.5 GiB leased, 2 queued");
        assertThat(EngineStatusCommand.describeWorkers(512L << 20, 64L << 20, 0))
                .isEqualTo("512 MiB budget, 64 MiB leased, 0 queued");
    }
}
