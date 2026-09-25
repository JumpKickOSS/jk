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
}
