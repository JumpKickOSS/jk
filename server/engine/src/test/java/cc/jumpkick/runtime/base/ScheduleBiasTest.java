// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Learned schedule-contention bias: success-only, size-gated observations of
 * actual-wall / raw-simulated-schedule, EWMA-folded per project <em>and build shape</em> and
 * clamped on read.
 */
class ScheduleBiasTest {

    @TempDir
    Path home;

    @BeforeEach
    void isolateStore() throws Exception {
        System.setProperty("jk.env.JK_STATE_DIR", home.toString());
        // A fresh @TempDir per test is not enough on its own: the state root resolves once per
        // JVM, so whichever test in this worker ran first owns the directory every later one
        // reads. Sharded across 24 workers that is order-dependent — this class passed under one
        // harness and failed under the other until the store itself was cleared per test.
        Files.deleteIfExists(ScheduleBias.file());
    }

    /**
     * Builds finish together in one engine. Each observation is a read-fold-write of the whole
     * file, so without serialisation one build's row is overwritten by another's stale read.
     */
    @Test
    void concurrent_observations_all_land() throws Exception {
        int builds = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(builds);
        for (int i = 0; i < builds; i++) {
            Path proj = home.resolve("proj-" + i);
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    ScheduleBias.observe(proj, 90_000, 120_000, 29, 29, false, true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < builds; i++) {
            assertThat(ScheduleBias.current(home.resolve("proj-" + i), 29, 29, false, true))
                    .as("build %d's observation survived the others", i)
                    .isCloseTo(120_000 / 90_000.0, offset(1e-3)); // the store keeps four decimals
        }
    }

    /**
     * A bias below one is the simulator running hot; it is applied as learned, down to the fold
     * floor. Reading it back at 0.9 would leave the estimate wrong by the whole gap the store
     * already knows about.
     */
    @Test
    void a_learned_over_estimate_is_applied_as_learned() throws Exception {
        Path proj = home.resolve("proj");
        // Sim said 100s, the build took 60s.
        ScheduleBias.observe(proj, 100_000, 60_000, 29, 29, false, true);
        assertThat(ScheduleBias.current(proj, 29, 29, false, true)).isCloseTo(0.6, offset(1e-3));

        // A hand-edited store below the fold floor reads as the floor, not as written.
        Files.writeString(
                ScheduleBias.file(),
                "\"" + proj.toAbsolutePath().normalize() + "|w16|r16|i\" = 0.1000\n",
                StandardCharsets.UTF_8);
        assertThat(ScheduleBias.current(proj, 29, 29, false, true)).isEqualTo(ScheduleBias.MIN_BIAS);
    }

    @Test
    void unobserved_projects_use_neutral_bias() {
        assertThat(ScheduleBias.current(home.resolve("proj"), 12, 12, false, true))
                .isEqualTo(1.0);
    }

    @Test
    void observations_fold_toward_the_measured_ratio() {
        Path proj = home.resolve("proj");
        // Sim said 90s, reality 120s → ratio 1.333; first observation seeds the EWMA directly.
        ScheduleBias.observe(proj, 90_000, 120_000, 29, 29, false, true);
        assertThat(ScheduleBias.current(proj, 29, 29, false, true)).isCloseTo(120_000 / 90_000.0, offset(0.01));
        // A perfectly-priced follow-up pulls the bias back toward 1.0, a quarter of the way in log space.
        ScheduleBias.observe(proj, 100_000, 100_000, 29, 29, false, true);
        double expected = Math.exp((1 - ScheduleBias.ALPHA) * Math.log(120_000 / 90_000.0));
        assertThat(ScheduleBias.current(proj, 29, 29, false, true)).isCloseTo(expected, offset(0.01));
    }

    /** A ratio is a multiplier: running 2x hot and 2x cold pull the bias equally far from 1. */
    @Test
    void the_fold_is_symmetric_in_log_space() {
        double hot = ScheduleBias.fold(1.0, 0.5);
        double cold = ScheduleBias.fold(1.0, 2.0);
        assertThat(hot * cold).isCloseTo(1.0, offset(1e-9));
        assertThat(hot).isLessThan(1.0);
        assertThat(cold).isGreaterThan(1.0);
    }

    /**
     * A dependency-neutral cascade: predicted at 36.6 s over 13 dirty modules, done in 5.4 s
     * because the dependents were cache hits. A floor on the actual wall threw this observation
     * away, so the bucket never learned that its shape runs fast.
     */
    @Test
    void a_fast_wide_build_teaches_the_bias_down() {
        Path proj = home.resolve("proj");
        ScheduleBias.observe(proj, 36_601, 5_398, 13, 13, false, true);
        assertThat(ScheduleBias.current(proj, 13, 13, false, true))
                .as("the first observation seeds the w8 bucket at the clamped ratio")
                .isEqualTo(ScheduleBias.MIN_BIAS);

        Path other = home.resolve("other");
        ScheduleBias.observe(other, 40_000, 40_000, 13, 13, false, true);
        ScheduleBias.observe(other, 36_601, 5_398, 13, 13, false, true);
        assertThat(ScheduleBias.current(other, 13, 13, false, true))
                .as("a settled bucket moves down on a short actual")
                .isCloseTo(Math.exp(ScheduleBias.ALPHA * Math.log(ScheduleBias.MIN_BIAS)), offset(1e-3))
                .isLessThan(0.8);
    }

    /**
     * The reason the store is keyed by shape at all. A wide cascade and a one-file incremental want
     * different corrections on the same project — measured at ~1.7 and ~0.8 on the dogfood build —
     * and a single EWMA would hand each of them whichever ran last.
     */
    @Test
    void a_wide_cascade_does_not_teach_the_narrow_shape() {
        Path proj = home.resolve("proj");
        ScheduleBias.observe(proj, 40_000, 70_000, 13, 13, false, true); // wide: simulated cold
        ScheduleBias.observe(proj, 25_000, 21_000, 3, 3, false, true); // narrow: simulated hot

        assertThat(ScheduleBias.current(proj, 13, 13, false, true)).isCloseTo(70_000 / 40_000.0, offset(0.01));
        assertThat(ScheduleBias.current(proj, 3, 3, false, true)).isCloseTo(21_000 / 25_000.0, offset(0.01));
    }

    /** Counts round down to a power of two, so neighbouring widths share a history. */
    @Test
    void shapes_bucket_by_power_of_two() {
        assertThat(ScheduleBias.bucket(1)).isEqualTo(1);
        assertThat(ScheduleBias.bucket(3)).isEqualTo(2);
        assertThat(ScheduleBias.bucket(12)).isEqualTo(8);
        assertThat(ScheduleBias.bucket(13)).isEqualTo(8);
        assertThat(ScheduleBias.bucket(31)).isEqualTo(16);
        assertThat(ScheduleBias.bucket(0))
                .as("degenerate counts still land in a bucket")
                .isEqualTo(1);
    }

    /** An unseen shape is better served by the project's other history than by 1.0. */
    @Test
    void a_cascade_of_rechecks_and_a_cold_build_of_the_same_width_learn_apart() {
        Path proj = home.resolve("proj");
        // 223 modules, all compiling: the schedule simulated hot.
        ScheduleBias.observe(proj, 16_000, 13_000, 223, 223, false, true);
        // 212 modules, one compiling and 211 cache-hit rechecks: simulated cold.
        ScheduleBias.observe(proj, 3_200, 2_000, 212, 1, false, true);
        assertThat(ScheduleBias.current(proj, 223, 223, false, true)).isCloseTo(13_000 / 16_000.0, offset(0.01));
        assertThat(ScheduleBias.current(proj, 212, 1, false, true)).isCloseTo(2_000 / 3_200.0, offset(0.01));
        assertThat(ScheduleBias.current(proj, 212, 40, false, true))
                .as("an unseen shape is the model as simulated, not a neighbour's correction")
                .isEqualTo(1.0);
    }

    @Test
    void trivial_schedules_never_teach_the_bias() {
        Path proj = home.resolve("proj");
        ScheduleBias.observe(proj, 200, 200_000, 29, 29, false, true);
        ScheduleBias.observe(proj, 249, 300, 29, 29, false, true);
        ScheduleBias.observe(proj, 90_000, 0, 29, 29, false, true);
        assertThat(ScheduleBias.current(proj, 29, 29, false, true)).isEqualTo(1.0);
    }

    @Test
    void read_clamp_bounds_a_poisoned_store() {
        Path proj = home.resolve("proj");
        // One absurd observation (clamped to MAX_BIAS at fold and again on read).
        ScheduleBias.observe(proj, 10_000, 500_000, 29, 29, false, true);
        assertThat(ScheduleBias.current(proj, 29, 29, false, true)).isEqualTo(ScheduleBias.MAX_BIAS);
    }

    @Test
    void the_close_out_is_learned_as_a_fixed_cost_beside_the_bias() {
        Path proj = home.resolve("proj");
        assertThat(ScheduleBias.tailMs(proj)).as("unmeasured").isZero();
        ScheduleBias.observeTail(proj, 240);
        assertThat(ScheduleBias.tailMs(proj)).isEqualTo(240);
        ScheduleBias.observeTail(proj, 200);
        assertThat(ScheduleBias.tailMs(proj)).isEqualTo(230); // 240 + 0.25 * (200 - 240)
        ScheduleBias.observeTail(proj, 3_600_000);
        assertThat(ScheduleBias.tailMs(proj))
                .as("a stall is clamped, not learned whole")
                .isLessThan(3_000);
        assertThat(ScheduleBias.current(proj, 1, 1, false, true))
                .as("the tail is not a shape's bias")
                .isEqualTo(1.0);
    }
}
