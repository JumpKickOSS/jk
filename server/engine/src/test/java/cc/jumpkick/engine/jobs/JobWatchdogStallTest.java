// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.testing.Await;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The watchdog's stall arm: a note in the log for a silent live job, never a cancel. */
class JobWatchdogStallTest {

    private static final JobLimits NO_HEARTBEAT_NO_DEADLINE = new JobLimits(0L, 0L, 0L, 0L, 0L, 0L);

    @Test
    void a_live_job_silent_past_the_bound_is_named_in_the_log_and_again_after_the_next_silence() throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicLong lastEvent = new AtomicLong(0L);
        List<String> logs = new CopyOnWriteArrayList<>();
        JobWatchdog watchdog = new JobWatchdog(
                NO_HEARTBEAT_NO_DEADLINE, now::get, id -> null, id -> lastEvent.get(), logs::add, 5_000L);
        Session.CancelToken token = Session.CancelToken.live();
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = Objects.requireNonNull(
                watchdog.start(
                        7L,
                        "test",
                        "/tmp/app",
                        token,
                        new AtomicReference<>(),
                        done,
                        null,
                        new WallDeadline(0L, "x"),
                        1_000L),
                "the stall arm alone keeps the watchdog alive");
        try {
            now.set(1_000L + 5_000L);
            Await.until(Duration.ofSeconds(10), () -> !logs.isEmpty());
            assertThat(logs.get(0))
                    .isEqualTo("jk engine: job 7 (test /tmp/app) has emitted no task event for 5s; 0 worker processes"
                            + " alive, the job is still live and holds its memory share — `jk cancel 7` stops it");
            assertThat(token.cancelled()).as("a note, not a kill").isFalse();

            // A task event resets the silence: three more seconds later nothing new is said.
            lastEvent.set(now.get());
            now.addAndGet(3_000L);
            Thread.sleep(3_000L);
            assertThat(logs).hasSize(1);

            // Five seconds after that event, the second note.
            now.addAndGet(2_000L);
            Await.until(Duration.ofSeconds(10), () -> logs.size() == 2);
            assertThat(logs.get(1)).contains("no task event for 5s");
        } finally {
            done.countDown();
            thread.join(Duration.ofSeconds(5));
        }
        assertThat(token.cancelled()).isFalse();
    }

    @Test
    void a_job_that_keeps_emitting_events_is_never_noted() throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        AtomicLong lastEvent = new AtomicLong(1_000L);
        List<String> logs = new CopyOnWriteArrayList<>();
        JobWatchdog watchdog = new JobWatchdog(
                NO_HEARTBEAT_NO_DEADLINE, now::get, id -> null, id -> lastEvent.get(), logs::add, 2_000L);
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = Objects.requireNonNull(watchdog.start(
                8L,
                "build",
                "/tmp/lib",
                Session.CancelToken.live(),
                new AtomicReference<>(),
                done,
                null,
                new WallDeadline(0L, "x"),
                1_000L));
        try {
            for (int i = 0; i < 4; i++) {
                now.addAndGet(1_500L);
                lastEvent.set(now.get());
                Thread.sleep(1_100L);
            }
            assertThat(logs).isEmpty();
        } finally {
            done.countDown();
            thread.join(Duration.ofSeconds(5));
        }
    }
}
