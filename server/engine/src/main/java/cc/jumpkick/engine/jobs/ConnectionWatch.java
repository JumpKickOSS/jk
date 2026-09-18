// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.host.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Watch a client connection for EOF while a job runs, then bound the join with the runner. The
 * connection thread may be interrupted only while it is actually parked in {@code readLine}: an
 * interrupt landing after the loop poisons teardown I/O, and a stray one once killed journal
 * completion with {@code ClosedByInterruptException}, leaving a permanently running job in
 * {@code jk jobs}. Its only host collaborators are the clock and the log.
 */
final class ConnectionWatch {

    private final AtomicBoolean parkedOnRead = new AtomicBoolean(false);
    private final LongSupplier nowMillis;
    private final Consumer<String> log;

    ConnectionWatch(LongSupplier nowMillis, Consumer<String> log) {
        this.nowMillis = nowMillis;
        this.log = log;
    }

    /**
     * Read {@code reader} until the job ends or the client goes away. The client never writes on this
     * socket mid-job, so a plain blocking read would park forever after the runner finished; cancel
     * and runner teardown wake this thread so the finish tail can run. EOF or a read error while the
     * job's body is still running is a disconnect, and runs {@code onDisconnect} once. Once
     * {@code bodyFinished} holds, the body has sent its terminal and only its teardown remains, so
     * the EOF is the client half-closing after reading that terminal — the end of the request, not a
     * disconnect that stops it — and nothing is cancelled. Returns with the interrupt flag cleared,
     * so the joins that follow are not spuriously skipped.
     */
    void watchForEof(
            @Nullable BufferedReader reader, CountDownLatch done, BooleanSupplier bodyFinished, Runnable onDisconnect) {
        try {
            while (reader != null && done.getCount() > 0) {
                try {
                    parkedOnRead.set(true);
                    String line = reader.readLine();
                    parkedOnRead.set(false);
                    if (line == null) {
                        // EOF / client gone mid-job — same bounded cancel path (not explicit:
                        // an EOF after a reported failure is the terminal-read race).
                        if (!bodyFinished.getAsBoolean()) onDisconnect.run();
                        break;
                    }
                    // Any in-band line while a job runs is noise: cancellation arrives
                    // out-of-band as CANCEL_REQUEST on its own connection, or as EOF here.
                } catch (IOException e) {
                    parkedOnRead.set(false);
                    // Interrupt during read (ClosedByInterruptException, etc.) or a real error.
                    if (done.getCount() == 0 || Thread.currentThread().isInterrupted()) {
                        break; // runner done / cancel wake — join below
                    }
                    if (!bodyFinished.getAsBoolean()) onDisconnect.run();
                    break;
                }
            }
            parkedOnRead.set(false);
        } catch (RuntimeException ignored) {
            if (done.getCount() > 0 && !bodyFinished.getAsBoolean()) onDisconnect.run();
        }
        Thread.interrupted();
    }

    /**
     * Wait for the runner with one of two budgets so a wedged runner can never hang the
     * connection: under a wall deadline, until deadline plus grace and then one last chance after
     * enforcing it; otherwise until the runner's own finally ends the wait or a cancel begins —
     * {@code cancelled} is released by the first user cancel, however it arrived — and from a
     * cancel, a short cancel grace and then a force kill.
     */
    void awaitRunner(
            long jid,
            CountDownLatch done,
            WallDeadline deadline,
            long deadlineGraceMs,
            long cancelGraceMs,
            long startMillis,
            CountDownLatch cancelled,
            Runnable enforceDeadline,
            Runnable forceKill) {
        try {
            if (deadline.bounded()) {
                joinUnderDeadline(
                        jid, done, deadline.ms(), deadlineGraceMs, cancelGraceMs, startMillis, enforceDeadline);
            } else {
                awaitRunnerOrCancel(done, cancelled);
                if (done.getCount() > 0) joinAfterCancel(jid, done, cancelGraceMs, forceKill);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * How often the open-ended join looks for a cancel. Two latches, one waiter: the runner's
     * finally releases the first, a user cancel the second, and a park on either alone would miss
     * the other. The tick is a fraction of the cancel grace, so it does not stretch the join.
     */
    private static final long CANCEL_LOOK_MS = 100L;

    private static void awaitRunnerOrCancel(CountDownLatch done, CountDownLatch cancelled) throws InterruptedException {
        while (done.getCount() > 0 && cancelled.getCount() > 0) {
            done.await(CANCEL_LOOK_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void joinUnderDeadline(
            long jid,
            CountDownLatch done,
            long deadlineMs,
            long graceMs,
            long cancelGraceMs,
            long startMillis,
            Runnable enforceDeadline)
            throws InterruptedException {
        long elapsed = nowMillis.getAsLong() - startMillis;
        long budget = Math.max(1L, deadlineMs + graceMs - elapsed);
        if (done.await(budget, TimeUnit.MILLISECONDS)) return;
        enforceDeadline.run();
        // Last chance for the runner to unwind after worker kill / interrupt.
        // Cap hard so UX never waits the full 30s grace when the job is deadlocked.
        long lastChance = Math.min(graceMs, Math.max(cancelGraceMs + 200L, 1_000L));
        if (!done.await(lastChance, TimeUnit.MILLISECONDS)) {
            log.accept("jk engine: job " + jid + " still running after deadline+" + lastChance
                    + "ms grace — abandoned; workers killed");
        }
    }

    private void joinAfterCancel(long jid, CountDownLatch done, long cancelGraceMs, Runnable forceKill)
            throws InterruptedException {
        // User cancel without wall deadline: join only for cancelGrace + small buffer.
        long joinBudget = cancelGraceMs + 500L;
        if (done.await(joinBudget, TimeUnit.MILLISECONDS)) return;
        forceKill.run();
        if (!done.await(200L, TimeUnit.MILLISECONDS)) {
            log.accept("jk engine: job " + jid + " still running after cancel+" + joinBudget
                    + "ms — abandoned; workers force-killed");
        }
    }

    /** Whether the connection thread is parked in {@code readLine} right now. */
    boolean parkedOnRead() {
        return parkedOnRead.get();
    }

    /** Wake the connection thread only while it is actually parked on the read; otherwise nothing. */
    void wakeIfParked(@Nullable SocketChannel channel, Thread connectionThread) {
        if (parkedOnRead.get()) wakeOffClientRead(channel, connectionThread);
    }

    /**
     * Wake the connection thread off client-readLine so it can run the finish tail.
     *
     * <p>Half-closing the read direction is the gentle wake: the blocked read sees EOF while the
     * write direction stays usable, so the tail can still deliver {@code job-finish} — the line the
     * client waits for before it may delete {@code target/}. {@link Thread#interrupt} is
     * the fallback, and it is blunt: on a thread blocked in an InterruptibleChannel read it closes
     * the whole channel, so the client learns the job ended one journal-write too early. A platform
     * whose half-close does not wake a blocked read is still covered — the client half-closes its
     * own end once it has the terminal, which delivers the same EOF.
     */
    static void wakeOffClientRead(@Nullable SocketChannel channel, Thread connectionThread) {
        if (channel != null) {
            try {
                channel.shutdownInput();
                return;
            } catch (IOException | UnsupportedOperationException ignored) {
                // Not a half-closable transport (or already gone) — fall through to the blunt wake.
            }
        }
        try {
            connectionThread.interrupt();
        } catch (RuntimeException e) {
            // best-effort wake
            Log.debug("wakeOffClientRead: best-effort wake", e);
        }
    }
}
