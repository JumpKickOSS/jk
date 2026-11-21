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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The EOF watch and the three join budgets, driven without an engine. */
class ConnectionWatchTest {

    /** The join buffer past the cancel grace, shortened from the production default. */
    private static final long JOIN_BUFFER_MS = 50L;

    @Test
    void eof_while_the_job_runs_is_one_disconnect_and_leaves_no_interrupt_behind() {
        ConnectionWatch watch =
                new ConnectionWatch(System::currentTimeMillis, s -> {}, 50L, ConnectionWatch.JOIN_BUFFER_MS);
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
     * The body sends its terminal and then returns; the client's half-close on reading it lands in
     * between. That EOF is still the end of the request: the body returning moments later proves it.
     */
    @Test
    void eof_just_before_the_body_returns_is_the_end_of_the_request_not_a_disconnect() {
        ConnectionWatch watch = new ConnectionWatch(System::currentTimeMillis, s -> {});
        AtomicInteger disconnects = new AtomicInteger();
        long returnsAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        watch.watchForEof(
                new BufferedReader(new StringReader("")),
                new CountDownLatch(1),
                () -> System.nanoTime() >= returnsAt,
                disconnects::incrementAndGet);
        assertThat(disconnects).as("the body returned inside the grace").hasValue(0);
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
            assertThat(returned.await(200, TimeUnit.MILLISECONDS))
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
        ConnectionWatch watch = new ConnectionWatch(
                System::currentTimeMillis, log::add, ConnectionWatch.TERMINAL_GRACE_MS, JOIN_BUFFER_MS);
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
        assertThat(log).singleElement().asString().contains("still running after cancel+500ms");
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
        ConnectionWatch watch = new ConnectionWatch(
                System::currentTimeMillis, log::add, ConnectionWatch.TERMINAL_GRACE_MS, JOIN_BUFFER_MS);
        AtomicInteger kills = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        // The join budget is 50 + 50 ms; the runner reaches its next check 150 ms past it, well
        // inside the four budgets it is given.
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(250L);
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

    /**
     * A detached job always runs under a wall deadline. A cancel of one whose runner ignores it
     * ends in the cancel's bounded join, with the workers killed, not at the deadline an hour on.
     */
    @Test
    void under_a_deadline_a_cancel_takes_the_bounded_cancel_join() {
        List<String> log = new ArrayList<>();
        ConnectionWatch watch = new ConnectionWatch(
                System::currentTimeMillis, log::add, ConnectionWatch.TERMINAL_GRACE_MS, JOIN_BUFFER_MS);
        AtomicInteger enforced = new AtomicInteger();
        AtomicInteger kills = new AtomicInteger();
        long start = System.nanoTime();
        watch.awaitRunner(
                10L,
                new CountDownLatch(1),
                new WallDeadline(30_000L, "test"),
                100L,
                50L,
                System.currentTimeMillis(),
                new CountDownLatch(0),
                enforced::incrementAndGet,
                kills::incrementAndGet);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(kills).hasValue(1);
        assertThat(enforced)
                .as("a cancelled job is not also cancelled by its deadline")
                .hasValue(0);
        assertThat(log).singleElement().asString().contains("still running after cancel+");
    }

    /**
     * Time the engine was suspended is not the job's: a job whose wall clock ran past its deadline
     * only across a suspend is still inside it, and the join waits for its runner.
     */
    @Test
    void under_a_deadline_the_time_suspended_is_not_counted() throws Exception {
        long suspended = 60_000L;
        ConnectionWatch watch = new ConnectionWatch(
                System::currentTimeMillis,
                jid -> jid == 11L ? suspended : 0L,
                s -> {},
                ConnectionWatch.TERMINAL_GRACE_MS,
                JOIN_BUFFER_MS);
        AtomicInteger enforced = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ignored) {
                // the test ends first
            }
            done.countDown();
        });
        watch.awaitRunner(
                11L,
                done,
                new WallDeadline(5_000L, "test"),
                100L,
                50L,
                System.currentTimeMillis() - suspended,
                new CountDownLatch(1),
                enforced::incrementAndGet,
                () -> {});
        runner.join();
        assertThat(enforced).as("the job ran 200ms of a 5s deadline").hasValue(0);
        assertThat(done.getCount()).isZero();
    }

    /** A suspend that lands while the join waits moves the wall clock, not the job's deadline. */
    @Test
    void a_suspend_during_the_join_does_not_spend_the_deadline() throws Exception {
        AtomicLong away = new AtomicLong();
        AtomicLong now = new AtomicLong(1_000L);
        ConnectionWatch watch = new ConnectionWatch(
                now::get, jid -> away.get(), s -> {}, ConnectionWatch.TERMINAL_GRACE_MS, JOIN_BUFFER_MS);
        AtomicInteger enforced = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(150L);
                away.set(3_600_000L);
                now.addAndGet(3_600_000L);
                Thread.sleep(150L);
            } catch (InterruptedException ignored) {
                // the test ends first
            }
            done.countDown();
        });
        watch.awaitRunner(
                12L,
                done,
                new WallDeadline(5_000L, "test"),
                100L,
                50L,
                1_000L,
                new CountDownLatch(1),
                enforced::incrementAndGet,
                () -> {});
        runner.join();
        assertThat(enforced).hasValue(0);
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
