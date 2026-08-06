// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestEffortTest {

    @Test
    void class_walls_skip_method_product() {
        Map<String, Long> walls = Map.of("com.ex.A", 1000L, "com.ex.B", 2000L);
        long ms = TestEffort.wallMillis(
                "/m", walls, List.of("com.ex.A", "com.ex.B"), /*methodCount*/ 9999, null, List.of(), null, 1);
        // startup + 3000; method count must not be used
        assertThat(ms).isGreaterThanOrEqualTo(3000);
        assertThat(ms).isLessThan(3000 + 9999 * 40L); // would be huge if methods used
    }

    @Test
    void incomplete_class_walls_uses_method_count() {
        Map<String, Long> walls = Map.of("com.ex.A", 1000L); // B missing
        long withMethods = TestEffort.wallMillis(
                "/m", walls, List.of("com.ex.A", "com.ex.B"), 10, null, List.of(), null, 1);
        long noMethods = TestEffort.wallMillis(
                "/m", walls, List.of("com.ex.A", "com.ex.B"), 0, null, List.of(), null, 1);
        assertThat(withMethods).isGreaterThan(noMethods);
    }

    @Test
    void empty_selection_does_not_require_method_count() {
        long ms = TestEffort.wallMillis("/m", Map.of(), List.of(), 0, null, List.of(), null, 1);
        assertThat(ms).isPositive();
    }

    /**
     * Regression (JK-1587): the documented specificity ladder is module residual → project
     * median → host absolute. Sibling-module rates must win over a host-wide average that may
     * have been trained by unrelated projects.
     */
    @Test
    void project_median_beats_host_absolute(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        StepTimings.record(
                tmp,
                List.of(
                        // Sibling modules trained a run-tests rate (units are residual weight;
                        // methodMs converts × MS_PER_WEIGHT).
                        new StepTimings.Sample("/ws/a", "run-tests", 2.0),
                        new StepTimings.Sample("/ws/b", "run-tests", 4.0),
                        // Host absolute prior says something very different.
                        new StepTimings.Sample(StepTimings.HOST_METHOD_MS_DIR, "test-method-ms", 999.0)),
                1.0,
                System.currentTimeMillis());
        StepTimings timings = StepTimings.load(tmp);
        assertThat(timings.hostAvgTestMethodMs()).isPresent();

        double ms = TestEffort.methodMs("/ws/untrained", timings, List.of("/ws/a", "/ws/b"));
        // Median(2.0, 4.0) = 3.0 × MS_PER_WEIGHT — not the 999 host absolute.
        assertThat(ms).isEqualTo(3.0 * EffortWeights.MS_PER_WEIGHT);
    }
}
