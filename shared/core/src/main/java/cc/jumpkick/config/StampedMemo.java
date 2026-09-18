// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * <strong>The</strong> staleness rule for jk's in-process config memos: one entry per key, the stamp
 * it was computed at stored <em>in the value</em>, and a recompute exactly when a fresh stamp
 * disagrees with the stored one.
 *
 * <p>Five readers hand-rolled this before it existed, with three different staleness stamps and no
 * shared eviction story, and {@code GlobalConfig} and {@code UserPlugins} memoized <em>the same
 * file</em> under two of them. The stamp being in the value rather than the key is the non-obvious
 * half and the reason the shape is worth owning: with the stamp in the key, every config rewrite
 * minted a new entry and nothing ever removed the old one, so the map grew for the life of the
 * process. Here a rewrite <em>replaces</em>, so the map is bounded by the number of distinct files a
 * process reads.
 *
 * <p><strong>The stamp type is the caller's, deliberately.</strong> {@code S} is compared with
 * {@link Object#equals}, so a reader keeps the staleness policy its file needs while sharing this
 * mechanism: {@link FileStamp} (size + mtime) for the user config, the file's own bytes for
 * {@code jk.toml}, where size+mtime is too coarse on Windows — a same-length edit inside one clock
 * tick served the previous parse. Typing the stamp rather than erasing it to {@code Object} is what
 * stops one memo from mixing two rules.
 *
 * <p><strong>Not {@code cc.jumpkick.host}.</strong> It is JDK-only and would meet that module's bar,
 * but every reader of it is a {@code :core} config reader, and {@code :host} lands on all sixteen
 * forked plugin workers and inside the native image. A type goes to the lowest module its
 * <em>readers</em> can reach, not the lowest module it <em>could</em> compile in. Nothing here is the
 * compile-output freshness question {@code BuildStamps} / {@code FreshnessStamp} answer either:
 * those are sentinel files on disk that outlive the process, this is a map that does not.
 *
 * <p>Thread-safe. A concurrent miss on the same key may compute twice; the last write wins and both
 * results are equal by construction, so no reader can observe the difference.
 *
 * @param <K> memo key — a path, or something derived from one
 * @param <S> staleness stamp, compared by {@link Object#equals}
 * @param <V> memoized value; {@code null} is a value, so "this file says nothing" is memoized too
 */
public final class StampedMemo<K, S, V extends @Nullable Object> {

    private final ConcurrentHashMap<K, Entry<S, V>> entries = new ConcurrentHashMap<>();

    /** {@code ConcurrentHashMap} rejects null values, so the entry wrapper carries the nullable one. */
    private record Entry<S, V extends @Nullable Object>(S stamp, V value) {}

    /**
     * Entries kept before the map is cleared, or {@link Integer#MAX_VALUE} for no bound.
     *
     * <p>Unbounded is right for the original readers: a config scalar or a parsed {@code jk.toml} is
     * small, and the map is bounded in practice by the number of distinct files a process reads. It is
     * <em>not</em> right for a memo whose values are large — a parsed {@code Lockfile} can be
     * megabytes, and the engine runs on a 256&nbsp;MB heap. Two readers hand-rolled this rule with
     * their own bounds rather than use this class, which is a fair sign the class was missing it.
     */
    private final int maxEntries;

    private StampedMemo(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** A fresh, empty memo with no entry bound. */
    public static <K, S, V extends @Nullable Object> StampedMemo<K, S, V> create() {
        return new StampedMemo<>(Integer.MAX_VALUE);
    }

    /**
     * A fresh memo that clears itself once it holds {@code maxEntries}.
     *
     * <p>Clear-on-overflow rather than eviction, deliberately: the readers that need a bound hold
     * values far larger than any bookkeeping an LRU would justify, and the cost of a clear is a
     * re-read. {@code FileHashMemo} ranks victims instead, because its entries are ~200 B and it is
     * the one memo where the eviction order is worth paying for.
     */
    public static <K, S, V extends @Nullable Object> StampedMemo<K, S, V> bounded(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        return new StampedMemo<>(maxEntries);
    }

    /**
     * The value for {@code key}, computed at {@code stamp}. A stored entry is served only when its
     * stamp {@link Object#equals equals} this one; otherwise {@code compute} runs and its result
     * replaces the entry.
     */
    public V get(K key, S stamp, Supplier<V> compute) {
        Entry<S, V> hit = entries.get(key);
        if (hit != null && hit.stamp().equals(stamp)) return hit.value();
        V fresh = compute.get();
        if (entries.size() >= maxEntries) entries.clear();
        entries.put(key, new Entry<>(stamp, fresh));
        return fresh;
    }

    /** Drop {@code key}'s entry, so the next {@link #get} recomputes whatever its stamp says. */
    public void forget(K key) {
        entries.remove(key);
    }

    /**
     * Drop every entry and say how many went: the idle trim's log line names what a long-lived
     * engine gave back, and tests that rewrite a config file inside one JVM start clean.
     */
    public int clear() {
        int dropped = entries.size();
        entries.clear();
        return dropped;
    }

    /**
     * Drop every entry whose key {@code keep} rejects and say how many went: the idle trim keeps the
     * workspace built last warm and returns the rest.
     */
    public int retain(Predicate<K> keep) {
        int dropped = 0;
        for (K key : entries.keySet()) {
            if (!keep.test(key) && entries.remove(key) != null) dropped++;
        }
        return dropped;
    }

    /** How many keys are memoized. */
    public int size() {
        return entries.size();
    }

    /**
     * A file's (size, mtime) staleness stamp — the rule for files jk only reads, where one stat is
     * cheaper than the parse it guards.
     *
     * <p>The mtime is kept as a {@link FileTime}, not truncated to milliseconds: every reader of
     * {@code ~/.jk/config.toml} shares this stamp, and a coarser one can only ever serve a stale
     * parse.
     */
    public record FileStamp(long size, FileTime modified) {

        /** The stamp for {@code file}, or {@code null} when it is null, absent, or cannot be stat'ed. */
        public static @Nullable FileStamp of(@Nullable Path file) {
            if (file == null) return null;
            try {
                // No exists() first: readAttributes throws NoSuchFileException for an absent file and
                // the catch below already answers null, so the guard was a second syscall answering a
                // question this one answers — 21.4 us instead of 11.1 on NTFS, on every jk.toml parse
                // . PathUtil.deleteTree shows the same shape.
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileStamp(attrs.size(), attrs.lastModifiedTime());
            } catch (IOException e) {
                return null;
            }
        }
    }
}
