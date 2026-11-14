// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Suspensions;
import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The suite inactivity watchdog measures a worker's silence from its last line or from the engine's
 * last resume, whichever is later: a suite cannot print while the machine sleeps.
 */
class IdleWatchdogSuspendTest {

    private static final long TIMEOUT = 120_000;

    private final FakeClock clock = new FakeClock();
    private final AtomicLong boot = new AtomicLong(9_000_000);
    private final Suspensions watch = Suspensions.forTests(clock, boot::get);

    /** {@code seconds} of the engine running, the heartbeat looking once a second. */
    private void live(int seconds) {
        for (int i = 0; i < seconds; i++) {
            clock.advance(Duration.ofSeconds(1));
            boot.addAndGet(1_000);
            watch.poll();
        }
    }

    @Test
    void a_sleep_longer_than_the_idle_timeout_restarts_the_window_instead_of_killing_the_worker() {
        watch.jobStarted(1);
        long cursor = watch.cursor();
        long lastLine = clock.millis();
        live(30);
        assertThat(PluginProcess.idleMillis(clock.millis(), lastLine, watch, cursor))
                .isEqualTo(30_000);

        // Asleep 40 minutes; the watchdog's first look after the wake.
        clock.set(clock.instant().plus(Duration.ofMinutes(40)));
        boot.addAndGet(Duration.ofMinutes(40).toMillis());
        clock.advance(Duration.ofSeconds(1));
        boot.addAndGet(1_000);
        long idle = PluginProcess.idleMillis(clock.millis(), lastLine, watch, cursor);
        assertThat(idle)
                .as("the window restarted at the resume")
                .isLessThan(TIMEOUT)
                .isZero();

        live(121);
        assertThat(PluginProcess.idleMillis(clock.millis(), lastLine, watch, cursor))
                .as("a real silence after the wake still fires")
                .isGreaterThanOrEqualTo(TIMEOUT);
    }

    @Test
    void with_no_suspend_the_silence_runs_from_the_last_line() {
        watch.jobStarted(1);
        long cursor = watch.cursor();
        long lastLine = clock.millis();
        live(130);
        assertThat(PluginProcess.idleMillis(clock.millis(), lastLine, watch, cursor))
                .isGreaterThanOrEqualTo(TIMEOUT);
    }
}
