// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The watchdog's stall arm: a note in the log for a silent live job, never a cancel. The passes are
 * driven here against a clock the test advances, so a loaded host cannot turn a tick into a timeout.
 */
class JobWatchdogStallTest {

    private static final JobLimits NO_HEARTBEAT_NO_DEADLINE = new JobLimits(0L, 0L, 0L, 0L, 0L, 0L);

    private static final WallDeadline UNBOUNDED = new WallDeadline(0L, "x");

    @Test
    void a_live_job_silent_past_the_bound_is_named_in_the_log_and_again_after_the_next_silence() {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicLong lastEvent = new AtomicLong(0L);
        List<String> logs = new CopyOnWriteArrayList<>();
        JobWatchdog watchdog = new JobWatchdog(
                NO_HEARTBEAT_NO_DEADLINE, now::get, id -> null, id -> lastEvent.get(), logs::add, 5_000L);
        Session.CancelToken token = Session.CancelToken.live();
        JobWatchdog.Watch watch = Objects.requireNonNull(
                watchdog.watch(7L, "test", "/tmp/app", token, new AtomicReference<>(), null, UNBOUNDED, 1_000L),
                "the stall arm alone keeps the watchdog alive");
        assertThat(watch.nextWait())
                .as("a stall-only watch ticks at half the silence")
                .isEqualTo(2_500L);

        now.set(1_000L + 5_000L);
        watch.pass();
        assertThat(logs)
                .containsExactly("jk engine: job 7 (test /tmp/app) has emitted no task event for 5s; 0 worker processes"
                        + " alive, the job is still live and holds its memory share — `jk cancel 7` stops it");
        assertThat(token.cancelled()).as("a note, not a kill").isFalse();

        // A task event resets the silence: three seconds later nothing new is said.
        lastEvent.set(now.get());
        now.addAndGet(3_000L);
        watch.pass();
        assertThat(logs).hasSize(1);

        // Five seconds after that event, the second note.
        now.addAndGet(2_000L);
        watch.pass();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1)).contains("no task event for 5s");
        assertThat(token.cancelled()).isFalse();
    }

    @Test
    void a_job_that_keeps_emitting_events_is_never_noted() {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicLong lastEvent = new AtomicLong(1_000L);
        List<String> logs = new CopyOnWriteArrayList<>();
        JobWatchdog watchdog = new JobWatchdog(
                NO_HEARTBEAT_NO_DEADLINE, now::get, id -> null, id -> lastEvent.get(), logs::add, 2_000L);
        JobWatchdog.Watch watch = Objects.requireNonNull(watchdog.watch(
                8L, "build", "/tmp/lib", Session.CancelToken.live(), new AtomicReference<>(), null, UNBOUNDED, 1_000L));
        for (int i = 0; i < 4; i++) {
            now.addAndGet(1_500L);
            lastEvent.set(now.get());
            watch.pass();
        }
        assertThat(logs).isEmpty();
    }

    @Test
    void the_watch_thread_ends_with_the_job() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        JobWatchdog watchdog =
                new JobWatchdog(NO_HEARTBEAT_NO_DEADLINE, () -> 1_000L, id -> null, id -> 1_000L, logs::add, 5_000L);
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = Objects.requireNonNull(watchdog.start(
                9L,
                "test",
                "/tmp/app",
                Session.CancelToken.live(),
                new AtomicReference<>(),
                done,
                null,
                UNBOUNDED,
                1_000L));
        done.countDown();
        thread.join(Duration.ofMinutes(1));
        assertThat(thread.isAlive()).as("the loop ends on the job's latch").isFalse();
        assertThat(logs).isEmpty();
    }
}
