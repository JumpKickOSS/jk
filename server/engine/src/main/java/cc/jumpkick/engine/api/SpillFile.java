// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import cc.jumpkick.host.PathUtil;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The lines one client stream holds on disk while its client reads slower than its job emits: one
 * line per JSONL line, appended by producers and read back in order by the stream's writer thread.
 * Named {@code stream-<pid>-<n>.jsonl}, so a later engine can sweep what a dead one left behind.
 * Not thread-safe: the stream appends under its monitor and only its writer thread reads.
 */
@NullMarked
final class SpillFile implements AutoCloseable {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicBoolean SWEPT = new AtomicBoolean();

    private final Path path;
    private final BufferedWriter out;
    private @Nullable BufferedReader in;

    private SpillFile(Path path) throws IOException {
        this.path = path;
        this.out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    /** A new, empty spill file under {@code dir}; the first one an engine opens sweeps dead engines' files. */
    static SpillFile open(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (SWEPT.compareAndSet(false, true)) sweepDead(dir);
        long pid = ProcessHandle.current().pid();
        return new SpillFile(dir.resolve("stream-" + pid + "-" + SEQ.incrementAndGet() + ".jsonl"));
    }

    /** Append one line; it is on disk, readable by {@link #next}, when this returns. */
    void append(String line) throws IOException {
        out.write(line);
        out.write('\n');
        out.flush();
    }

    /** The next line appended and not read yet. The caller knows one is there. */
    String next() throws IOException {
        BufferedReader reader = in;
        if (reader == null) in = reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        String line = reader.readLine();
        if (line == null) throw new IOException("spill file " + path + " ended before its lines did");
        return line;
    }

    Path path() {
        return path;
    }

    /** Close and delete the file. */
    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // deleted below either way
        }
        BufferedReader reader = in;
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // deleted below either way
            }
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the next engine's sweep removes it
        }
    }

    /** Delete the spill files of engines that are no longer running. */
    private static void sweepDead(Path dir) {
        try {
            PathUtil.forEachChild(dir, (file, attrs) -> {
                String name = file.getFileName().toString();
                int dash = name.indexOf('-', "stream-".length());
                if (!name.startsWith("stream-") || !name.endsWith(".jsonl") || dash < 0) return true;
                try {
                    long pid = Long.parseLong(name.substring("stream-".length(), dash));
                    if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) Files.deleteIfExists(file);
                } catch (NumberFormatException | IOException ignored) {
                    // not ours, or already gone
                }
                return true;
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
