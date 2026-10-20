// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The warm pool waits as long as the builds' own cadence, within the floor and the ceiling. */
class LingerWindowTest {

    private long now;

    @Test
    void with_no_loop_seen_the_window_is_the_floor() {
        assertThat(new LingerWindow().millis()).isEqualTo(LingerWindow.FLOOR_MS);
    }

    @Test
    void builds_two_minutes_apart_keep_the_worker_four() {
        LingerWindow w = new LingerWindow();
        for (long job = 1; job <= 5; job++) build(w, job, 120);

        assertThat(w.millis()).isEqualTo(TimeUnit.SECONDS.toMillis(240));
    }

    @Test
    void an_agent_rebuilding_every_few_seconds_keeps_the_floor() {
        LingerWindow w = new LingerWindow();
        for (long job = 1; job <= 8; job++) build(w, job, 4);

        assertThat(w.millis()).isEqualTo(LingerWindow.FLOOR_MS);
    }

    @Test
    void the_compiles_of_one_build_are_not_gaps() {
        LingerWindow w = new LingerWindow();
        build(w, 1, 0);
        build(w, 2, 150);
        build(w, 3, 150);
        // Job 3 compiles four more modules, one after another, a few ms apart.
        for (int i = 0; i < 4; i++) {
            now += TimeUnit.MILLISECONDS.toNanos(5);
            w.arrived(3, now);
            now += TimeUnit.MILLISECONDS.toNanos(40);
            w.settled(now);
        }

        assertThat(w.millis()).isEqualTo(TimeUnit.SECONDS.toMillis(300));
    }

    @Test
    void the_window_never_passes_the_ceiling() {
        LingerWindow w = new LingerWindow();
        for (long job = 1; job <= 4; job++) build(w, job, 540);

        assertThat(w.millis()).isEqualTo(LingerWindow.CEILING_MS);
    }

    @Test
    void a_gap_past_the_ceiling_forgets_the_loop() {
        LingerWindow w = new LingerWindow();
        for (long job = 1; job <= 4; job++) build(w, job, 200);
        assertThat(w.millis()).isGreaterThan(LingerWindow.FLOOR_MS);

        build(w, 5, TimeUnit.HOURS.toSeconds(2));

        assertThat(w.millis()).isEqualTo(LingerWindow.FLOOR_MS);
    }

    @Test
    void the_slow_quartile_decides_so_one_quick_rebuild_does_not_shrink_it() {
        LingerWindow w = new LingerWindow();
        build(w, 1, 0);
        for (long job = 2; job <= 6; job++) build(w, job, 150);
        build(w, 7, 2);

        assertThat(w.millis()).isEqualTo(TimeUnit.SECONDS.toMillis(300));
    }

    /** One single-compile build of {@code job}, {@code gapSeconds} after the last one settled. */
    private void build(LingerWindow w, long job, long gapSeconds) {
        now += TimeUnit.SECONDS.toNanos(gapSeconds);
        w.arrived(job, now);
        now += TimeUnit.MILLISECONDS.toNanos(80);
        w.settled(now);
    }
}
