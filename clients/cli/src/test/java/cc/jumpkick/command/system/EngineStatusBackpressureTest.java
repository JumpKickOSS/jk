// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The {@code Backpressure} row of {@code jk engine status}: the counts that are not zero. */
class EngineStatusBackpressureTest {

    @Test
    void the_row_names_each_count_that_is_not_zero() {
        String json = "{\"wireSpilledLines\":12,\"wireDroppedClients\":1,\"admissionWaits\":3,"
                + "\"admissionCollections\":2,\"leaseLockWaits\":0,\"leaseReclaims\":1}";
        assertThat(EngineStatusCommand.describeBackpressure(json))
                .isEqualTo("12 lines spilled to disk, 1 client dropped for not reading, 3 jobs waited for memory, "
                        + "2 collections to measure memory, leases of 1 gone engine reclaimed");
    }

    @Test
    void no_row_when_every_count_is_zero_or_the_engine_did_not_report() {
        String zeros = "{\"wireSpilledLines\":0,\"wireDroppedClients\":0,\"admissionWaits\":0,"
                + "\"admissionCollections\":0,\"leaseLockWaits\":0,\"leaseReclaims\":0}";
        assertThat(EngineStatusCommand.describeBackpressure(zeros)).isNull();
        assertThat(EngineStatusCommand.describeBackpressure(null)).isNull();
        assertThat(EngineStatusCommand.describeBackpressure("{\"leaseLockWaits\":4}"))
                .isEqualTo("4 waits on the shared leases file");
    }
}
