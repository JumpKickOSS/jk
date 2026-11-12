// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Request;
import cc.jumpkick.task.IoLedger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LiveUnitsTest {

    private static Request job(long id) {
        return Request.of(id, new IoLedger()).with(LiveUnits.SLOT, new LiveUnits());
    }

    @Test
    void a_unit_parked_at_a_gate_is_not_running_until_it_comes_back() {
        job(77_001L).run(() -> {
            try (LiveUnits.Lease a = LiveUnits.enter();
                    LiveUnits.Lease b = LiveUnits.enter()) {
                assertThat(LiveUnits.running()).isEqualTo(2);
                try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
                    assertThat(LiveUnits.running())
                            .as("the parked unit shares with nobody yet")
                            .isEqualTo(1);
                }
                assertThat(LiveUnits.running()).isEqualTo(2);
            }
        });
    }

    @Test
    void stepping_out_with_nothing_counted_is_a_no_op() {
        job(77_002L).run(() -> {
            try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
                assertThat(LiveUnits.running()).isZero();
            } finally {
                assertThat(LiveUnits.running())
                        .as("closing does not mint a phantom unit")
                        .isZero();
            }
        });
    }

    @Test
    void outside_a_job_nothing_is_counted() {
        try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
            assertThat(LiveUnits.running()).isZero();
        }
    }

    @Test
    void a_unit_that_enters_after_its_job_ended_is_not_counted_by_the_next_job_under_that_id() {
        AtomicReference<LiveUnits.Lease> late = new AtomicReference<>();
        // The job has torn down; a task it handed off still enters and has not left yet.
        job(77_003L).run(() -> late.set(LiveUnits.enter()));
        try {
            job(77_003L).run(() -> assertThat(LiveUnits.running())
                    .as("the next job under the id starts from its own count")
                    .isZero());
        } finally {
            late.get().close();
        }
    }
}
