// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A response body whose every write must finish within {@link #PER_WRITE}. A client that stops
 * reading fills the socket's send buffer and would hold the handler thread, and its admission
 * permit, forever; past the deadline a watchdog interrupts the writing thread, which closes the
 * connection under it, and the write throws. Time between writes does not count; a large write is
 * cut into slices, each with its own deadline.
 */
final class WriteDeadline extends FilterOutputStream {

    /** How long one write may wait for the client to take its bytes. */
    static final Duration PER_WRITE = Duration.ofSeconds(30);

    /**
     * The most one write hands the stream under it at once. A slow client that keeps reading takes
     * a slice well inside the deadline, where a whole large body in one write would outlast it.
     */
    static final int SLICE = 64 * 1024;

    /** Runs every deadline of the HTTP layer; its tasks must not block. */
    static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    /** One daemon thread shared by every response; a cancelled deadline leaves the queue at once. */
    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "jk-http-write-deadline");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final Duration limit;
    private final Object watch = new Object();
    private @Nullable Thread writer;
    private @Nullable ScheduledFuture<?> deadline;
    private boolean expired;
    private boolean closed;
    /** Counts writes, so a deadline that fires as its write ends never expires the next one. */
    private long writes;

    WriteDeadline(OutputStream out, Duration limit) {
        super(out);
        this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
        bounded(() -> out.write(b));
    }

    /** Writes in slices of at most {@link #SLICE} bytes, each under its own deadline. */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        for (int at = off, end = off + len; at < end; ) {
            int from = at;
            int n = Math.min(SLICE, end - at);
            bounded(() -> out.write(b, from, n));
            at += n;
        }
    }

    @Override
    public void flush() throws IOException {
        bounded(out::flush);
    }

    /**
     * Flushes and closes the stream under it, each under a deadline; after a failed flush the
     * connection is already gone, so the close cannot block.
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            flush();
        } catch (IOException e) {
            try {
                out.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        bounded(out::close);
    }

    private interface Write {
        void run() throws IOException;
    }

    private void bounded(Write write) throws IOException {
        arm();
        try {
            write.run();
        } catch (IOException e) {
            throw disarm() ? expired(e) : e;
        } catch (RuntimeException | Error e) {
            disarm();
            throw e;
        }
        if (disarm()) throw expired(null);
    }

    private void arm() throws IOException {
        synchronized (watch) {
            if (expired) throw expired(null);
            writer = Thread.currentThread();
            long write = ++writes;
            deadline = WATCHDOG.schedule(() -> expire(write), limit.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** Leaves the write; true when the deadline passed during it, its interrupt then cleared. */
    private boolean disarm() {
        boolean wasExpired;
        synchronized (watch) {
            if (deadline != null) deadline.cancel(false);
            deadline = null;
            writer = null;
            wasExpired = expired;
        }
        if (wasExpired) Thread.interrupted();
        return wasExpired;
    }

    private void expire(long write) {
        synchronized (watch) {
            Thread inWrite = writer;
            if (inWrite == null || expired || write != writes) return;
            expired = true;
            inWrite.interrupt();
        }
    }

    private IOException expired(@Nullable IOException cause) {
        return new IOException("the client took no bytes for " + spoken(limit) + "; closed the response", cause);
    }

    /** {@code limit} as a message says it: whole seconds as {@code 30 s}, anything finer in milliseconds. */
    static String spoken(Duration limit) {
        long millis = limit.toMillis();
        return millis % 1000 == 0 ? millis / 1000 + " s" : millis + " ms";
    }
}
