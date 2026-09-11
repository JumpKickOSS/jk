// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import org.jspecify.annotations.Nullable;

/**
 * The engine log, written through a byte-counting sink that rolls the file at a size cap.
 *
 * <p>The spawner redirects this process's stderr to the log and rotates it only at spawn; a
 * resident engine that lives for weeks would otherwise grow the file without bound. The sink
 * holds its <em>own</em> append handle on the log (never the inherited descriptor), so a roll is
 * a rename of {@code <log>} to {@code <log>.1} — one generation kept — and a fresh file at the
 * same path. Bytes the JVM itself writes to the inherited descriptor (fatal-error notes) land in
 * whatever file that descriptor still names: after a roll, the previous generation.
 *
 * <p>A successor's spawner rotates the log under a draining predecessor. Before rolling, the sink
 * checks that the path still names the file it holds open and otherwise leaves the successor's
 * log alone, appending to its own renamed file instead.
 *
 * <p>Installed on {@link System#err} and {@link System#out} by {@link #install}. A logger shares the
 * cap by handing its handler this stream; there is one sink and one file.
 */
public final class EngineLogSink extends OutputStream {

    private final Path log;

    /** Roll threshold in bytes; {@code 0} = never roll. */
    private final long capBytes;

    private final Clock clock;

    private OutputStream out = OutputStream.nullOutputStream();

    /** Filesystem identity of the file {@link #out} was opened on; {@code null} where the OS has none. */
    private @Nullable Object fileKey;

    /** Bytes in the current file: its size when opened plus everything written since. */
    private long written;

    private volatile long lastRolledAtMillis = -1;
    private volatile int rolls;

    EngineLogSink(Path log, long capBytes, Clock clock) throws IOException {
        this.log = log;
        this.capBytes = capBytes;
        this.clock = clock;
        open(false);
    }

    /**
     * Open {@code log} for append, capped at {@code capBytes} ({@code 0} = no cap), and route
     * {@code System.err} and {@code System.out} through it. Throws when the log cannot be opened;
     * the caller then keeps the inherited streams.
     */
    public static EngineLogSink install(Path log, long capBytes) throws IOException {
        EngineLogSink sink = new EngineLogSink(log, capBytes, Clock.SYSTEM);
        PrintStream stream = new PrintStream(sink, true, StandardCharsets.UTF_8);
        System.setErr(stream);
        System.setOut(stream);
        return sink;
    }

    /** The log file this sink writes. */
    public Path path() {
        return log;
    }

    /** Epoch millis of the last roll, {@code -1} when this process has not rolled yet. */
    public long lastRolledAtMillis() {
        return lastRolledAtMillis;
    }

    /** Rolls performed by this process. */
    public int rolls() {
        return rolls;
    }

    /** Size of {@code log} on disk, {@code -1} when it is missing or unreadable. */
    public static long sizeOf(Path log) {
        try {
            return Files.size(log);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public synchronized void write(int b) throws IOException {
        out.write(b);
        written++;
        rollIfDue();
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        written += len;
        rollIfDue();
    }

    @Override
    public synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }

    private void rollIfDue() throws IOException {
        if (capBytes <= 0 || written < capBytes) return;
        written = 0;
        if (!stillOwnsPath()) return;
        out.flush();
        out.close();
        // Until the fresh file is open, drop rather than throw: a failed reopen must not turn
        // every later log line into an exception on the caller's thread.
        out = OutputStream.nullOutputStream();
        Path previous = log.resolveSibling(log.getFileName() + ".1");
        try {
            Files.move(log, previous, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException renameRefused) {
            // The inherited descriptor pins the name on platforms that refuse to rename an open
            // file; copying the generation aside and truncating in place keeps the same outcome.
            Files.copy(log, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        open(true);
        lastRolledAtMillis = clock.millis();
        rolls++;
    }

    /** {@code true} while {@link #log} still names the file {@link #out} is open on. */
    private boolean stillOwnsPath() {
        if (fileKey == null) return true;
        try {
            return fileKey.equals(
                    Files.readAttributes(log, BasicFileAttributes.class).fileKey());
        } catch (IOException pathGone) {
            return false;
        }
    }

    private void open(boolean truncate) throws IOException {
        out = Files.newOutputStream(
                log,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
        BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class);
        fileKey = attrs.fileKey();
        written = attrs.size();
    }
}
