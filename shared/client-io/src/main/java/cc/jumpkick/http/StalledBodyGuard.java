// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import cc.jumpkick.host.time.Clock;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A streamed response body whose read fails once no bytes have arrived for {@link #NO_BYTES}. The
 * request timeout ends at the response headers, and the JDK client's body stream has no read
 * timeout of its own, so a server that stops sending mid-body would hold the reader (and its host
 * permit) forever. A watchdog interrupts the reading thread instead; the read then throws an
 * {@link IOException} naming the URL. Time the caller spends between reads does not count.
 */
final class StalledBodyGuard extends FilterInputStream {

    /** How long one read may wait for its first byte. */
    static final Duration NO_BYTES = Duration.ofSeconds(60);

    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    /** One daemon thread shared by every body; a cancelled check leaves the queue at once. */
    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "jk-http-body-watchdog");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final URI uri;
    private final Duration limit;

    private final Object watch = new Object();
    /** The thread inside a read, or null between reads. */
    private @Nullable Thread reader;

    private long deadlineNanos;
    private boolean stalled;
    private @Nullable ScheduledFuture<?> check;

    StalledBodyGuard(InputStream in, URI uri, Duration limit) {
        super(in);
        this.uri = uri;
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        begin();
        int b;
        try {
            b = in.read();
        } catch (IOException e) {
            throw end() ? stall(e) : e;
        } catch (RuntimeException | Error e) {
            end();
            throw e;
        }
        if (end()) throw stall(null);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        begin();
        int n;
        try {
            n = in.read(b, off, len);
        } catch (IOException e) {
            throw end() ? stall(e) : e;
        } catch (RuntimeException | Error e) {
            end();
            throw e;
        }
        if (end()) throw stall(null);
        return n;
    }

    @Override
    public void close() throws IOException {
        synchronized (watch) {
            if (check != null) check.cancel(false);
            check = null;
        }
        super.close();
    }

    private void begin() throws IOException {
        synchronized (watch) {
            if (stalled) throw stall(null);
            reader = Thread.currentThread();
            deadlineNanos = Clock.SYSTEM.nanos() + limit.toNanos();
            if (check == null) check = WATCHDOG.schedule(this::check, limit.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /** Leaves the read; true when the watchdog stalled it, its interrupt then cleared. */
    private boolean end() {
        boolean wasStalled;
        synchronized (watch) {
            reader = null;
            wasStalled = stalled;
        }
        if (wasStalled) Thread.interrupted();
        return wasStalled;
    }

    /** The watchdog: stalls a read past its deadline, or looks again at the deadline it has. */
    private void check() {
        synchronized (watch) {
            check = null;
            Thread inRead = reader;
            if (inRead == null || stalled) return;
            long left = deadlineNanos - Clock.SYSTEM.nanos();
            if (left > 0) {
                check = WATCHDOG.schedule(this::check, left, TimeUnit.NANOSECONDS);
                return;
            }
            stalled = true;
            inRead.interrupt();
        }
    }

    private IOException stall(@Nullable IOException cause) {
        return new IOException(
                "no bytes from " + SafeUri.forMessage(uri) + " for " + Http.spoken(limit) + "; stopped reading", cause);
    }
}
