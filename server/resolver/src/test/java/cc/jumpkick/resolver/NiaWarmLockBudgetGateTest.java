// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The NIA timing budget applies to a whole-host JVM on an idle machine and to nothing else. */
class NiaWarmLockBudgetGateTest {

    @Test
    void a_jvm_pinned_below_the_host_skips_the_budget_and_says_how_to_lift_the_pin() {
        String reason = NiaWarmLockTimingTest.budgetSkipReason(0.5, 1, 24);
        assertThat(reason).contains("jvm-cpus=1").contains("host-cpus=24").contains("-XX:ActiveProcessorCount=24");
    }

    @Test
    void a_loaded_host_skips_the_budget_naming_the_load() {
        assertThat(NiaWarmLockTimingTest.budgetSkipReason(12.0, 24, 24))
                .contains("loadavg=12.0")
                .contains("not a quiet machine");
        assertThat(NiaWarmLockTimingTest.budgetSkipReason(-1.0, 24, 24))
                .as("a platform that reports no load average is not proven quiet")
                .contains("not a quiet machine");
    }

    @Test
    void a_whole_host_jvm_on_a_quiet_machine_is_judged_against_the_budget() {
        assertThat(NiaWarmLockTimingTest.budgetSkipReason(3.0, 24, 24)).isNull();
    }
}
