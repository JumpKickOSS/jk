// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Before the estimate arrives, cached modules finishing in the first second do not run the bar
 * ahead: their weight slices are not the build's time, and the peak hold would pin the bar there.
 */
class WorkspaceProgressTrackerPreEstimateTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
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

    private WorkspaceProgressTracker tracker() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker(ProgressBarMode.AUTO, ticker);
        t.preflight("sync", 1, 1);
        t.calibrate(1_000, 3);
        return t;
    }

    @Test
    void cached_modules_finishing_before_the_estimate_hold_the_bar_at_a_small_share() {
        WorkspaceProgressTracker t = tracker();
        t.moduleProgress("a", 400, 400, 400);
        t.moduleComplete("a", 400);
        t.moduleProgress("b", 400, 400, 400);
        t.moduleComplete("b", 400);

        long pf = WorkspaceProgressTracker.PREFLIGHT_UNITS;
        double cap = 100.0 * (pf + 1_000 * WorkspaceProgressTracker.PRE_ESTIMATE_SHARE) / (pf + 1_000);
        assertThat(t.snapshot().percent())
                .as("preflight done, execute held at its small share")
                .isLessThanOrEqualTo(cap + 0.5);
    }

    @Test
    void an_estimate_that_never_comes_lets_the_weights_through_after_a_moment() {
        WorkspaceProgressTracker t = tracker();
        t.moduleProgress("a", 400, 400, 400);
        t.moduleComplete("a", 400);
        nanos.addAndGet((WorkspaceProgressTracker.PRE_ESTIMATE_HOLD_MS + 1_000) * 1_000_000L);
        t.moduleProgress("b", 400, 400, 400);
        t.moduleComplete("b", 400);

        assertThat(t.snapshot().percent()).isGreaterThan(50.0);
    }
}
