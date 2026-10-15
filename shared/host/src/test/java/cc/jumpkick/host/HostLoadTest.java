// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A bound grows with the load per processor and never shrinks below what it was on a quiet host. */
class HostLoadTest {

    @Test
    void a_quiet_or_unknown_host_leaves_the_bound_alone() {
        assertThat(HostLoad.factor(0.5, 24)).isEqualTo(1);
        assertThat(HostLoad.factor(-1, 24))
                .as("a platform that reports no load average")
                .isEqualTo(1);
        assertThat(HostLoad.factor(24, 24)).isEqualTo(1);
    }

    @Test
    void a_loaded_host_stretches_the_bound_by_its_load_per_processor_up_to_a_cap() {
        assertThat(HostLoad.factor(100, 24)).isEqualTo(5);
        assertThat(HostLoad.factor(25, 24))
                .as("rounded up: any excess costs a whole step")
                .isEqualTo(2);
        assertThat(HostLoad.factor(1_000, 24)).isEqualTo(HostLoad.MAX_FACTOR);
    }
}
