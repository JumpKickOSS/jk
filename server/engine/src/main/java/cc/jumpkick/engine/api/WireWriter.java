// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.BoundedLineReader;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The only code that writes a line to a client's JSONL socket.
 *
 * <p>Every line for one connection goes through one queue and one writer thread, so a line is
 * atomic and lines land in the order they were handed in, whichever thread handed them over — the
 * plan's worker threads, the envelope's heartbeat watchdog, the cancel path and the job-finish
 * tail. The writer thread is a platform thread of its own: not one of the CPU pool's threads and
 * not a virtual thread, so a client that reads slowly parks nothing the engine needs for other
 * clients, and a saturated virtual-thread scheduler cannot hold a hello reply back.
 *
 * <p>A client that stops reading is dropped, not waited for. Its stream dies — the socket is closed
 * and every later line for it fails at once — when {@value #MAX_QUEUED_BYTES} bytes of lines wait
 * for it, or when its oldest unread line is older than the stream-idle bound ({@code
 * JK_STREAM_IDLE_MS}; {@code 0} leaves only the byte bound), which is also how long a {@link #send}
 * waits for its line to land before it gives the client up.
 */
@NullMarked
public final class WireWriter {

    /** Bytes of lines a stream holds for a client that is not reading before that client is dropped. */
    static final long MAX_QUEUED_BYTES = 8L << 20;

    /** A writer thread with nothing to write ends after this long; the next line starts another. */
    static final long WRITER_IDLE_MS = 30_000;

    private static final String NOT_READING = "the client stopped reading its stream";

    private static final Map<BufferedWriter, Stream> STREAMS = new ConcurrentHashMap<>();
    private static final Clock CLOCK = Clock.SYSTEM;
    private static final AtomicLong WRITER_SEQ = new AtomicLong();

    private WireWriter() {}

    /**
     * Give {@code writer}'s stream its idle bound, in milliseconds ({@code 0} = only the byte
     * bound). A writer nobody bound uses the process-wide stream-idle bound.
     */
    public static void bind(BufferedWriter writer, long idleBoundMillis) {
        STREAMS.put(writer, new Stream(writer, idleBoundMillis));
    }

    /**
     * Move {@code writer}'s idle bound for the lines that follow, in milliseconds ({@code 0} = only
     * the byte bound). A connection binds loose for request/reply and hands the job's stream-idle
     * bound over when a job takes the connection; a writer nobody bound gets the process-wide bound.
     */
    public static void idleBound(BufferedWriter writer, long idleBoundMillis) {
        stream(writer).idleBound(idleBoundMillis);
    }

    /**
     * Queue one line and wait until it has reached the socket, or throw the failure that kept it
     * from getting there: the client gone, or the client not reading within the stream's idle bound.
     * The wait survives the caller's interrupt — the flag is restored before this returns — because
     * a cancelled runner's terminal still has to be the last line the client reads.
     */
    public static void send(BufferedWriter writer, String line) throws IOException {
        stream(writer).enqueue(line, true);
    }

    /**
     * Queue one line and return; a stream whose client is gone or not reading drops the line, since
     * the cancel-watching read loop notices the same disconnect. A null writer is a detached job
     * (HTTP/MCP) with no wire at all.
     */
    public static void sendQuiet(@Nullable BufferedWriter writer, String line) {
        if (writer == null) return;
        try {
            stream(writer).enqueue(line, false);
        } catch (IOException gone) {
            // client gone or not reading; the read loop sees it too
        }
    }

    /**
     * Wait for every line queued so far to land — bounded by the stream's idle bound, past which
     * the client is dropped. A job's envelope waits here after its job-finish, so the connection
     * loop it returns to never closes a writer with the terminal still in the queue.
     */
    public static void awaitLanded(BufferedWriter writer) {
        Stream s = STREAMS.get(writer);
        if (s != null) s.awaitLanded();
    }

    /**
     * {@link #awaitLanded}, then let the stream's writer thread go. The connection calls this
     * before it closes the writer.
     */
    public static void release(BufferedWriter writer) {
        Stream s = STREAMS.remove(writer);
        if (s != null) s.release();
    }

    private static Stream stream(BufferedWriter writer) {
        return STREAMS.computeIfAbsent(writer, w -> new Stream(w, BoundedLineReader.streamIdleMillis(JkDirs::env)));
    }

    /** One line waiting to be written; {@code landed} is present when a caller waits for it. */
    private record Pending(String line, @Nullable CompletableFuture<Void> landed, long queuedNanos) {
        long bytes() {
            return line.length() + 1L;
        }
    }

    /** One connection's outbound queue and the thread that drains it. Guarded by its own monitor. */
    private static final class Stream {
        private final BufferedWriter writer;
        private volatile long idleBoundMillis;
        private final ArrayDeque<Pending> queue = new ArrayDeque<>();
        private long queuedBytes;
        private @Nullable Thread drainer;

        /** When the line being written left the queue; {@code 0} while nothing is being written. */
        private long writingSinceNanos;

        private @Nullable IOException dead;
        private boolean released;

        Stream(BufferedWriter writer, long idleBoundMillis) {
            this.writer = writer;
            this.idleBoundMillis = Math.max(0L, idleBoundMillis);
        }

        void idleBound(long millis) {
            idleBoundMillis = Math.max(0L, millis);
        }

        void enqueue(String line, boolean await) throws IOException {
            Pending p;
            synchronized (this) {
                if (dead != null) throw dead;
                long now = CLOCK.nanos();
                p = new Pending(line, await ? new CompletableFuture<>() : null, now);
                if (queuedBytes + p.bytes() > MAX_QUEUED_BYTES) {
                    throw drop(notReading("holds " + queuedBytes + " bytes of unread lines"));
                }
                long stalledMs = stalledMillis(now);
                if (idleBoundMillis > 0 && stalledMs > idleBoundMillis) {
                    throw drop(notReading("has read nothing for " + stalledMs + " ms"));
                }
                queue.addLast(p);
                queuedBytes += p.bytes();
                if (drainer == null) startDrainer();
                else notifyAll();
            }
            CompletableFuture<Void> landed = p.landed();
            if (landed != null) awaitLanding(landed);
        }

        /** Caller holds the monitor. Age of the oldest line the client has not read yet, in milliseconds. */
        private long stalledMillis(long now) {
            Pending oldest = queue.peekFirst();
            long since = writingSinceNanos != 0 ? writingSinceNanos : oldest == null ? now : oldest.queuedNanos();
            return (now - since) / 1_000_000L;
        }

        private void awaitLanding(CompletableFuture<Void> landed) throws IOException {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        if (idleBoundMillis > 0) landed.get(idleBoundMillis, TimeUnit.MILLISECONDS);
                        else landed.get();
                        return;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (TimeoutException e) {
                        synchronized (this) {
                            throw drop(notReading("has not read a line in " + idleBoundMillis + " ms"));
                        }
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof IOException io) throw io;
                        if (cause instanceof RuntimeException re) throw re;
                        if (cause instanceof Error err) throw err;
                        throw new IOException(cause);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private static IOException notReading(String detail) {
            return new IOException(NOT_READING + " (" + detail + "); dropping it");
        }

        /**
         * Caller holds the monitor. Kill the stream: fail every waiting line, and close the socket
         * under a writer thread blocked in it — interrupting a thread inside an interruptible
         * channel closes that channel — so the connection's read loop ends as well. A client that
         * went away is the read loop's news; a client that stopped reading is said in the log.
         */
        private IOException drop(IOException cause) {
            IOException already = dead;
            if (already != null) return already;
            dead = cause;
            String message = cause.getMessage();
            if (message != null && message.startsWith(NOT_READING)) {
                Log.warn(
                        "jk engine: dropped a client that stopped reading its stream",
                        "queuedBytes",
                        queuedBytes,
                        "queuedLines",
                        queue.size());
            }
            for (Pending p; (p = queue.pollFirst()) != null; ) {
                CompletableFuture<Void> landed = p.landed();
                if (landed != null) landed.completeExceptionally(cause);
            }
            queuedBytes = 0;
            Thread t = drainer;
            if (t != null && t != Thread.currentThread() && writingSinceNanos != 0) t.interrupt();
            notifyAll();
            return cause;
        }

        /** Caller holds the monitor. */
        private void startDrainer() {
            // One writer per client stream; a line carries no session.
            drainer = Thread.ofPlatform()
                    .daemon()
                    .name("jk-wire-" + WRITER_SEQ.incrementAndGet())
                    .start(this::drain);
        }

        private void drain() {
            while (true) {
                Pending p;
                synchronized (this) {
                    p = queue.pollFirst();
                    if (p == null) {
                        if (dead != null || released) {
                            endDrainer();
                            return;
                        }
                        try {
                            wait(WRITER_IDLE_MS);
                        } catch (InterruptedException e) {
                            endDrainer();
                            return;
                        }
                        p = queue.pollFirst();
                        if (p == null) {
                            endDrainer();
                            return;
                        }
                    }
                    writingSinceNanos = p.queuedNanos();
                }
                CompletableFuture<Void> landed = p.landed();
                try {
                    synchronized (writer) {
                        writer.write(p.line());
                        writer.write('\n');
                        writer.flush();
                    }
                } catch (IOException e) {
                    synchronized (this) {
                        drop(e);
                        endDrainer();
                    }
                    if (landed != null) landed.completeExceptionally(e);
                    return;
                }
                synchronized (this) {
                    queuedBytes -= p.bytes();
                    writingSinceNanos = 0;
                    notifyAll();
                }
                if (landed != null) landed.complete(null);
            }
        }

        /** Caller holds the monitor; the writer thread is about to return. */
        private void endDrainer() {
            drainer = null;
            writingSinceNanos = 0;
            notifyAll();
        }

        synchronized void release() {
            awaitLanded();
            released = true;
            notifyAll();
        }

        synchronized void awaitLanded() {
            boolean bounded = idleBoundMillis > 0;
            long deadlineNanos = CLOCK.nanos() + idleBoundMillis * 1_000_000L;
            boolean interrupted = false;
            try {
                while (dead == null && (!queue.isEmpty() || writingSinceNanos != 0)) {
                    long remainingMs = (deadlineNanos - CLOCK.nanos()) / 1_000_000L;
                    if (bounded && remainingMs <= 0) {
                        drop(notReading("still holds " + queuedBytes + " bytes of unread lines at close"));
                        break;
                    }
                    try {
                        wait(bounded ? Math.max(1L, Math.min(remainingMs, 1_000L)) : 1_000L);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
