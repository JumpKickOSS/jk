// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** A build on hold for memory (JK-VYJ4X): the tracker's clock stands still and resumes where it was. */
class WorkspaceProgressTrackerHoldTest {

    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private final Clock ticker = new Clock() {
        @Override
        public long millis() {
            return nanos.get() / 1_000_000L;
        }

        @Override
        public long nanos() {
            return nanos.get();
        }
    };

    private void advance(long ms) {
        nanos.addAndGet(ms * 1_000_000L);
    }

    @Test
    void the_bar_and_the_countdown_stand_still_on_hold_and_carry_on_from_there_after() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker(null, ticker);
        t.seedWall(100_000, 3);
        t.noteRemaining(100_000, 100_000);
        advance(20_000);
        var before = t.snapshot();

        t.hold();
        advance(60_000);
        var held = t.snapshot();
        assertThat(t.onHold()).isTrue();
        assertThat(held.percent()).isEqualTo(before.percent());

        t.resume();
        assertThat(t.onHold()).isFalse();
        assertThat(t.snapshot().percent()).isEqualTo(before.percent());
        advance(10_000);
        assertThat(t.snapshot().percent()).isGreaterThan(before.percent());
    }

    @Test
    void the_likely_range_rides_the_residual_as_gaps_below_and_above_it() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker(null, ticker);
        t.seedWall(100_000, 3);
        t.noteRemaining(80_000, 100_000);
        t.noteRange(80_000, 60_000, 110_000);

        var snap = t.snapshot();
        assertThat(snap.hasRange()).isTrue();
        assertThat(snap.lowMs()).isEqualTo(60_000);
        assertThat(snap.highMs()).isEqualTo(110_000);

        t.noteRemaining(50_000, 100_000);
        var later = t.snapshot();
        assertThat(later.lowMs()).isEqualTo(30_000);
        assertThat(later.highMs()).isEqualTo(80_000);
    }
}
