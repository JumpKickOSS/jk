// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * {@link BufferedReader} with a max line length ({@link #DEFAULT_MAX_LINE}) and optional idle
 * timeout that closes the socket on stall.
 */
public final class BoundedLineReader extends BufferedReader {

    /** Generous for real traffic (large dep graphs, long diagnostics); fatal for runaway peers. */
    public static final int DEFAULT_MAX_LINE = 64 * 1024 * 1024;

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jk-protocol-idle-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final int maxLine;
    private final java.io.Closeable onTimeout;
    private final long idleTimeoutMillis;
    private volatile boolean timedOut;

    public BoundedLineReader(Reader in) {
        this(in, null, 0);
    }

    /** With an idle timeout: {@code onTimeout} (the socket/channel) is closed when a read stalls. */
    public BoundedLineReader(Reader in, java.io.Closeable onTimeout, long idleTimeoutMillis) {
        super(in);
        this.maxLine = DEFAULT_MAX_LINE;
        this.onTimeout = onTimeout;
        this.idleTimeoutMillis = idleTimeoutMillis;
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
    public String readLine() throws IOException {
        java.util.concurrent.ScheduledFuture<?> guard = null;
        if (onTimeout != null && idleTimeoutMillis > 0) {
            guard = WATCHDOG.schedule(
                    () -> {
                        timedOut = true;
                        try {
                            onTimeout.close();
                        } catch (IOException ignored) {
                        }
                    },
                    idleTimeoutMillis,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
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
                                + formatIdle(idleTimeoutMillis)
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
