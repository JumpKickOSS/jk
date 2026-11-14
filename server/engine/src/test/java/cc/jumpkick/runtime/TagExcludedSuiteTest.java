// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The results line of a run whose {@code -I} / {@code -E} filter left the selected suites nothing to run. */
class TagExcludedSuiteTest {

    private static final TestSummary NOTHING = new TestSummary(0, 0, 0, 0, 0, List.of(), Map.of(), 0);

    @Test
    void a_tag_both_included_and_excluded_selects_nothing_and_says_so() {
        TestSelection sel = TestSelection.of(List.of(), true, List.of("integration"), List.of("integration", "slow"));

        assertThat(TagExcludedSuite.line(sel, false, NOTHING))
                .isEqualTo("0 tests: --include-tags and --exclude-tags both name integration");
    }

    @Test
    void disjoint_filters_name_the_flags_that_excluded_every_test() {
        TestSelection sel = TestSelection.of(List.of(), true, List.of("nightly"), List.of("slow"));

        assertThat(TagExcludedSuite.line(sel, false, NOTHING))
                .isEqualTo("0 tests (all excluded by --include-tags nightly and --exclude-tags slow)");
    }
}
