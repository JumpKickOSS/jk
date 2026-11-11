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
}
