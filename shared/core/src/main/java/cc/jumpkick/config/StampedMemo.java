// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ConcurrentHashMap;
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
public final class StampedMemo<K, S, V> {

    private final ConcurrentHashMap<K, Entry<S, V>> entries = new ConcurrentHashMap<>();

    /** {@code ConcurrentHashMap} rejects null values, so the entry wrapper carries the nullable one. */
    private record Entry<S, V>(S stamp, @Nullable V value) {}

    private StampedMemo() {}

    /** A fresh, empty memo. */
    public static <K, S, V> StampedMemo<K, S, V> create() {
        return new StampedMemo<>();
    }

    /**
     * The value for {@code key}, computed at {@code stamp}. A stored entry is served only when its
     * stamp {@link Object#equals equals} this one; otherwise {@code compute} runs and its result
     * replaces the entry.
     */
    public @Nullable V get(K key, S stamp, Supplier<V> compute) {
        Entry<S, V> hit = entries.get(key);
        if (hit != null && hit.stamp().equals(stamp)) return hit.value();
        V fresh = compute.get();
        entries.put(key, new Entry<>(stamp, fresh));
        return fresh;
    }

    /** Drop {@code key}'s entry, so the next {@link #get} recomputes whatever its stamp says. */
    public void forget(K key) {
        entries.remove(key);
    }

    /** Drop every entry. For tests that rewrite a config file inside one JVM. */
    public void clear() {
        entries.clear();
    }

    /** How many keys are memoized. */
    public int size() {
        return entries.size();
    }

    /**
     * A file's (size, mtime) staleness stamp — the rule for files jk only reads, where one stat is
     * cheaper than the parse it guards.
     *
     * <p>The mtime is kept as a {@link FileTime}, not truncated to milliseconds. Two readers used to
     * stamp {@code ~/.config/jk/config.toml} independently, one at each resolution; the finer one is
     * the safe merge, because a coarser stamp can only ever serve a stale parse.
     */
    public record FileStamp(long size, FileTime modified) {

        /** The stamp for {@code file}, or {@code null} when it is null, absent, or cannot be stat'ed. */
        public static @Nullable FileStamp of(@Nullable Path file) {
            if (file == null) return null;
            try {
                // No exists() first: readAttributes throws NoSuchFileException for an absent file and
                // the catch below already answers null, so the guard was a second syscall answering a
                // question this one answers — 21.4 us instead of 11.1 on NTFS, on every jk.toml parse
                // (JK-1033). PathUtil.deleteTree shows the same shape.
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileStamp(attrs.size(), attrs.lastModifiedTime());
            } catch (IOException e) {
                return null;
            }
        }
    }
}
