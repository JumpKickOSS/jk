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
                new BufferedReader(new StringReader("")),
                new CountDownLatch(1),
                () -> false,
                disconnects::incrementAndGet);
        assertThat(disconnects).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(watch.parkedOnRead()).isFalse();
    }

    /**
     * The client half-closes its socket the moment it has read the body's terminal, which lands
     * while the runner is still tearing down; that EOF ends the request and cancels nothing.
     */
    @Test
    void eof_after_the_body_has_finished_is_the_end_of_the_request_not_a_disconnect() {
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, s -> {});
        AtomicInteger disconnects = new AtomicInteger();
        watch.watchForEof(
                new BufferedReader(new StringReader("")),
                new CountDownLatch(1),
                () -> true,
                disconnects::incrementAndGet);
        assertThat(disconnects).as("a finished body has nothing left to cancel").hasValue(0);
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
            watch.watchForEof(new BufferedReader(never), done, () -> false, disconnects::incrementAndGet);
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
                7L,
                new CountDownLatch(1),
                new WallDeadline(0L, ""),
                JobLimits.DEFAULT_DEADLINE_GRACE_MS,
                50L,
                0L,
                new CountDownLatch(0),
                () -> {},
                kills::incrementAndGet);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(kills).hasValue(1);
        assertThat(log).singleElement().asString().contains("still running after cancel+2750ms");
    }

    /**
     * A runner that looks for the cancel between units of work — the forecast at each module
     * boundary — ends at its next look, which on a large reactor lies past the join budget. The
     * workers are still killed at the budget, and the runner is given four more budgets to reach
     * that look before the job is written off as abandoned.
     */
    @Test
    void a_runner_that_ends_at_its_next_cancel_check_after_the_join_budget_is_not_written_off() throws Exception {
        List<String> log = new ArrayList<>();
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, log::add);
        AtomicInteger kills = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        // The join budget is 50 + 500 ms; the runner reaches its next check 300 ms past it.
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(850L);
            } catch (InterruptedException ignored) {
                // the test ends first
            }
            done.countDown();
        });
        watch.awaitRunner(
                9L,
                done,
                new WallDeadline(0L, ""),
                JobLimits.DEFAULT_DEADLINE_GRACE_MS,
                50L,
                0L,
                new CountDownLatch(0),
                () -> {},
                kills::incrementAndGet);
        runner.join();
        assertThat(kills).as("the workers are still killed at the join budget").hasValue(1);
        assertThat(log)
                .as("a runner that ended at its next check was not abandoned")
                .isEmpty();
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
                new WallDeadline(50L, "test"),
                100L,
                0L,
                System.currentTimeMillis(),
                new CountDownLatch(1),
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
        watch.awaitRunner(
                9L,
                done,
                new WallDeadline(0L, ""),
                JobLimits.DEFAULT_DEADLINE_GRACE_MS,
                0L,
                0L,
                new CountDownLatch(1),
                () -> {},
                () -> {});
        assertThat(done.getCount()).isZero();
    }
}
