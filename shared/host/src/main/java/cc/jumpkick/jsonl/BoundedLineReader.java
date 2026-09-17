// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
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
 */
public final class BoundedLineReader extends BufferedReader {

    /** Generous for real traffic (large dep graphs, long diagnostics); fatal for runaway peers. */
    public static final int DEFAULT_MAX_LINE = 64 * 1024 * 1024;

    /** The least a heap-sized line bound comes to; a protocol line is never this long. */
    static final int MIN_HEAP_MAX_LINE = 4 * 1024 * 1024;

    /** Default gap between protocol lines before a stream is declared dead: 60 minutes. */
    public static final long DEFAULT_STREAM_IDLE_MS = 60L * 60_000L;

    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    /**
     * One daemon thread whose cancelled tasks leave the queue immediately. Every {@link #readLine}
     * under a live bound schedules a guard and cancels it once the line arrives; a cancelled task
     * that stayed queued until its delay passed would pin one task and its captured peer per line
     * for the length of the idle window, and the CLI reads every streamed event line under a
     * 60-minute one.
     */
    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "jk-protocol-idle-watchdog");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Test seam: guards still queued, cancelled or not. */
    static int pendingWatchdogs() {
        return WATCHDOG.getQueue().size();
    }

    private final int maxLine;
    private final @Nullable Closeable onTimeout;
    private volatile long idleTimeoutMillis;
    private volatile boolean timedOut;

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
        super(in);
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

    @Override
    public @Nullable String readLine() throws IOException {
        ScheduledFuture<?> guard = null;
        Closeable peer = onTimeout;
        long idle = idleTimeoutMillis;
        if (peer != null && idle > 0) {
            guard = WATCHDOG.schedule(
                    () -> {
                        timedOut = true;
                        try {
                            peer.close();
                        } catch (IOException ignored) {
                        }
                    },
                    idle,
                    TimeUnit.MILLISECONDS);
        }
        try {
            StringBuilder line = new StringBuilder(128);
            boolean sawAny = false;
            while (true) {
                int r = read();
                if (r < 0) return sawAny ? line.toString() : null;
                sawAny = true;
                char c = (char) r;
                if (c == '\n') return line.toString();
                if (c == '\r') {
                    // \r or \r\n both terminate; peek-consume a following \n.
                    mark(1);
                    int n = read();
                    if (n >= 0 && n != '\n') reset();
                    return line.toString();
                }
                line.append(c);
                if (line.length() > maxLine) {
                    throw new IOException("protocol line exceeds " + maxLine + " chars without a terminator"
                            + " — refusing to buffer further (malformed or hostile peer)");
                }
            }
        } catch (IOException e) {
            if (timedOut) {
                throw new IOException(
                        "no protocol traffic for "
                                + formatIdle(idle)
                                + " — the engine looks dead (set JK_STREAM_IDLE_MS to tune;"
                                + " try `jk engine stop --force`)",
                        e);
            }
            throw e;
        } finally {
            if (guard != null) guard.cancel(false);
        }
    }
}
