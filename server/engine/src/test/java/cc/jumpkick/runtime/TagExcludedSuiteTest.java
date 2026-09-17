// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The results line of a tier the module has no tests in, and the hint when the profile's own tag is what excluded them. */
class TagExcludedSuiteTest {

    private static final TestSummary NOTHING = new TestSummary(0, 0, 0, 0, 0, List.of(), Map.of(), 0);

    @Test
    void a_profile_that_includes_a_tag_its_exclude_list_still_drops_says_how_to_clear_it() {
        TestSelection sel = TestSelection.of(List.of(), true, List.of("integration"), List.of("integration", "slow"));

        assertThat(TagExcludedSuite.line(sel, "integration", false, NOTHING))
                .isEqualTo("0 tests (all excluded by profile integration — it includes integration, which its"
                        + " exclude-tags still drop, so a @Tag(\"integration\") method inside an untagged class is"
                        + " not selected; [profiles.integration] exclude-tags = [] clears them)");
    }

    @Test
    void a_profile_whose_include_and_exclude_lists_are_disjoint_keeps_the_plain_line() {
        TestSelection sel = TestSelection.of(List.of(), true, List.of("nightly"), List.of("slow"));

        assertThat(TagExcludedSuite.line(sel, "nightly", false, NOTHING))
                .isEqualTo("0 tests (all excluded by profile nightly)");
        assertThat(TagExcludedSuite.line(sel, null, false, NOTHING))
                .isEqualTo("0 tests (all excluded by" + " include-tags nightly and exclude-tags slow)");
    }
}
