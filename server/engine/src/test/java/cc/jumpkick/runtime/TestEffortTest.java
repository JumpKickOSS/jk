// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.runtime.base.StepTimings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestEffortTest {

    @TempDir
    Path hostStateDir;

    private String prevStateDir;

    /**
     * Pin state + builds, the same way {@link EffortWeightsStepEtaTest} and {@link NativeEffortTest}
     * do and for the same reason: every cold rung here ends at {@code Calibration.load()}, so without
     * this the assertions read whatever {@code host-metrics.toml} the machine has and change meaning
     * the moment it is calibrated. This class was the one of the three without it, and it went red at
     * 481 ms/method against a 22.5–90 band after a fixture build of a one-method suite trained a
     * host-wide rate into the shared test home.
     *
     * <p>The cold-ladder test also supplies its calibration outright, which is the stronger statement
     * — it names the rung it prices against instead of arranging for the file to be empty. This is
     * what covers the assertions that only read the startup rung.
     */
    @BeforeEach
    void isolateHostState() {
        prevStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", hostStateDir.toString());
        Calibration.invalidateMemo();
    }

    @AfterEach
    void restoreHostState() {
        if (prevStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevStateDir);
        Calibration.invalidateMemo();
    }

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
     *
     * <p>The calibration is supplied, not read. Loaded from {@code JK_HOME} this asserted against
     * whatever rate the machine had learned: a fixture build of a one-method suite is nearly all
     * JVM fork, so it trains a host-wide ~500 ms/method that another tier's run then reads, and the
     * band is 22.5–90. {@code absentForTest} is the state the ladder's last rung is for — nothing
     * measured here yet.
     */
    @Test
    void cold_ladder_is_methods_times_calibration_method_ms_over_runners() {
        int methods = 1000;
        Calibration cold = Calibration.absentForTest();
        long startup = TestEffort.suiteStartupMs(cold);
        long serial = TestEffort.wallMillis("/cold", Map.of(), List.of(), methods, null, List.of(), null, 1, cold);
        double perMethod = (serial - startup) / (double) methods;
        assertThat(perMethod)
                .as("effective cold ms/method vs the calibration baseline")
                .isBetween(Calibration.BASELINE_METHOD_MS / 2.0, Calibration.BASELINE_METHOD_MS * 2.0);

        long sharded = TestEffort.wallMillis("/cold", Map.of(), List.of(), methods, null, List.of(), null, 4, cold);
        assertThat(sharded - startup)
                .as("runners divide the cold body")
                .isBetween((serial - startup) / 5, (serial - startup) / 3);
    }

    /**
     * And the rung is reachable past a learned rate: a host that has measured something uses it, which
     * is what makes the supplied-calibration seam load-bearing rather than cosmetic.
     */
    @Test
    void a_learned_host_rate_outranks_the_baseline() {
        Calibration learned = Calibration.testInstance(
                12.0,
                true,
                JkVersion.VERSION,
                System.currentTimeMillis(),
                new HostLearnedRates().withSample(HostLearnedRates.RUN_TESTS_PER_METHOD_MS, 500, 0),
                200,
                15,
                20);
        assertThat(TestEffort.methodMs("/cold", null, List.of(), learned))
                .as("the machine's measured rate, not the 45 ms baseline")
                .isEqualTo(500.0);
        assertThat(TestEffort.methodMs("/cold", null, List.of(), Calibration.absentForTest()))
                .as("and the baseline when it has measured nothing")
                .isBetween(Calibration.BASELINE_METHOD_MS / 2.0, Calibration.BASELINE_METHOD_MS * 2.0);
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
