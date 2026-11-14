// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.TaskNames;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The key a suite's timings are kept under: the selection it ran, nothing broader. */
class SuiteSelectionTest {

    @Test
    void the_default_selection_keeps_the_bare_step() {
        assertThat(SuiteSelection.step(TestSelection.DEFAULT, null)).isEqualTo(TaskNames.RUN_TESTS);
        assertThat(SuiteSelection.step(TestSelection.of(List.of("test"), false, List.of(), List.of()), null))
                .isEqualTo(TaskNames.RUN_TESTS);
    }

    @Test
    void a_profile_that_selects_tags_names_the_suite_by_the_profile() {
        TestSelection integration = TestSelection.of(List.of(), false, List.of("integration"), List.of(), true);
        assertThat(SuiteSelection.step(integration, "integration")).isEqualTo("run-tests@integration");
        assertThat(SuiteSelection.step(integration, null))
                .as("the same tags without the profile are the same tier")
                .isEqualTo("run-tests@integration");
    }

    @Test
    void tiers_never_share_a_step() {
        TestSelection integration = TestSelection.of(List.of(), false, List.of("integration"), List.of());
        TestSelection slow = TestSelection.of(List.of(), false, List.of("slow"), List.of());
        TestSelection all = TestSelection.of(List.of(), true, List.of(), List.of());
        assertThat(List.of(
                        SuiteSelection.step(TestSelection.DEFAULT, null),
                        SuiteSelection.step(integration, null),
                        SuiteSelection.step(slow, null),
                        SuiteSelection.step(all, null)))
                .doesNotHaveDuplicates();
    }

    @Test
    void a_key_survives_the_ledgers_key_grammar() {
        TestSelection odd = TestSelection.of(List.of("my suite"), false, List.of("a.b", "c/d"), List.of());
        assertThat(SuiteSelection.step(odd, null)).matches("run-tests@[A-Za-z0-9_-]+");
    }

    @Test
    void only_a_class_filter_makes_a_run_partial() {
        assertThat(SuiteSelection.partial(TestSelection.DEFAULT)).isFalse();
        assertThat(SuiteSelection.partial(TestSelection.of(List.of(), false, List.of("integration"), List.of())))
                .isFalse();
        assertThat(SuiteSelection.partial(TestSelection.of(
                        List.of(), false, List.of(), List.of(), false, false, false, false, List.of("FooTest"))))
                .isTrue();
    }

    /** jk test, the push gate and one tier keep their own ledger shapes; a profile alone still keys one. */
    @Test
    void each_suite_selection_is_its_own_tier_in_the_ledger() {
        TestSelection guard =
                TestSelection.of(List.of("test", "integration", "e2e"), false, List.of(), List.of(), false, true);
        TestSelection integration = TestSelection.of(List.of("integration"), false, List.of(), List.of());
        assertThat(SuiteSelection.tier(TestSelection.DEFAULT, null)).isEmpty();
        assertThat(SuiteSelection.tier(TestSelection.DEFAULT, "ci")).isEqualTo("ci");
        assertThat(SuiteSelection.tier(guard, null)).isEqualTo("test-integration-e2e");
        assertThat(SuiteSelection.tier(integration, null)).isEqualTo("integration");
    }

    /** A run without the default suite is a slow tier; one beside it (-g, --all) is a blend, priced as the default. */
    @Test
    void a_slow_tier_is_a_run_without_the_default_suite_or_one_that_includes_tags() {
        assertThat(SuiteSelection.slowTier(TestSelection.DEFAULT)).isFalse();
        assertThat(SuiteSelection.slowTier(TestSelection.of(List.of("integration"), false, List.of(), List.of())))
                .isTrue();
        assertThat(SuiteSelection.slowTier(TestSelection.of(List.of(), false, List.of("slow"), List.of())))
                .isTrue();
        assertThat(SuiteSelection.slowTier(
                        TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true)))
                .isFalse();
        assertThat(SuiteSelection.slowTier(TestSelection.of(List.of(), true, List.of(), List.of())))
                .isFalse();
    }
}
