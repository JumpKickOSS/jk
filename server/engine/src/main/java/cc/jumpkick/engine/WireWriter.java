// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.run.JkThreads;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
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
 * <p>It exists because that discipline was previously restated in three places and one of them
 * disagreed: {@code EngineServer.send} and {@code WireEventSink.emit} both synchronized (each with a
 * comment explaining why), while {@code JobEnvelope.send} did not — and the envelope's copy was the
 * one the 30-second heartbeat used, so a long build could splice a heartbeat into the middle of a
 * plan event and hand the client a malformed line. Three implementations of one invariant is two
 * too many; a fourth would have arrived with the next producer.
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
     * interrupts the io pool. A caller interrupted while waiting gets an {@link InterruptedIOException}
     * and keeps its interrupt flag; the line still lands, and the socket stays open.
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
            written.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while a wire line was being written");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IOException(cause);
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
