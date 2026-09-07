// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import cc.jumpkick.run.JkThreads;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The only code that writes a line to a client's JSONL socket.
 *
 * <p>One connection's writer is shared by several producers: the plan's worker threads, the
 * envelope's heartbeat watchdog, the cancel/terminal path, and the job-finish tail. A line is only
 * atomic on the wire if every one of them holds the same monitor while writing it, so this class
 * owns that monitor and nothing else takes it.
 *
 * <p>One implementation, because the invariant has three producers ({@code EngineServer.send},
 * {@code WireEventSink.emit}, {@code JobEnvelope.send}) and the 30-second heartbeat rides the
 * envelope's: a producer that skips the monitor can splice a heartbeat into the middle of a plan
 * event and hand the client a malformed line.
 */
@NullMarked
public final class WireWriter {

    private WireWriter() {}

    /**
     * Write one line, atomically with respect to every other producer on this writer.
     *
     * <p>The write runs on an io-pool thread, not the caller's. The writer sits on an interruptible
     * channel, and the JDK closes such a channel when the thread blocked in it is interrupted — so a
     * cancelled runner interrupted mid-progress-line would take the client's stream down for every
     * producer sharing it, before the terminal and {@code job-finish} could reach the client. Nobody
     * interrupts the io pool, so the line lands and the socket stays open; the caller keeps its
     * interrupt flag.
     */
    public static void send(BufferedWriter writer, String line) throws IOException {
        Future<?> written = JkThreads.io().submit(() -> {
            synchronized (writer) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
            return null;
        });
        try {
            await(written);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IOException(cause);
        }
    }

    /**
     * Wait for the line to reach the socket, even when the caller is already interrupted.
     *
     * <p>{@code Future.get} throws immediately if the caller's interrupt flag is set, so an
     * interrupted producer that handed its line to the pool and returned could be overtaken by the
     * next producer's line, submitted from a thread that was not interrupted. A {@code progress}
     * frame arriving after {@code job-finish} is not a stream the client can read: the terminal is
     * where it stops. Ordering is the reason this class exists, and it cannot depend on which
     * callers happened to be interrupted.
     *
     * <p>The wait is bounded by the write itself. The io pool is never interrupted, and a gone
     * client fails the write rather than blocking it. The caller's interrupt is restored before
     * this returns, so a cancelled runner still sees its cancellation on the next check.
     */
    private static void await(Future<?> written) throws ExecutionException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    written.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * As {@link #send}, but a write failure is dropped: it means the client is gone, and the
     * cancel-watching read loop notices the same disconnect. A null writer is a detached job
     * (HTTP/MCP) with no wire at all.
     */
    public static void sendQuiet(@Nullable BufferedWriter writer, String line) {
        if (writer == null) return;
        try {
            send(writer, line);
        } catch (IOException ignored) {
            // client gone; the read loop sees it too
        }
    }
}
