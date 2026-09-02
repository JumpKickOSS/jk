// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Learned schedule-contention bias: success-only, size-gated observations of
 * actual-wall / raw-simulated-schedule, EWMA-folded per project and clamped on read.
 */
class ScheduleBiasTest {

    @TempDir
    Path home;

    private String prevBuilds;

    @BeforeEach
    void isolateStore() {
        prevBuilds = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", home.toString());
    }

    @AfterEach
    void restore() {
        if (prevBuilds == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevBuilds);
    }

    @Test
    void unobserved_projects_use_neutral_bias() {
        assertThat(ScheduleBias.current(home.resolve("proj"))).isEqualTo(1.0);
    }

    @Test
    void observations_fold_toward_the_measured_ratio() {
        Path proj = home.resolve("proj");
        // Sim said 90s, reality 120s → ratio 1.333; first observation seeds the EWMA directly.
        ScheduleBias.observe(proj, 90_000, 120_000, 29);
        assertThat(ScheduleBias.current(proj)).isCloseTo(120_000 / 90_000.0, org.assertj.core.data.Offset.offset(0.01));
        // A perfectly-priced follow-up pulls the bias back toward 1.0 (alpha 0.4).
        ScheduleBias.observe(proj, 100_000, 100_000, 29);
        double expected = (120_000 / 90_000.0) + ScheduleBias.ALPHA * (1.0 - (120_000 / 90_000.0));
        assertThat(ScheduleBias.current(proj)).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void small_or_trivial_builds_never_teach_the_bias() {
        Path proj = home.resolve("proj");
        ScheduleBias.observe(proj, 90_000, 200_000, 2); // too few modules
        ScheduleBias.observe(proj, 1_000, 200_000, 29); // sim below threshold
        ScheduleBias.observe(proj, 90_000, 4_000, 29); // wall below threshold
        assertThat(ScheduleBias.current(proj)).isEqualTo(1.0);
    }

    @Test
    void read_clamp_bounds_a_poisoned_store() {
        Path proj = home.resolve("proj");
        // One absurd observation (ratio clamps to 2.5 at fold; read clamps to 2.0).
        ScheduleBias.observe(proj, 10_000, 500_000, 29);
        assertThat(ScheduleBias.current(proj)).isEqualTo(ScheduleBias.MAX_BIAS);
    }
}
