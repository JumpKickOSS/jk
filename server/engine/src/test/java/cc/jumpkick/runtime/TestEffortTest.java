// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.StepTimings;
import java.nio.file.Path;
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
        long withMethods =
                TestEffort.wallMillis("/m", walls, List.of("com.ex.A", "com.ex.B"), 10, null, List.of(), null, 1);
        long noMethods =
                TestEffort.wallMillis("/m", walls, List.of("com.ex.A", "com.ex.B"), 0, null, List.of(), null, 1);
        assertThat(withMethods).isGreaterThan(noMethods);
    }

    @Test
    void empty_selection_does_not_require_method_count() {
        long ms = TestEffort.wallMillis("/m", Map.of(), List.of(), 0, null, List.of(), null, 1);
        assertThat(ms).isPositive();
    }

    /**
     * The cold arm of the ladder — no suite wall, no class walls, no learned rate — is methods x
     * the calibration method-ms, divided across runners, plus one startup. Pinned against the
     * baseline constant with a 2x band for host scaling, so the multiplier cannot drift by an
     * order of magnitude again without a test going red (a 19 s build was once priced in minutes).
     */
    @Test
    void cold_ladder_is_methods_times_calibration_method_ms_over_runners() {
        int methods = 1000;
        long startup = TestEffort.suiteStartupMs();
        long serial = TestEffort.wallMillis("/cold", Map.of(), List.of(), methods, null, List.of(), null, 1);
        double perMethod = (serial - startup) / (double) methods;
        assertThat(perMethod)
                .as("effective cold ms/method vs the calibration baseline")
                .isBetween(Calibration.BASELINE_METHOD_MS / 2.0, Calibration.BASELINE_METHOD_MS * 2.0);

        long sharded = TestEffort.wallMillis("/cold", Map.of(), List.of(), methods, null, List.of(), null, 4);
        assertThat(sharded - startup)
                .as("runners divide the cold body")
                .isBetween((serial - startup) / 5, (serial - startup) / 3);
    }

    /**
     * Regression: the documented specificity ladder is module residual → project
     * median → host absolute. Sibling-module rates must win over a host-wide average that may
     * have been trained by unrelated projects.
     */
    @Test
    void project_median_beats_host_absolute(@org.junit.jupiter.api.io.TempDir Path tmp) {
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
