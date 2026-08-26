// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The {@code System.out}/{@code System.err} half of the live region's process output: while a region
 * is up, the JVM's standard streams are re-pointed at a line-assembling sink so a compiler or test
 * worker writing to stdout lands <em>above</em> the region instead of shredding it. {@link
 * OutputWindow} owns what happens to those lines afterwards; this owns the swap and the byte buffer.
 *
 * <p>Invariant: <b>the streams are always given back, exactly once</b>. {@link #start} records the
 * real streams before replacing them and {@link #restore} puts those two references back and flushes
 * the trailing partial line, under one flag, so the double-restore that a Ctrl-C settle racing a
 * try-with-resources close produces is a no-op rather than a stdout pointed at a dead sink. That is
 * why the saved streams, the flag and the sink are one object: a caller that could see {@code
 * capturing == false} while {@code savedOut} still holds the real stream can strand the console.
 *
 * <p>The flush is deliberately outside the lock — it re-enters the line consumer, which takes the
 * live region's lock.
 */
@NullMarked
final class OutputCapture {

    private final Object lock = new Object();

    /** Where a completed line goes; re-enters the live region, so never called under {@link #lock}. */
    private final Consumer<String> lines;

    private @Nullable PrintStream savedOut;
    private @Nullable PrintStream savedErr;

    /** Volatile: the animator thread polls it for the stale-partial-line flush. */
    private volatile @Nullable LineSink sink;

    private boolean capturing;

    OutputCapture(Consumer<String> lines) {
        this.lines = lines;
    }

    /**
     * Redirect the standard streams into this capture. Returns false when a capture is already
     * installed, so the second caller gets an inert scope rather than the power to restore the
     * first caller's streams.
     */
    boolean start() {
        synchronized (lock) {
            if (capturing) return false;
            savedOut = System.out;
            savedErr = System.err;
            LineSink fresh = new LineSink(lines);
            sink = fresh;
            PrintStream redirect = new PrintStream(fresh, true, StandardCharsets.UTF_8);
            System.setOut(redirect);
            System.setErr(redirect);
            capturing = true;
            return true;
        }
    }

    /**
     * Put the real {@code System.out}/{@code System.err} back and flush any trailing partial line.
     * Idempotent — called by the scope a caller closes, and defensively when the region settles so a
     * Ctrl-C mid-plan hands the streams back before {@link GlobalCancel} prints.
     */
    void restore() {
        LineSink toFlush;
        synchronized (lock) {
            if (!capturing) return;
            capturing = false;
            if (savedOut != null) System.setOut(savedOut);
            if (savedErr != null) System.setErr(savedErr);
            toFlush = sink;
        }
        if (toFlush != null) toFlush.flushPartial(); // re-enters the region's lock
    }

    /**
     * Flush a buffered partial line that has not grown for {@code ms}, so output without a trailing
     * newline still appears in a timely manner instead of stalling. Called from the animator thread
     * outside the region lock — the order must match a step write (sink then lock).
     */
    void flushStale(long ms) {
        LineSink s = sink;
        if (s != null) s.maybeFlushStale(ms);
    }

    /** Buffers redirected bytes and forwards each completed line to the consumer. */
    private static final class LineSink extends OutputStream {

        private final Consumer<String> lines;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private long lastWriteNanos; // when the current partial line last grew

        LineSink(Consumer<String> lines) {
            this.lines = lines;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                buf.write(b);
                lastWriteNanos = System.nanoTime();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int start = off;
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    buf.write(b, start, i - start);
                    emit();
                    start = i + 1;
                }
            }
            if (start < off + len) {
                buf.write(b, start, off + len - start);
                lastWriteNanos = System.nanoTime();
            }
        }

        synchronized void maybeFlushStale(long ms) {
            if (buf.size() == 0) return;
            if (System.nanoTime() - lastWriteNanos < ms * 1_000_000L) return;
            emit();
        }

        synchronized void flushPartial() {
            if (buf.size() > 0) emit();
        }

        private void emit() {
            String s = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            if (s.isBlank()) return; // do not inject empty lines into the peek / settle layout
            lines.accept(s);
        }
    }
}
