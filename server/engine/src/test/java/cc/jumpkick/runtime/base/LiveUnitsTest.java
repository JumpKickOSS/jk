// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.JobWorkers;
import org.junit.jupiter.api.Test;

class LiveUnitsTest {

    @Test
    void a_unit_parked_at_a_gate_is_not_running_until_it_comes_back() {
        long job = 77_001L;
        JobWorkers.open(job);
        try (LiveUnits.Lease a = LiveUnits.enter();
                LiveUnits.Lease b = LiveUnits.enter()) {
            assertThat(LiveUnits.running()).isEqualTo(2);
            try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
                assertThat(LiveUnits.running())
                        .as("the parked unit shares with nobody yet")
                        .isEqualTo(1);
            }
            assertThat(LiveUnits.running()).isEqualTo(2);
        } finally {
            LiveUnits.end(job);
            JobWorkers.close();
        }
    }

    @Test
    void stepping_out_with_nothing_counted_is_a_no_op() {
        long job = 77_002L;
        JobWorkers.open(job);
        try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
            assertThat(LiveUnits.running()).isZero();
        } finally {
            assertThat(LiveUnits.running())
                    .as("closing does not mint a phantom unit")
                    .isZero();
            LiveUnits.end(job);
            JobWorkers.close();
        }
    }

    @Test
    void outside_a_job_nothing_is_counted() {
        try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
            assertThat(LiveUnits.running()).isZero();
        }
    }
}
