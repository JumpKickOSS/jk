// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestFailureInfoTest {

    @Test
    void strip_label_round_trips_the_writer() {
        assertThat(TestFailureInfo.stripLabel("g:a", TestFailureInfo.label("g:a", "running 80 tests", 0)))
                .isEqualTo("running 80 tests");
        // A display string that itself contains the separator survives the module-aware strip.
        assertThat(TestFailureInfo.stripLabel("g:a", TestFailureInfo.label("g:a", "left :: right", 0)))
                .isEqualTo("left :: right");
        // The worker suffix is the label's, not the module's — the strip leaves it alone.
        assertThat(TestFailureInfo.stripLabel("g:a", TestFailureInfo.label("g:a", "FooTest.bar()", 2)))
                .isEqualTo("FooTest.bar()  [w2]");
    }

    @Test
    void module_aware_strip_skips_a_drifted_module() {
        assertThat(TestFailureInfo.stripLabel("g:a", "g:other :: detail")).isEqualTo("g:other :: detail");
        assertThat(TestFailureInfo.stripLabel("", "g:a :: detail")).isEqualTo("g:a :: detail");
    }

    @Test
    void null_module_strips_any_leading_segment() {
        assertThat(TestFailureInfo.stripLabel(null, "g:whatever :: running 80 tests"))
                .isEqualTo("running 80 tests");
        // Only the leading segment goes; a second separator stays.
        assertThat(TestFailureInfo.stripLabel(null, "g:a :: left :: right")).isEqualTo("left :: right");
        // No separator, a leading separator, or a blank label pass through unchanged/empty.
        assertThat(TestFailureInfo.stripLabel(null, "no prefix here")).isEqualTo("no prefix here");
        assertThat(TestFailureInfo.stripLabel(null, " :: leading")).isEqualTo(":: leading");
        assertThat(TestFailureInfo.stripLabel(null, "  ")).isEmpty();
        assertThat(TestFailureInfo.stripLabel("g:a", null)).isEmpty();
    }
}
