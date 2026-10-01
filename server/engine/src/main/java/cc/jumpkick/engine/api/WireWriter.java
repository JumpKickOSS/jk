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
import java.util.concurrent.ForkJoinPool;
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
 * <p>A client that reads slower than its job emits is paced, not dropped: once {@value
 * #MAX_QUEUED_BYTES} bytes of lines wait for it, a {@link #sendQuiet} waits for the client to drain
 * before it queues more, so the job runs at the client's pace and the queue stays bounded. A worker
 * of the CPU pool waits as a managed block, so the pool runs a spare thread in its place and other
 * jobs keep their parallelism. Lines from threads that serve more than this client — a cancel, a
 * deadline, a heartbeat — go through {@link #sendNoWait}, which never waits.
 *
 * <p>A client that stops reading is dropped. The stream dies — the socket is closed and every later
 * line for it fails at once — when it has had bytes to write and none has reached the socket for
 * the stream-idle bound ({@code JK_STREAM_IDLE_MS}; {@code 0} never drops a client, so a paced
 * producer then waits for as long as its client does not read). The writer reports progress every
 * {@value #WRITE_CHUNK_CHARS} characters, so a client slowly reading one long line is still reading.
 */
@NullMarked
public final class WireWriter {

    /** Bytes of unread lines past which {@link #sendQuiet} waits for the client to drain. */
    static final long MAX_QUEUED_BYTES = 8L << 20;

    /** A line longer than this is written and flushed in slices this long, each one counted as progress. */
    static final int WRITE_CHUNK_CHARS = 8_192;

    /** A writer thread with nothing to write ends after this long; the next line starts another. */
    static final long WRITER_IDLE_MS = 30_000;

    private static final String NOT_READING = "the client stopped reading its stream";

    private static final Map<BufferedWriter, Stream> STREAMS = new ConcurrentHashMap<>();
    private static final Clock CLOCK = Clock.SYSTEM;
    private static final AtomicLong WRITER_SEQ = new AtomicLong();

    private WireWriter() {}

    /**
     * Give {@code writer}'s stream its idle bound, in milliseconds ({@code 0} = never drop). A
     * writer nobody bound uses the process-wide stream-idle bound.
     */
    public static void bind(BufferedWriter writer, long idleBoundMillis) {
        STREAMS.put(writer, new Stream(writer, idleBoundMillis));
    }

    /**
     * Move {@code writer}'s idle bound for the lines that follow, in milliseconds ({@code 0} = never
     * drop). A connection binds loose for request/reply and hands the job's stream-idle bound over
     * when a job takes the connection; a writer nobody bound gets the process-wide bound.
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
        stream(writer).enqueue(line, Mode.LANDED);
    }

    /**
     * Queue one line and return, first waiting for the client to drain while the stream holds
     * {@value #MAX_QUEUED_BYTES} bytes or more. An interrupted caller does not wait. A stream whose
     * client is gone or not reading drops the line, since the cancel-watching read loop notices the
     * same disconnect. A null writer is a detached job (HTTP/MCP) with no wire at all.
     */
    public static void sendQuiet(@Nullable BufferedWriter writer, String line) {
        if (writer == null) return;
        try {
            stream(writer).enqueue(line, Mode.PACED);
        } catch (IOException gone) {
            // client gone or not reading; the read loop sees it too
        }
    }

    /**
     * {@link #sendQuiet} without the wait: for a short line from a thread that serves more than
     * this client — a cancel, a deadline, a heartbeat — which must not stand behind its backlog.
     */
    public static void sendNoWait(@Nullable BufferedWriter writer, String line) {
        if (writer == null) return;
        try {
            stream(writer).enqueue(line, Mode.QUEUED);
        } catch (IOException gone) {
            // client gone or not reading; the read loop sees it too
        }
    }

    /**
     * Stop pacing {@code writer}'s producers: its job was cancelled, and a cancelled job's lines must
     * not hold its threads behind a client that is not reading. Every producer waiting for room
     * returns at once, and from now on a {@link #sendQuiet} that finds no room drops its line rather
     * than wait. {@link #send} and {@link #sendNoWait} lines, the terminal among them, still queue.
     */
    public static void unpace(@Nullable BufferedWriter writer) {
        if (writer == null) return;
        Stream s = STREAMS.get(writer);
        if (s != null) s.unpace();
    }

    /**
     * Wait for every line queued so far to land — while the client keeps reading, however long
     * that takes; a client that reads nothing for the stream's idle bound is dropped. A job's
     * envelope waits here after its job-finish, so the connection loop it returns to never closes
     * a writer with the terminal still in the queue.
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

    /** Bytes of lines {@code writer}'s stream holds that have not reached the socket yet. */
    static long queuedBytes(BufferedWriter writer) {
        Stream s = STREAMS.get(writer);
        if (s == null) return 0;
        synchronized (s) {
            return s.queuedBytes;
        }
    }

    private static Stream stream(BufferedWriter writer) {
        return STREAMS.computeIfAbsent(writer, w -> new Stream(w, BoundedLineReader.streamIdleMillis(JkDirs::env)));
    }

    /** How a caller hands its line over. */
    private enum Mode {
        /** Wait until the line has reached the socket. */
        LANDED,
        /** Wait for room under {@link #MAX_QUEUED_BYTES}, then return. */
        PACED,
        /** Return at once. */
        QUEUED
    }

    /** One line waiting to be written; {@code landed} is present when a caller waits for it. */
    private record Pending(String line, @Nullable CompletableFuture<Void> landed) {
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

        /** Whether the writer thread holds a line it took off the queue and has not finished. */
        private boolean writing;

        /**
         * When bytes last reached the socket, or when the stream last went from nothing to write to
         * something. While the stream is busy, its stall runs from here.
         */
        private volatile long progressNanos;

        private @Nullable IOException dead;
        private boolean released;

        /** Whether the stream's job was cancelled: a paced line never waits, and one with no room is dropped. */
        private boolean unpaced;

        Stream(BufferedWriter writer, long idleBoundMillis) {
            this.writer = writer;
            this.idleBoundMillis = Math.max(0L, idleBoundMillis);
        }

        void idleBound(long millis) {
            idleBoundMillis = Math.max(0L, millis);
        }

        synchronized void unpace() {
            unpaced = true;
            notifyAll();
        }

        void enqueue(String line, Mode mode) throws IOException {
            Pending p = new Pending(line, mode == Mode.LANDED ? new CompletableFuture<>() : null);
            if (mode == Mode.PACED) {
                awaitRoom(p);
            } else {
                synchronized (this) {
                    add(p);
                }
            }
            CompletableFuture<Void> landed = p.landed();
            if (landed != null) awaitLanding(landed);
        }

        /**
         * Caller holds the monitor. Queue {@code p}, or throw when the stream is dead or its client
         * has read nothing for the idle bound.
         */
        private void add(Pending p) throws IOException {
            if (dead != null) throw dead;
            long now = CLOCK.nanos();
            long stalledMs = stalledMillis(now);
            if (stalled(stalledMs)) throw drop(notReading("has read nothing for " + stalledMs + " ms"));
            if (!busy()) progressNanos = now;
            queue.addLast(p);
            queuedBytes += p.bytes();
            if (drainer == null) startDrainer();
            else notifyAll();
        }

        /** Caller holds the monitor. A line fits while the queue stays under the bound, or is empty. */
        private boolean hasRoom(Pending p) {
            return queuedBytes == 0 || queuedBytes + p.bytes() <= MAX_QUEUED_BYTES;
        }

        /**
         * Queue {@code p} once the stream has room for it. The wait is a managed block, so a CPU-pool
         * worker waiting here has a spare thread run in its place. It ends when the client drains,
         * when the client is dropped for reading nothing within the idle bound, when the caller is
         * interrupted (its line is queued), or when the job is cancelled (its line is dropped).
         */
        private void awaitRoom(Pending p) throws IOException {
            synchronized (this) {
                if (unpaced) {
                    if (hasRoom(p)) add(p);
                    return;
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                synchronized (this) {
                    add(p);
                }
                return;
            }
            RoomBlocker blocker = new RoomBlocker(p);
            try {
                ForkJoinPool.managedBlock(blocker);
            } catch (InterruptedException e) {
                // block() keeps the interrupt itself and never throws it
                Thread.currentThread().interrupt();
            }
            if (blocker.interrupted) Thread.currentThread().interrupt();
            IOException failed = blocker.failed;
            if (failed != null) throw failed;
        }

        /** {@link #awaitRoom}'s wait. Queues the line itself, under the monitor, once there is room. */
        private final class RoomBlocker implements ForkJoinPool.ManagedBlocker {
            private final Pending p;
            private boolean done;
            private boolean interrupted;
            private @Nullable IOException failed;

            RoomBlocker(Pending p) {
                this.p = p;
            }

            @Override
            public boolean isReleasable() {
                synchronized (Stream.this) {
                    if (!done && (dead != null || hasRoom(p))) addOrFail();
                    else if (!done && unpaced) done = true;
                    return done;
                }
            }

            @Override
            public boolean block() {
                synchronized (Stream.this) {
                    while (!done) {
                        if (dead != null || hasRoom(p) || interrupted) {
                            addOrFail();
                            break;
                        }
                        if (unpaced) {
                            done = true;
                            break;
                        }
                        long stalledMs = stalledMillis(CLOCK.nanos());
                        if (stalled(stalledMs)) {
                            failed = drop(notReading("has read nothing for " + stalledMs + " ms"));
                            done = true;
                            break;
                        }
                        try {
                            Stream.this.wait(pollMillis(stalledMs));
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
                return true;
            }

            /** Caller holds the stream's monitor. */
            private void addOrFail() {
                try {
                    add(p);
                } catch (IOException e) {
                    failed = e;
                }
                done = true;
            }
        }

        /** Caller holds the monitor. Whether the stream has bytes it has not written yet. */
        private boolean busy() {
            return writing || !queue.isEmpty();
        }

        /** Caller holds the monitor. How long a busy stream has gone without writing a byte, in milliseconds. */
        private long stalledMillis(long now) {
            return busy() ? (now - progressNanos) / 1_000_000L : 0L;
        }

        private boolean stalled(long stalledMs) {
            long bound = idleBoundMillis;
            return bound > 0 && stalledMs >= bound;
        }

        /** How long to wait before looking at the stall again: what is left of the idle bound, at most a second. */
        private long pollMillis(long stalledMs) {
            long bound = idleBoundMillis;
            return bound > 0 ? Math.max(1L, Math.min(bound - stalledMs, 1_000L)) : 1_000L;
        }

        private void awaitLanding(CompletableFuture<Void> landed) throws IOException {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        long waitMs;
                        synchronized (this) {
                            waitMs = pollMillis(stalledMillis(CLOCK.nanos()));
                        }
                        landed.get(waitMs, TimeUnit.MILLISECONDS);
                        return;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (TimeoutException e) {
                        synchronized (this) {
                            long stalledMs = stalledMillis(CLOCK.nanos());
                            if (stalled(stalledMs)) {
                                throw drop(notReading("has read nothing for " + stalledMs + " ms"));
                            }
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
                        "idleBoundMs",
                        idleBoundMillis,
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
            if (t != null && t != Thread.currentThread() && writing) t.interrupt();
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
                    writing = true;
                }
                CompletableFuture<Void> landed = p.landed();
                try {
                    write(p.line());
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
                    writing = false;
                    notifyAll();
                }
                if (landed != null) landed.complete(null);
            }
        }

        /** One line and its newline; a long line goes out in flushed slices, each one counted as progress. */
        private void write(String line) throws IOException {
            synchronized (writer) {
                int length = line.length();
                int off = 0;
                while (length - off > WRITE_CHUNK_CHARS) {
                    int end = off + WRITE_CHUNK_CHARS;
                    if (Character.isHighSurrogate(line.charAt(end - 1))) end--;
                    writer.write(line, off, end - off);
                    writer.flush();
                    progressNanos = CLOCK.nanos();
                    off = end;
                }
                writer.write(line, off, length - off);
                writer.write('\n');
                writer.flush();
                progressNanos = CLOCK.nanos();
            }
        }

        /** Caller holds the monitor; the writer thread is about to return. */
        private void endDrainer() {
            drainer = null;
            writing = false;
            // Close the socket here, not only via interrupt of a blocked write: on Windows a pipe
            // sink can stay open after Thread.interrupt until the channel is closed explicitly.
            if (dead != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // stream already dead
                }
            }
            notifyAll();
        }

        synchronized void release() {
            awaitLanded();
            released = true;
            notifyAll();
        }

        synchronized void awaitLanded() {
            boolean interrupted = false;
            try {
                while (dead == null && busy()) {
                    long stalledMs = stalledMillis(CLOCK.nanos());
                    if (stalled(stalledMs)) {
                        drop(notReading("still holds " + queuedBytes + " bytes of unread lines at close"));
                        break;
                    }
                    try {
                        wait(pollMillis(stalledMs));
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
