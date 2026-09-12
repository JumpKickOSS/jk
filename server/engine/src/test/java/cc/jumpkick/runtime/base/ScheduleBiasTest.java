// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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

    private String prevBuilds;

    @BeforeEach
    void isolateStore() throws Exception {
        prevBuilds = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", home.toString());
        // A fresh @TempDir per test is not enough on its own: the state root resolves once per
        // JVM, so whichever test in this worker ran first owns the directory every later one
        // reads. Sharded across 24 workers that is order-dependent — this class passed under one
        // harness and failed under the other until the store itself was cleared per test.
        Files.deleteIfExists(ScheduleBias.file());
    }

    @AfterEach
    void restore() {
        if (prevBuilds == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevBuilds);
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
                    ScheduleBias.observe(proj, 90_000, 120_000, 29);
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
            assertThat(ScheduleBias.current(home.resolve("proj-" + i), 29))
                    .as("build %d's observation survived the others", i)
                    .isCloseTo(120_000 / 90_000.0, offset(1e-3)); // the store keeps four decimals
        }
    }

    @Test
    void unobserved_projects_use_neutral_bias() {
        assertThat(ScheduleBias.current(home.resolve("proj"), 12)).isEqualTo(1.0);
    }

    @Test
    void observations_fold_toward_the_measured_ratio() {
        Path proj = home.resolve("proj");
        // Sim said 90s, reality 120s → ratio 1.333; first observation seeds the EWMA directly.
        ScheduleBias.observe(proj, 90_000, 120_000, 29);
        assertThat(ScheduleBias.current(proj, 29)).isCloseTo(120_000 / 90_000.0, offset(0.01));
        // A perfectly-priced follow-up pulls the bias back toward 1.0 (alpha 0.4).
        ScheduleBias.observe(proj, 100_000, 100_000, 29);
        double expected = (120_000 / 90_000.0) + ScheduleBias.ALPHA * (1.0 - (120_000 / 90_000.0));
        assertThat(ScheduleBias.current(proj, 29)).isCloseTo(expected, offset(0.01));
    }

    /**
     * The reason the store is keyed by shape at all. A wide cascade and a one-file incremental want
     * different corrections on the same project — measured at ~1.7 and ~0.8 on the dogfood build —
     * and a single EWMA would hand each of them whichever ran last.
     */
    @Test
    void a_wide_cascade_does_not_teach_the_narrow_shape() {
        Path proj = home.resolve("proj");
        ScheduleBias.observe(proj, 40_000, 70_000, 13); // wide: simulated cold
        ScheduleBias.observe(proj, 25_000, 21_000, 3); // narrow: simulated hot

        assertThat(ScheduleBias.current(proj, 13)).isCloseTo(70_000 / 40_000.0, offset(0.01));
        assertThat(ScheduleBias.current(proj, 3)).isCloseTo(ScheduleBias.MIN_BIAS, offset(0.01));
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
    void an_unseen_shape_falls_back_to_the_project_wide_entry() throws Exception {
        Path proj = home.resolve("proj");
        Path store = ScheduleBias.file();
        Files.createDirectories(store.getParent());
        Files.writeString(store, "\"" + proj.toAbsolutePath().normalize() + "\" = 1.5000\n");

        assertThat(ScheduleBias.current(proj, 7)).as("no w4 bucket yet").isCloseTo(1.5, offset(0.01));
        // A shape's own observation seeds its bucket outright rather than folding into the
        // project-wide number, so the fallback stops applying the moment real evidence exists.
        ScheduleBias.observe(proj, 40_000, 40_000, 7);
        assertThat(ScheduleBias.current(proj, 7))
                .as("once the bucket exists it wins")
                .isCloseTo(1.0, offset(0.01));
    }

    @Test
    void trivial_builds_never_teach_the_bias() {
        Path proj = home.resolve("proj");
        ScheduleBias.observe(proj, 1_000, 200_000, 29); // sim below threshold
        ScheduleBias.observe(proj, 90_000, 4_000, 29); // wall below threshold
        assertThat(ScheduleBias.current(proj, 29)).isEqualTo(1.0);
    }

    @Test
    void read_clamp_bounds_a_poisoned_store() {
        Path proj = home.resolve("proj");
        // One absurd observation (clamped to MAX_BIAS at fold and again on read).
        ScheduleBias.observe(proj, 10_000, 500_000, 29);
        assertThat(ScheduleBias.current(proj, 29)).isEqualTo(ScheduleBias.MAX_BIAS);
    }
}
