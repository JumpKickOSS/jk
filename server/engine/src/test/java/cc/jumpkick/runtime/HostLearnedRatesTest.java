// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostLearnedRatesTest {

    @Test
    void trimmed_mean_drops_extremes_when_enough_samples() {
        List<Double> s = new ArrayList<>();
        for (int i = 1; i <= 10; i++) s.add((double) i); // 1..10
        // trim 1 each side → 2..9 average = 5.5
        assertThat(HostLearnedRates.trimmedMean(s)).isCloseTo(5.5, within(1e-9));
    }

    @Test
    void trimmed_mean_is_plain_average_for_small_n() {
        assertThat(HostLearnedRates.trimmedMean(List.of(10.0, 20.0, 30.0))).isCloseTo(20.0, within(1e-9));
    }

    @Test
    void with_sample_caps_ring_and_ignores_outliers() {
        HostLearnedRates r = new HostLearnedRates();
        r = r.withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 40, 1_000);
        r = r.withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 50, 1_000);
        r = r.withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 999_999, 1_000); // dropped
        assertThat(r.sampleCount(HostLearnedRates.RUN_TESTS_PER_METHOD_MS)).isEqualTo(2);
        assertThat(r.meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS)).hasValueCloseTo(45.0, within(1e-9));
    }
}
