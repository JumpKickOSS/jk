// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The file behind an in-memory memo: one record line per key, appended as keys change, read back
 * last-line-wins. A flush writes the keys put since the last one, not the map, so a build that
 * changed three entries writes three lines however large the memo has grown.
 *
 * <p>The file is rewritten whole only to compact it: when it holds more than twice as many lines
 * as the map has entries (plus {@link #COMPACT_SLACK}), after entries were dropped, after a flush
 * failed, and when a load found a final line with no newline. An append is one write under
 * {@code O_APPEND}, so a crash or a full disk can only cut the last line short; that line is never
 * read as a record, and the next flush starts the file again rather than appending onto it. Two
 * engines on one cache root interleave whole lines; a rewrite by one can drop the other's latest
 * appends, which costs those entries one recomputation, as a lost write always has.
 */
final class MemoLog {

    /** Lines a log may carry beyond twice its live entries before a flush compacts it. */
    static final int COMPACT_SLACK = 1_024;

    private final Path file;
    /** Keys put since the last flush: what the next append writes. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** The next flush rewrites the file whole rather than appending to it. */
    private volatile boolean compact;
    /** Lines the file holds, live or superseded; ranks it for compaction. */
    private long linesOnDisk;

    MemoLog(Path file) {
        this.file = file;
    }

    /**
     * Hand every complete line of the file to {@code decode}, oldest first. A missing file reads as
     * empty; an unreadable one throws for the caller to fail open on.
     */
    void load(Consumer<String> decode) throws IOException {
        if (!Files.isRegularFile(file)) return;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder(256);
            int ch;
            while ((ch = reader.read()) >= 0) {
                if (ch == '\n') {
                    if (line.length() > 0) {
                        linesOnDisk++;
                        decode.accept(line.toString());
                        line.setLength(0);
                    }
                } else {
                    line.append((char) ch);
                }
            }
            if (line.length() > 0) compact = true;
        }
    }

    /** {@code key}'s entry changed: the next flush writes it. */
    void changed(String key) {
        pending.add(key);
    }

    /** Entries left the map without a line saying so: the next flush rewrites the file. */
    void dropped() {
        compact = true;
    }

    /**
     * Persist what changed. {@code encode} renders one entry as its line without the newline, or
     * {@code null} for an entry the format cannot carry (it stays in memory, unwritten). On a
     * failure the next flush rewrites the file whole, since what this one missed is no longer pending.
     */
    synchronized <V> void flush(Map<String, V> entries, BiFunction<String, V, @Nullable String> encode)
            throws IOException {
        List<String> changed = new ArrayList<>(pending);
        try {
            if (compact || !Files.isRegularFile(file) || linesOnDisk > 2L * entries.size() + COMPACT_SLACK) {
                pending.clear();
                rewrite(entries, encode);
            } else {
                append(changed, entries, encode);
            }
        } catch (IOException | RuntimeException e) {
            compact = true;
            throw e;
        }
    }

    private <V> void rewrite(Map<String, V> entries, BiFunction<String, V, @Nullable String> encode)
            throws IOException {
        StringBuilder sb = new StringBuilder(entries.size() * 128);
        long lines = 0;
        for (Map.Entry<String, V> e : entries.entrySet()) {
            String line = encode.apply(e.getKey(), e.getValue());
            if (line == null) continue;
            sb.append(line).append('\n');
            lines++;
        }
        AtomicWrites.replace(file, sb.toString());
        linesOnDisk = lines;
        compact = false;
    }

    private <V> void append(List<String> keys, Map<String, V> entries, BiFunction<String, V, @Nullable String> encode)
            throws IOException {
        StringBuilder sb = new StringBuilder(keys.size() * 128);
        long lines = 0;
        for (String key : keys) {
            pending.remove(key);
            V v = entries.get(key);
            String line = v == null ? null : encode.apply(key, v);
            if (line == null) continue;
            sb.append(line).append('\n');
            lines++;
        }
        if (lines == 0) return;
        ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (buf.hasRemaining()) ch.write(buf);
        }
        linesOnDisk += lines;
    }

    /** Test seam: lines the file is known to hold. */
    long linesOnDisk() {
        return linesOnDisk;
    }
}
