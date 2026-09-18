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
import java.nio.file.attribute.FileTime;
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

    /** Creation time of that file; used when {@link #fileKey} is null (Windows). */
    private @Nullable FileTime created;

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

    /**
     * {@code true} while {@link #log} still names the file {@link #out} is open on.
     *
     * <p>Where the OS gives a file key this is exact. Windows gives none — {@code fileKey()} is
     * null for every file — so the fallback is creation time, and creation time cannot carry this
     * alone: NTFS hands a new file the creation time of the one that was renamed out of that name
     * in that directory moments before. Which is this scenario exactly, a successor's spawner
     * rotating the log aside and opening a fresh one at the same path. Measured on the reporting
     * host: the successor's log inherits the predecessor's creation time in 58% of runs, so the
     * check passed, the predecessor rolled a log it did not own, and the successor lost its file.
     *
     * <p>So the size has to agree too. This is only ever asked at the moment the sink has filled
     * its own file past {@link #capBytes}, and a successor's log is a freshly written header line;
     * a file below the cap is therefore not the file this sink is about to roll. The pair is not a
     * proof of identity, but the two failure directions are not equal — skipping a roll costs a
     * larger log, taking someone else's costs their log — so the doubt goes to not rolling.
     */
    private boolean stillOwnsPath() {
        try {
            BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class);
            return sameFile(fileKey, created, capBytes, attrs.fileKey(), attrs.creationTime(), attrs.size());
        } catch (IOException pathGone) {
            return false;
        }
    }

    /**
     * The decision itself, as a pure function of what was recorded at open and what the path shows
     * now — so the Windows fallback can be tested on a host that has file keys, rather than only
     * where NTFS happens to collide.
     */
    static boolean sameFile(
            @Nullable Object openedKey,
            @Nullable FileTime openedCreated,
            long capBytes,
            @Nullable Object pathKey,
            @Nullable FileTime pathCreated,
            long pathSize) {
        if (openedKey != null && pathKey != null) return openedKey.equals(pathKey);
        return openedCreated != null && openedCreated.equals(pathCreated) && pathSize >= capBytes;
    }

    private void open(boolean truncate) throws IOException {
        out = Files.newOutputStream(
                log,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                truncate ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
        BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class);
        fileKey = attrs.fileKey();
        created = attrs.creationTime();
        written = attrs.size();
    }
}
