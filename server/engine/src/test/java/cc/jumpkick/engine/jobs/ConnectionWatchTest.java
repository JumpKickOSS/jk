// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.testing.Await;
import java.io.BufferedReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The read-park gate and the three join budgets, driven without an engine. */
class ConnectionWatchTest {

    @Test
    void eof_while_the_job_runs_is_one_disconnect_and_leaves_no_interrupt_behind() {
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, s -> {});
        AtomicInteger disconnects = new AtomicInteger();
        watch.watchForEof(
                new BufferedReader(new StringReader("")), new CountDownLatch(1), disconnects::incrementAndGet);
        assertThat(disconnects).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(watch.parkedOnRead()).isFalse();
    }

    @Test
    void the_wake_reaches_the_connection_thread_only_while_it_is_parked_on_the_read() throws Exception {
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, s -> {});
        PipedReader never = new PipedReader(new PipedWriter());
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger disconnects = new AtomicInteger();
        Thread connection = Thread.ofPlatform().start(() -> {
            watch.watchForEof(new BufferedReader(never), done, disconnects::incrementAndGet);
            try {
                hold.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            Await.until(Duration.ofSeconds(10), watch::parkedOnRead);
            assertThat(watch.parkedOnRead()).isTrue();

            // No channel to half-close: the blunt wake is the only way off the read.
            watch.wakeIfParked(null, connection);
            Await.until(Duration.ofSeconds(10), () -> !watch.parkedOnRead());
            assertThat(watch.parkedOnRead()).isFalse();
            assertThat(disconnects)
                    .as("a wake with the job still running is a disconnect")
                    .hasValue(1);

            // Off the read: a second wake must not interrupt a thread about to do teardown I/O.
            watch.wakeIfParked(null, connection);
            assertThat(connection.isAlive()).isTrue();
            assertThat(connection.isInterrupted()).isFalse();
        } finally {
            hold.countDown();
            connection.join(10_000);
        }
    }

    @Test
    void after_a_cancel_the_join_is_bounded_and_a_runner_that_ignores_it_is_force_killed() {
        List<String> log = new ArrayList<>();
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, log::add);
        AtomicInteger kills = new AtomicInteger();
        long start = System.nanoTime();
        watch.awaitRunner(
                7L, new CountDownLatch(1), JobLimits.DEFAULTS, 50L, 0L, true, () -> {}, kills::incrementAndGet);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(kills).hasValue(1);
        assertThat(log).singleElement().asString().contains("still running after cancel+550ms");
    }

    @Test
    void under_a_deadline_the_join_enforces_it_once_and_gives_the_runner_one_last_chance() {
        List<String> log = new ArrayList<>();
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, log::add);
        AtomicInteger enforced = new AtomicInteger();
        long start = System.nanoTime();
        watch.awaitRunner(
                8L,
                new CountDownLatch(1),
                new JobLimits(0L, 50L, 100L, 500L),
                0L,
                System.currentTimeMillis(),
                false,
                enforced::incrementAndGet,
                () -> {});
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(enforced).hasValue(1);
        assertThat(log).singleElement().asString().contains("still running after deadline+");
    }

    @Test
    void with_neither_deadline_nor_cancel_the_join_waits_for_the_runner() throws Exception {
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, s -> {});
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                // Real time: the wake has to arrive while the read is parked, from another thread.
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.countDown();
        });
        watch.awaitRunner(9L, done, JobLimits.DEFAULTS, 0L, 0L, false, () -> {}, () -> {});
        assertThat(done.getCount()).isZero();
    }
}
