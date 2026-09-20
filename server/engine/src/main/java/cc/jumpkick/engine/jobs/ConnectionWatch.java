// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.SessionContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Watch a client connection for EOF while a job runs, then bound the join with the runner. The
 * read that waits for the client's EOF sits on a thread of its own: a blocked socket read can only
 * be ended by the peer (a half-close from this side does not wake it on Windows), so the
 * connection thread never parks in it and needs no wake to run the finish tail. Its only host
 * collaborators are the clock and the log.
 */
final class ConnectionWatch {

    /** How often the wait for the runner looks at the EOF watcher's verdict. */
    private static final long EOF_LOOK_MS = 50L;

    private final LongSupplier nowMillis;
    private final Consumer<String> log;

    ConnectionWatch(LongSupplier nowMillis, Consumer<String> log) {
        this.nowMillis = nowMillis;
        this.log = log;
    }

    /**
     * Wait until the job ends or the client goes away. The client never writes on this socket
     * mid-job, so its EOF is read on a watcher thread that outlives this call when the job ends
     * first; the connection closes after the job and ends that read. EOF or a read error while the
     * job's body is still running is a disconnect, and runs {@code onDisconnect} once. Once {@code
     * bodyFinished} holds, the body has sent its terminal and only its teardown remains, so the EOF
     * is the client half-closing after reading that terminal — the end of the request, not a
     * disconnect that stops it — and nothing is cancelled. Returns with the interrupt flag cleared,
     * so the joins that follow are not spuriously skipped.
     */
    void watchForEof(
            @Nullable BufferedReader reader, CountDownLatch done, BooleanSupplier bodyFinished, Runnable onDisconnect) {
        if (reader == null) return;
        CountDownLatch gone = new CountDownLatch(1);
        SessionContext.startVirtual("jk-conn-eof-watch", () -> {
            try {
                // Any in-band line while a job runs is noise: cancellation arrives out-of-band
                // as CANCEL_REQUEST on its own connection, or as EOF here.
                while (reader.readLine() != null) {}
            } catch (IOException | RuntimeException e) {
                // A read error is the client gone, the same as EOF.
            }
            gone.countDown();
        });
        try {
            while (done.getCount() > 0) {
                if (gone.await(EOF_LOOK_MS, TimeUnit.MILLISECONDS)) {
                    if (done.getCount() > 0 && !bodyFinished.getAsBoolean()) onDisconnect.run();
                    break;
                }
            }
        } catch (InterruptedException e) {
            // A cancel or runner wake landed here: the joins below bound what is left.
        }
        Thread.interrupted();
    }

    /**
     * Wait for the runner with one of two budgets so a wedged runner can never hang the
     * connection: under a wall deadline, until deadline plus grace and then one last chance after
     * enforcing it; otherwise until the runner's own finally ends the wait or a cancel begins —
     * {@code cancelled} is released by the first user cancel, however it arrived — and from a
     * cancel, a short cancel grace, a force kill, and a bounded wait for the runner's next cancel
     * check before the job is written off.
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

    /**
     * How many more join budgets a runner gets, after its workers are killed, to reach its next
     * cancel check before the job is written off as abandoned. A runner that looks for the cancel
     * between units of work — the forecast at each module boundary — ends at its next look, and one
     * module of a large reactor can outlast the budget itself.
     */
    private static final long NEXT_CHECK_BUDGETS = 4L;

    private void joinAfterCancel(long jid, CountDownLatch done, long cancelGraceMs, Runnable forceKill)
            throws InterruptedException {
        // User cancel without wall deadline: join for cancelGrace + a small buffer, then kill the
        // workers so nothing the runner waits on is still running.
        long joinBudget = cancelGraceMs + 500L;
        if (done.await(joinBudget, TimeUnit.MILLISECONDS)) return;
        forceKill.run();
        long nextCheck = NEXT_CHECK_BUDGETS * joinBudget;
        if (!done.await(nextCheck, TimeUnit.MILLISECONDS)) {
            log.accept("jk engine: job " + jid + " still running after cancel+" + (joinBudget + nextCheck)
                    + "ms — abandoned; workers force-killed");
        }
    }
}
