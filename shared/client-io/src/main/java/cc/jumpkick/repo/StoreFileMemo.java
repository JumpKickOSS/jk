// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * What one store file says, read once per change: keyed by path and served while the file's stat
 * identity (size, mtime, file key) still matches. The store replaces a file by rename, so a
 * rewritten file carries a new identity and is read again. Values must be immutable; the memo is
 * an {@link LruMemo} bounded by their weight.
 */
public final class StoreFileMemo<V> {

    /** Reads and decodes one file. */
    @FunctionalInterface
    public interface Reader<V> {
        V read(Path file) throws IOException;
    }

    private record Stamp(
            long size, long mtimeNanos, @Nullable Object fileKey) {
        static Stamp of(BasicFileAttributes attrs) {
            return new Stamp(attrs.size(), attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS), attrs.fileKey());
        }
    }

    private record Held<V>(Stamp stamp, V value) {}

    private final LruMemo<Path, Held<V>> entries;
    private final LongAdder reads = new LongAdder();

    public StoreFileMemo(long maxWeight, ToLongFunction<V> weigher) {
        Objects.requireNonNull(weigher, "weigher");
        this.entries = new LruMemo<>(maxWeight, held -> weigher.applyAsLong(held.value()));
    }

    /** {@code file} through {@code reader}, or from the memo while unchanged; empty when the file is absent. */
    public Optional<V> get(Path file, Reader<V> reader) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        Stamp stamp;
        try {
            stamp = Stamp.of(Files.readAttributes(key, BasicFileAttributes.class));
        } catch (NoSuchFileException absent) {
            entries.remove(key);
            return Optional.empty();
        }
        Held<V> held = entries.get(key);
        if (held != null && held.stamp().equals(stamp)) return Optional.of(held.value());
        V value = reader.read(key);
        reads.increment();
        entries.put(key, new Held<>(stamp, value));
        return Optional.of(value);
    }

    /** How many times a reader ran: the seam that proves a second ask did not touch the file. */
    public long reads() {
        return reads.sum();
    }

    /** Drop every entry and return how many went. */
    public int clear() {
        return entries.clear();
    }
}
