// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which suites a run runs, and whether its launch is the walled default tier. */
class SuiteChoiceTest {

    private static final JkBuild UNTIERED = JkBuildParser.parse("""
            group = "t"
            name = "m"
            version = "1.0.0"
            """);

    private static final JkBuild TIERED = JkBuildParser.parse("""
            group = "t"
            name = "m"
            version = "1.0.0"

            [test.suites.slow]
            tags = ["slow"]
            """);

    @Test
    void the_default_suite_alone_in_a_tiered_module_is_the_fast_tier() {
        assertThat(SuiteChoice.fastTier(Set.of("test"), TestSelection.DEFAULT, TIERED))
                .isTrue();
    }

    @Test
    void a_tag_suite_beside_it_an_included_tag_or_an_untiered_module_is_not() {
        assertThat(SuiteChoice.fastTier(Set.of("test", "slow"), TestSelection.DEFAULT, TIERED))
                .as("a suite that claims a tag rides the same launch")
                .isFalse();
        assertThat(SuiteChoice.fastTier(
                        Set.of("test"), TestSelection.of(List.of(), false, List.of("smoke"), List.of()), TIERED))
                .as("the run includes a tag")
                .isFalse();
        assertThat(SuiteChoice.fastTier(Set.of("test"), TestSelection.DEFAULT, UNTIERED))
                .as("a project that never sorted its tests")
                .isFalse();
        assertThat(SuiteChoice.fastTier(Set.of("slow"), TestSelection.DEFAULT, TIERED))
                .as("the default suite does not run")
                .isFalse();
    }

    @Test
    void the_integration_suite_is_always_among_the_suites_a_run_resolves_against(@TempDir Path dir) {
        TestSelection integration = TestSelection.of(List.of("integration"), false, List.of(), List.of());

        TestSelection.Resolved resolved =
                SuiteChoice.resolve(integration, dir, false, TIERED.build().runSuites());

        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.suites()).containsExactly("integration");
    }

    @Test
    void a_selection_that_names_classes_or_tags_is_never_covered_by_the_guards_run(@TempDir Path dir) {
        assertThat(SuiteChoice.guardCovering(TestSelection.DEFAULT.withClasses(List.of("OrdersTest")), dir))
                .isNull();
        assertThat(SuiteChoice.guardCovering(TestSelection.of(List.of(), false, List.of("smoke"), List.of()), dir))
                .isNull();
    }
}
