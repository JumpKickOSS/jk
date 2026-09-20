// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
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

/** The EOF watch and the three join budgets, driven without an engine. */
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
    }

    /**
     * The client never writes mid-job, so the EOF read can only be ended by the client — and on
     * Windows not even a half-close from this side wakes it. The connection thread therefore
     * never sits in that read: the job's own latch releases it, and a client that sends nothing
     * cancels nothing.
     */
    @Test
    void the_job_ending_releases_the_connection_thread_while_the_client_stays_silent() throws Exception {
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, s -> {});
        PipedReader never = new PipedReader(new PipedWriter());
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger disconnects = new AtomicInteger();
        CountDownLatch returned = new CountDownLatch(1);
        Thread connection = Thread.ofPlatform().start(() -> {
            watch.watchForEof(new BufferedReader(never), done, () -> false, disconnects::incrementAndGet);
            returned.countDown();
        });
        try {
            assertThat(returned.await(1, TimeUnit.SECONDS))
                    .as("a silent client must not hold the connection thread")
                    .isFalse();
            done.countDown();
            assertThat(returned.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(disconnects)
                    .as("the job ended; the client did not go away")
                    .hasValue(0);
            assertThat(connection.isInterrupted()).isFalse();
        } finally {
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
