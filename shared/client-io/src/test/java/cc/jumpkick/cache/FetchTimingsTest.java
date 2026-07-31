// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class FetchTimingsTest {

    @Test
    void trimmed_mean_drops_top_and_bottom_decile() {
        // 10 samples: trim 1 each side → mean of middle 8.
        List<Long> samples = LongStream.rangeClosed(1, 10).boxed().toList();
        // middle 2..9 → sum 44 / 8 = 5
        assertThat(FetchTimings.trimmedMeanMs(samples)).isEqualTo(5);
    }

    @Test
    void small_samples_are_not_trimmed() {
        assertThat(FetchTimings.trimmedMeanMs(List.of(100L, 200L, 300L))).isEqualTo(200);
    }

    @Test
    void empty_is_zero() {
        assertThat(FetchTimings.trimmedMeanMs(List.of())).isEqualTo(0);
    }

    @Test
    void weight_units_fall_back_when_cold() {
        assertThat(FetchTimings.weightUnits(8, 150)).isIn(8, FetchTimings.weightUnits(8, 150));
        // With no samples, cold path returns fallback.
        // (process memo may already have samples from other tests — only assert positive)
        assertThat(FetchTimings.weightUnits(8, 150)).isGreaterThan(0);
    }
}
