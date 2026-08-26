// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Content fingerprints for source and input files, memoized on {@code (path, size, mtime)}.
 *
 * <p>One store per cache root, held in memory for the life of the engine and persisted as a single
 * file at {@code <cache>/hash-memo/memo.v1}. A hit costs one map read; the caller's own stat is the
 * only filesystem call on the hot path. An entry per path — rather than a file per path — is what
 * makes the memo pay on every platform: creating and opening small files costs an order of
 * magnitude more on NTFS than on ext4, enough that a file-backed entry cost more to consult than
 * the content hash it was there to avoid.
 *
 * <p>The map is keyed by path alone, so a rebuild replaces an entry rather than adding one and the
 * store cannot grow with the number of builds. {@link #MAX_ENTRIES} bounds it against a workspace
 * large enough to reach it; victims are the least recently used, which is the only honest ranking
 * here because an entry's own timestamps measure the churn of the file it describes, not use.
 *
 * <p>Trust is provenance-based. {@link #rememberContent} seeds a <em>known</em> digest (a CAS blob
 * just restored) and records the nanosecond mtime it was seeded at; such an entry is trusted
 * immediately, but only while that stamp still matches — an in-place rewrite landing in the same
 * millisecond tick, which compilers do to restored class files, moves the nanoseconds and voids the
 * seed. A self-hash carries no stamp and is instead gated on {@link #SETTLE_MS}, so a same-size
 * rewrite inside one mtime tick cannot reuse a stale digest.
 *
 * <p>Every I/O failure fails open: the caller re-hashes. Nothing here is load-bearing, which is also
 * why a concurrent engine writing the same store is last-writer-wins rather than locked — a lost
 * entry costs one re-hash. For the same reason the store survives a {@code jk cache} wipe of the
 * tier in this engine's memory; every entry is re-validated against the live file before use, so
 * the bytes come back on the next flush rather than going stale.
 */
public final class FileHashMemo {

    /** Distrust stat-identity for files modified within this window (mtime-granularity guard). */
    private static final long SETTLE_MS = 2_000;

    /**
     * Entries one store keeps. Around 200 B each, so the cap is a heap bound before it is a disk
     * one; a workspace with more distinct input files than this re-hashes its coldest.
     */
    private static final int MAX_ENTRIES = 32_768;

    /** Headroom over {@link #MAX_ENTRIES} before a trim runs, so inserts do not each pay for one. */
    private static final int TRIM_SLACK = MAX_ENTRIES / 4;

    private static final String STORE_FILE = "memo.v1";

    /** One loaded store per cache root. */
    private static final ConcurrentMap<Path, Store> STORES = new ConcurrentHashMap<>();

    /** Monotonic use clock; ranks victims when a store is over cap. */
    private static final AtomicLong USE_TICK = new AtomicLong();

    private static final AtomicLong CONTENT_HASH_INVOCATIONS = new AtomicLong();
    private static final AtomicLong MEMO_HITS = new AtomicLong();
    private static final AtomicLong CONTENT_READS = new AtomicLong();

    private FileHashMemo() {}

    /**
     * SHA-256 hex of {@code file}'s contents, from the memo when the file's stat identity still
     * matches what was recorded. Streams the file on a miss. IOException propagates (caller decides).
     */
    public static String contentHash(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        return contentHash(abs, Files.readAttributes(abs, BasicFileAttributes.class));
    }

    /**
     * As {@link #contentHash(Path)}, for a caller that already holds {@code file}'s attributes —
     * a tree walk hands them over, and re-reading them is the single most repeated syscall in a
     * build. {@code file} must already be absolute and normalized.
     */
    public static String contentHash(Path file, BasicFileAttributes attrs) throws IOException {
        CONTENT_HASH_INVOCATIONS.incrementAndGet();
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().toMillis();
        long nanos = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);

        Store store = store();
        if (store != null) {
            Entry hit = store.get(file.toString(), size, mtime, nanos);
            if (hit != null) {
                MEMO_HITS.incrementAndGet();
                return hit.token;
            }
        }

        CONTENT_READS.incrementAndGet();
        String token = Hashing.sha256Hex(file);
        // A file written moments ago can change again at the same size and tick; only record it
        // once the clock can tell the two apart.
        if (store != null && System.currentTimeMillis() - mtime >= SETTLE_MS) {
            store.put(file.toString(), new Entry(size, mtime, -1L, token));
        }
        return token;
    }

    /**
     * Seed the memo with a <em>known</em> content hash — a CAS blob this build just restored, whose
     * bytes are complete and whose mtime is ours. Recorded with its nanosecond stamp so it is
     * trusted without waiting out {@link #SETTLE_MS}; this is what keeps stamps and package keys
     * cheap after {@code jk clean} plus an action-cache restore, which would otherwise re-hash
     * every class file and jar it just wrote.
     */
    public static void rememberContent(Path file, String sha256Hex) {
        if (sha256Hex == null || sha256Hex.isBlank()) return;
        try {
            Path abs = file.toAbsolutePath().normalize();
            BasicFileAttributes attrs = Files.readAttributes(abs, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) return;
            Store store = store();
            if (store == null) return;
            store.put(
                    abs.toString(),
                    new Entry(
                            attrs.size(),
                            attrs.lastModifiedTime().toMillis(),
                            attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                            sha256Hex));
        } catch (IOException | RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Persist every loaded store. Called at the idle boundary; the maps stay in memory, since their
     * whole payoff is the next build. Best-effort — an unwritable cache root just means the next
     * engine starts cold.
     */
    public static void flush() {
        for (Store s : STORES.values()) {
            s.flush();
        }
    }

    /** Test seam: drop every loaded store, so the next lookup reloads from disk. */
    public static void reset() {
        STORES.clear();
    }

    /** Test seam: total {@link #contentHash} calls since process start (or last {@link #resetStats}). */
    public static long contentHashInvocations() {
        return CONTENT_HASH_INVOCATIONS.get();
    }

    /** Test seam: lookups the memo answered without reading the file. */
    public static long memoHits() {
        return MEMO_HITS.get();
    }

    /** Test seam: lookups that had to stream the file's bytes. */
    public static long contentReads() {
        return CONTENT_READS.get();
    }

    /** Test seam. */
    public static void resetStats() {
        CONTENT_HASH_INVOCATIONS.set(0);
        MEMO_HITS.set(0);
        CONTENT_READS.set(0);
    }

    /** The store for the session's cache root, or {@code null} when no session cache resolves. */
    private static Store store() {
        try {
            Path cache = SessionContext.current().cacheDir();
            return STORES.computeIfAbsent(
                    cache.toAbsolutePath().normalize(), root -> Store.load(CacheTree.HASH_MEMO.under(root)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * One memoized fingerprint.
     *
     * @param nanos nanosecond mtime this entry was seeded at, or {@code -1} for a self-hash, which
     *     is validated by settle instead
     */
    private static final class Entry {
        final long size;
        final long mtimeMillis;
        final long nanos;
        final String token;
        volatile long used;

        Entry(long size, long mtimeMillis, long nanos, String token) {
            this.size = size;
            this.mtimeMillis = mtimeMillis;
            this.nanos = nanos;
            this.token = token;
            this.used = USE_TICK.incrementAndGet();
        }
    }

    /** The in-memory map for one cache root, plus the single file it persists to. */
    private static final class Store {

        private final Path file;
        private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
        private final AtomicBoolean trimming = new AtomicBoolean();
        private volatile boolean dirty;

        private Store(Path dir) {
            this.file = dir.resolve(STORE_FILE);
        }

        static Store load(Path dir) {
            Store s = new Store(dir);
            try {
                for (String line : Files.readAllLines(s.file, StandardCharsets.UTF_8)) {
                    decode(line, s.entries);
                }
            } catch (IOException | RuntimeException ignored) {
                // No store yet, or an unreadable one: start empty and refill.
            }
            sweepResidue(dir, s.file);
            return s;
        }

        /**
         * The store owns its directory, so anything beside it is residue — a sharded layout an
         * older jk wrote, or a half-written temp from an interrupted flush. Nothing reads it and
         * retention ranks the tier as one file, so it would otherwise sit there for good. One
         * readdir per cache root per engine, over a directory that is normally empty.
         */
        private static void sweepResidue(Path dir, Path keep) {
            try (Stream<Path> children = Files.list(dir)) {
                for (Path p : (Iterable<Path>) children::iterator) {
                    if (p.equals(keep)) continue;
                    PathUtil.deleteRecursively(p);
                }
            } catch (IOException | RuntimeException ignored) {
                // Housekeeping, never load-bearing.
            }
        }

        /** The entry for {@code path} when it still describes the file the caller just stat'ed. */
        Entry get(String path, long size, long mtimeMillis, long nanos) {
            Entry e = entries.get(path);
            if (e == null || e.size != size || e.mtimeMillis != mtimeMillis) return null;
            boolean valid = e.nanos >= 0 ? e.nanos == nanos : System.currentTimeMillis() - mtimeMillis >= SETTLE_MS;
            if (!valid) return null;
            e.used = USE_TICK.incrementAndGet();
            return e;
        }

        void put(String path, Entry e) {
            entries.put(path, e);
            dirty = true;
            if (entries.size() > MAX_ENTRIES + TRIM_SLACK) trim();
        }

        /** Drop the least recently used down to {@link #MAX_ENTRIES}. One trim at a time. */
        private void trim() {
            if (!trimming.compareAndSet(false, true)) return;
            try {
                List<Map.Entry<String, Entry>> all = new ArrayList<>(entries.entrySet());
                if (all.size() <= MAX_ENTRIES) return;
                all.sort(Comparator.comparingLong(x -> x.getValue().used));
                for (int i = 0; i < all.size() - MAX_ENTRIES; i++) {
                    entries.remove(all.get(i).getKey(), all.get(i).getValue());
                }
            } finally {
                trimming.set(false);
            }
        }

        void flush() {
            if (!dirty) return;
            dirty = false;
            try {
                trim();
                StringBuilder sb = new StringBuilder(entries.size() * 128);
                for (Map.Entry<String, Entry> e : entries.entrySet()) {
                    String path = e.getKey();
                    // The record is one line and NUL-delimited, so a path carrying either is one
                    // this format cannot read back. It stays live in memory and is simply not written.
                    if (path.indexOf('\n') >= 0 || path.indexOf('\0') >= 0) continue;
                    Entry v = e.getValue();
                    sb.append(path)
                            .append('\0')
                            .append(v.size)
                            .append('\0')
                            .append(v.mtimeMillis)
                            .append('\0')
                            .append(v.nanos)
                            .append('\0')
                            .append(v.token)
                            .append('\n');
                }
                AtomicWrites.replace(file, sb.toString());
            } catch (IOException | RuntimeException e) {
                // The memo is an optimisation, never a requirement.
                dirty = true;
            }
        }

        private static void decode(String line, ConcurrentMap<String, Entry> into) {
            int a = line.indexOf('\0');
            if (a < 0) return;
            int b = line.indexOf('\0', a + 1);
            if (b < 0) return;
            int c = line.indexOf('\0', b + 1);
            if (c < 0) return;
            int d = line.indexOf('\0', c + 1);
            if (d < 0) return;
            String token = line.substring(d + 1);
            if (token.isEmpty()) return;
            try {
                Entry e = new Entry(
                        Long.parseLong(line.substring(a + 1, b)),
                        Long.parseLong(line.substring(b + 1, c)),
                        Long.parseLong(line.substring(c + 1, d)),
                        token);
                into.put(line.substring(0, a), e);
            } catch (NumberFormatException malformed) {
                // A torn or hand-edited line is not an entry.
            }
        }
    }
}
