// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestTierTimeoutTest {

    private static TestSelection sel(List<String> include, List<String> exclude) {
        return TestSelection.of(List.of(), false, include, exclude);
    }

    @Test
    void the_fast_tier_stops_a_method_at_four_budgets() {
        TestTier fast = TestTier.of(sel(List.of(), List.of("integration")), null, 5_000);
        assertThat(fast.stopMs()).isEqualTo(20_000);
        assertThat(fast.timeoutArgs())
                .containsExactly("-Djunit.jupiter.execution.timeout.testable.method.default=20000 ms");
    }

    @Test
    void a_profile_or_no_budget_stops_nothing() {
        assertThat(TestTier.of(sel(List.of("integration"), List.of("slow")), "integration", 5_000)
                        .timeoutArgs())
                .isEmpty();
        assertThat(TestTier.of(sel(List.of(), List.of("integration")), null, 0).timeoutArgs())
                .isEmpty();
    }
}
