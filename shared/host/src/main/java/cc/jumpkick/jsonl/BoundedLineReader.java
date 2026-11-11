// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import cc.jumpkick.host.time.Clock;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@link BufferedReader} with a max line length ({@link #DEFAULT_MAX_LINE}, or one sized to the
 * heap — {@link #maxLineForHeap}) and optional idle timeout that closes the socket on stall. The
 * timeout can be changed between reads, so one connection may start strict and relax once its
 * peer has spoken.
 *
 * <p>Reads fill a private buffer in blocks and scan it for the line end, so a line costs one lock,
 * not one per char. The idle bound is one watchdog per reader: each {@link #readLine} moves its
 * deadline, and the watchdog is scheduled again only when it fires before the deadline or a
 * shorter bound needs it sooner, never once per line.
 */
public final class BoundedLineReader extends BufferedReader {

    /** Generous for real traffic (large dep graphs, long diagnostics); fatal for runaway peers. */
    public static final int DEFAULT_MAX_LINE = 64 * 1024 * 1024;

    /** The least a heap-sized line bound comes to; a protocol line is never this long. */
    static final int MIN_HEAP_MAX_LINE = 4 * 1024 * 1024;

    /** Default gap between protocol lines before a stream is declared dead: 60 minutes. */
    public static final long DEFAULT_STREAM_IDLE_MS = 60L * 60_000L;

    private static final int BUFFER_CHARS = 8192;

    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    /** One daemon thread shared by every reader; a cancelled watchdog leaves the queue at once. */
    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "jk-protocol-idle-watchdog");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final Reader source;
    private final char[] buf = new char[BUFFER_CHARS];
    private int pos;
    private int limit;
    /** The last line ended on {@code \r}; a {@code \n} right after it belongs to that line. */
    private boolean skipLf;

    private final int maxLine;
    private final @Nullable Closeable onTimeout;
    private volatile long idleTimeoutMillis;
    private volatile boolean timedOut;

    private final Object watch = new Object();
    private boolean reading;
    private long deadlineNanos;
    private @Nullable ScheduledFuture<?> guard;
    private long guardAtNanos;
    private long guardSchedules;

    public BoundedLineReader(Reader in) {
        this(in, null, 0);
    }

    /**
     * With an idle timeout: {@code onTimeout} (the socket/channel) is closed when a read stalls
     * for {@code idleTimeoutMillis}; {@code 0} (or a null {@code onTimeout}) disables the timer.
     */
    public BoundedLineReader(Reader in, @Nullable Closeable onTimeout, long idleTimeoutMillis) {
        this(in, onTimeout, idleTimeoutMillis, DEFAULT_MAX_LINE);
    }

    /** As above, with the line bound {@code maxLine} chars instead of {@link #DEFAULT_MAX_LINE}. */
    public BoundedLineReader(Reader in, @Nullable Closeable onTimeout, long idleTimeoutMillis, int maxLine) {
        super(in, 1);
        this.source = in;
        this.maxLine = maxLine;
        this.onTimeout = onTimeout;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    /**
     * A line bound a process with {@code maxHeapBytes} of heap can hold: an eighth of the heap, at
     * least {@value #MIN_HEAP_MAX_LINE} chars and at most {@link #DEFAULT_MAX_LINE}. The buffer for
     * a line that long peaks near three times its length while it grows, so a bound at the default
     * on a heap of 128 MiB — the native client's — would exhaust the heap before it fired; this one
     * fires first and names the peer.
     */
    public static int maxLineForHeap(long maxHeapBytes) {
        if (maxHeapBytes <= 0 || maxHeapBytes == Long.MAX_VALUE) return DEFAULT_MAX_LINE;
        long eighth = maxHeapBytes / 8;
        return (int) Math.max(MIN_HEAP_MAX_LINE, Math.min(DEFAULT_MAX_LINE, eighth));
    }

    /** The longest line this reader buffers before it fails the read, in chars. */
    public int maxLine() {
        return maxLine;
    }

    /** Change the idle bound for the reads that follow; {@code 0} disables it. */
    public void idleTimeout(long millis) {
        this.idleTimeoutMillis = millis;
    }

    /** {@code true} once the idle timer has closed the peer; the failed read threw the idle error. */
    public boolean timedOut() {
        return timedOut;
    }

    /**
     * The stream-idle bound both ends of the wire use: {@code JK_STREAM_IDLE_MS} (milliseconds,
     * preferred) or {@code JK_STREAM_IDLE_MINUTES}; unset or malformed falls back to {@link
     * #DEFAULT_STREAM_IDLE_MS}. {@code 0} disables the bound.
     */
    public static long streamIdleMillis(Function<String, @Nullable String> env) {
        String ms = env.apply("JK_STREAM_IDLE_MS");
        if (ms != null && !ms.isBlank()) {
            try {
                return Long.parseLong(ms.trim());
            } catch (NumberFormatException malformed) {
                return DEFAULT_STREAM_IDLE_MS;
            }
        }
        String minutes = env.apply("JK_STREAM_IDLE_MINUTES");
        if (minutes != null && !minutes.isBlank()) {
            try {
                return Long.parseLong(minutes.trim()) * 60_000L;
            } catch (NumberFormatException malformed) {
                return DEFAULT_STREAM_IDLE_MS;
            }
        }
        return DEFAULT_STREAM_IDLE_MS;
    }

    /** Human duration for idle-timeout errors (seconds under a minute, else minutes). */
    static String formatIdle(long idleTimeoutMillis) {
        if (idleTimeoutMillis < 60_000L) {
            long sec = Math.max(1L, idleTimeoutMillis / 1_000L);
            return sec + "s";
        }
        return (idleTimeoutMillis / 60_000L) + " minutes";
    }

    /** Test seam: how many times this reader has scheduled its watchdog. */
    long watchdogSchedules() {
        synchronized (watch) {
            return guardSchedules;
        }
    }

    @Override
    public @Nullable String readLine() throws IOException {
        long idle = idleTimeoutMillis;
        boolean bounded = onTimeout != null && idle > 0;
        if (bounded) armWatchdog(idle);
        try {
            synchronized (lock) {
                return scanLine();
            }
        } catch (IOException e) {
            if (timedOut) {
                throw new IOException(
                        "no answer from the engine for " + formatIdle(idle) + "; try jk engine stop -n", e);
            }
            throw e;
        } finally {
            if (bounded) disarmWatchdog();
        }
    }

    /** One line without its terminator ({@code \n}, {@code \r} or {@code \r\n}); null at end of input. */
    private @Nullable String scanLine() throws IOException {
        StringBuilder line = null;
        while (true) {
            if (pos >= limit && !fill()) return line == null ? null : line.toString();
            if (skipLf) {
                skipLf = false;
                if (buf[pos] == '\n') {
                    pos++;
                    continue;
                }
            }
            int start = pos;
            int end = start;
            while (end < limit && buf[end] != '\n' && buf[end] != '\r') end++;
            int had = line == null ? 0 : line.length();
            if (had + (end - start) > maxLine) {
                pos = end;
                throw new IOException("protocol line longer than " + maxLine + " chars; stopped reading");
            }
            if (end < limit) {
                skipLf = buf[end] == '\r';
                pos = end + 1;
                if (line == null) return new String(buf, start, end - start);
                return line.append(buf, start, end - start).toString();
            }
            if (line == null) line = new StringBuilder(Math.max(128, 2 * (end - start)));
            line.append(buf, start, end - start);
            pos = end;
        }
    }

    /** Refills the buffer from the wrapped reader; {@code false} at end of input. */
    private boolean fill() throws IOException {
        int n;
        do {
            n = source.read(buf, 0, buf.length);
        } while (n == 0);
        if (n < 0) return false;
        pos = 0;
        limit = n;
        return true;
    }

    /** Drops a {@code \n} owed to a line that ended on {@code \r}; {@code false} at end of input. */
    private boolean readyToRead() throws IOException {
        if (pos >= limit && !fill()) return false;
        if (skipLf) {
            skipLf = false;
            if (buf[pos] == '\n') {
                pos++;
                return pos < limit || fill();
            }
        }
        return true;
    }

    @Override
    public int read() throws IOException {
        synchronized (lock) {
            if (!readyToRead()) return -1;
            return buf[pos++];
        }
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, cbuf.length);
        if (len == 0) return 0;
        synchronized (lock) {
            if (!readyToRead()) return -1;
            int n = Math.min(len, limit - pos);
            System.arraycopy(buf, pos, cbuf, off, n);
            pos += n;
            return n;
        }
    }

    @Override
    public long skip(long n) throws IOException {
        if (n < 0) throw new IllegalArgumentException("skip value is negative");
        long skipped = 0;
        synchronized (lock) {
            while (skipped < n && readyToRead()) {
                int step = (int) Math.min(n - skipped, limit - pos);
                pos += step;
                skipped += step;
            }
        }
        return skipped;
    }

    @Override
    public boolean ready() throws IOException {
        synchronized (lock) {
            boolean onlyOwedLf = skipLf && pos + 1 == limit && buf[pos] == '\n';
            if (pos < limit && !onlyOwedLf) return true;
            return source.ready();
        }
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(int readAheadLimit) throws IOException {
        throw new IOException("mark() not supported");
    }

    @Override
    public void reset() throws IOException {
        throw new IOException("reset() not supported");
    }

    @Override
    public void close() throws IOException {
        synchronized (watch) {
            if (guard != null) guard.cancel(false);
            guard = null;
        }
        synchronized (lock) {
            source.close();
        }
    }

    /** Moves the deadline to {@code idle} from now; schedules the watchdog only if none is due by then. */
    private void armWatchdog(long idle) {
        synchronized (watch) {
            reading = true;
            deadlineNanos = Clock.SYSTEM.nanos() + TimeUnit.MILLISECONDS.toNanos(idle);
            if (guard != null) {
                if (guardAtNanos - deadlineNanos <= 0) return;
                guard.cancel(false);
            }
            scheduleWatchdog(deadlineNanos);
        }
    }

    private void disarmWatchdog() {
        synchronized (watch) {
            reading = false;
        }
    }

    private void scheduleWatchdog(long atNanos) {
        guardAtNanos = atNanos;
        guardSchedules++;
        guard = WATCHDOG.schedule(
                this::watchdogFired, Math.max(0, atNanos - Clock.SYSTEM.nanos()), TimeUnit.NANOSECONDS);
    }

    /** A read past its deadline closes the peer; one with time left re-arms; no read ends the watch. */
    private void watchdogFired() {
        Closeable peer;
        synchronized (watch) {
            guard = null;
            if (!reading) return;
            if (deadlineNanos - Clock.SYSTEM.nanos() > 0) {
                scheduleWatchdog(deadlineNanos);
                return;
            }
            timedOut = true;
            peer = onTimeout;
        }
        if (peer == null) return;
        try {
            peer.close();
        } catch (IOException ignored) {
        }
    }
}
