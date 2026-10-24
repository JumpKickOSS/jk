// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Settled;
import cc.jumpkick.host.StatIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Content fingerprints for source and input files, memoized on the path and its {@link
 * StatIdentity}: size, mtime, change time and file id, so a tool that restores a file's size and
 * mtime over different bytes ({@code cp -p}, {@code rsync -a}, {@code touch -r}) is still a miss.
 *
 * <p>One store per cache root, held in memory while that root is in use ({@link CacheRootStores}
 * bounds how many are) and persisted as a single file at {@code <cache>/hash-memo/memo.v1}. A hit costs one map read; the caller's own stat is the
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
 * just restored); such an entry is trusted immediately, while its identity still matches. A
 * self-hash is recorded only when the file's identity after the read equals the one the caller
 * hashed against, so bytes that changed under the read are never filed under the old stat, and
 * only once the file is {@link Settled}, so a same-size rewrite inside one
 * timestamp tick of a coarse file system cannot reuse a stale digest.
 *
 * <p>The file is a {@link MemoLog}: a flush appends the entries that changed since the last one, and
 * a load keeps the last line per path. A line whose token is not a SHA-256 is not an entry, so a
 * damaged record can lose an entry but never misreport one.
 *
 * <p>Every I/O failure fails open: the caller re-hashes. Nothing here is load-bearing, which is also
 * why a concurrent engine writing the same store is last-writer-wins rather than locked — a lost
 * entry costs one re-hash. For the same reason the store survives a {@code jk cache} wipe of the
 * tier in this engine's memory; every entry is re-validated against the live file before use, so
 * the bytes come back on the next flush rather than going stale.
 */
public final class FileHashMemo {

    /**
     * Entries one store keeps. Around 200 B each, so the cap is a heap bound before it is a disk
     * one; a workspace with more distinct input files than this re-hashes its coldest.
     */
    private static final int MAX_ENTRIES = 32_768;

    /** Headroom over {@link #MAX_ENTRIES} before a trim runs, so inserts do not each pay for one. */
    private static final int TRIM_SLACK = MAX_ENTRIES / 4;

    private static final String STORE_FILE = "memo.v1";

    /** The loaded stores, one per cache root. */
    private static final CacheRootStores<Store> STORES =
            new CacheRootStores<>(root -> Store.load(CacheTree.HASH_MEMO.under(root)), Store::flush);

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
        return contentHash(abs, StatIdentity.of(abs));
    }

    /**
     * As {@link #contentHash(Path)}, for a caller that already holds {@code file}'s attributes —
     * a tree walk hands them over, and re-reading them is the single most repeated syscall in a
     * build. {@code file} must already be absolute and normalized.
     */
    public static String contentHash(Path file, BasicFileAttributes attrs) throws IOException {
        return contentHash(file, StatIdentity.of(file, attrs));
    }

    /**
     * As {@link #contentHash(Path, BasicFileAttributes)} for a caller holding the identity itself —
     * a retained tree snapshot carries it without the attributes object. {@code file} must already
     * be absolute and normalized. The digest is of the bytes read now; it is remembered only when
     * the file still has {@code identity} after the read.
     */
    public static String contentHash(Path file, StatIdentity identity) throws IOException {
        CONTENT_HASH_INVOCATIONS.incrementAndGet();

        Store store = store();
        if (store != null) {
            Entry hit = store.get(file.toString(), identity);
            if (hit != null) {
                MEMO_HITS.incrementAndGet();
                return hit.token;
            }
        }

        CONTENT_READS.incrementAndGet();
        String token = Hashing.sha256Hex(file);
        // A file written moments ago can change again at the same size and tick; only record it
        // once the clock can tell the two apart, and only under the identity the bytes were read at.
        if (store != null && Settled.is(identity) && identity.equals(StatIdentity.of(file))) {
            store.put(file.toString(), new Entry(identity, false, token));
        }
        return token;
    }

    /**
     * Seed the memo with a <em>known</em> content hash — a CAS blob this build just restored, whose
     * bytes are complete and whose mtime is ours. Recorded with its nanosecond stamp so it is
     * trusted without waiting until it is {@link Settled}; this is what keeps stamps and package keys
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
            store.put(abs.toString(), new Entry(StatIdentity.of(abs, attrs), true, sha256Hex));
        } catch (IOException | RuntimeException e) {
            // best-effort
            Log.debug("rememberContent: best-effort", e);
        }
    }

    /**
     * Persist every loaded store. Called at the idle boundary; the maps stay in memory, since their
     * whole payoff is the next build. Best-effort — an unwritable cache root just means the next
     * engine starts cold.
     */
    public static void flush() {
        for (Store s : STORES.loaded()) {
            s.flush();
        }
    }

    /** Test seam: drop every loaded store, so the next lookup reloads from disk. */
    public static void reset() {
        STORES.clear();
    }

    /**
     * Persist every loaded store, then drop it from memory, and return how many entries went. For
     * the engine that has sat idle: the next build reloads the store from disk in one read.
     */
    public static int dropAll() {
        int dropped = 0;
        for (Store s : STORES.loaded()) {
            s.flush();
            dropped += s.entries.size();
        }
        STORES.clear();
        return dropped;
    }

    /** Test seam: how many cache roots have a store in memory. */
    static int loadedStores() {
        return STORES.size();
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
    private static @Nullable Store store() {
        try {
            return STORES.of(SessionContext.current().cacheDir());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The entries to drop so {@code entries} keeps {@code keep}: the least recently used first,
     * ranked on one reading of every entry's use tick taken before the sort. A hit moves {@link
     * Entry#used} while a trim runs — the map is concurrent and nothing holds readers off — and a
     * sort keyed on the live field asks a comparator whose answers change under it, which TimSort
     * rejects as a contract violation. A tie on the tick breaks on the key, so the order is total.
     */
    static List<Map.Entry<String, Entry>> victims(Map<String, Entry> entries, int keep) {
        record Ranked(Map.Entry<String, Entry> entry, long used) {}
        List<Ranked> all = new ArrayList<>(entries.size());
        for (Map.Entry<String, Entry> e : entries.entrySet()) all.add(new Ranked(e, e.getValue().used));
        int drop = all.size() - keep;
        if (drop <= 0) return List.of();
        all.sort(Comparator.comparingLong(Ranked::used)
                .thenComparing(r -> r.entry().getKey()));
        List<Map.Entry<String, Entry>> out = new ArrayList<>(drop);
        for (int i = 0; i < drop; i++) out.add(all.get(i).entry());
        return out;
    }

    /**
     * One memoized fingerprint: the identity the digest was taken at, and whether it was seeded
     * from a known blob ({@link #rememberContent}) rather than hashed here.
     */
    static final class Entry {
        final StatIdentity identity;
        final boolean seeded;
        final String token;
        volatile long used;

        Entry(StatIdentity identity, boolean seeded, String token) {
            this.identity = identity;
            this.seeded = seeded;
            this.token = token;
            this.used = USE_TICK.incrementAndGet();
        }

        /**
         * Whether this entry already says what {@code o} would: the same identity and digest. A
         * settled self-hash answers for a restore seed of the same file too.
         */
        boolean answersFor(Entry o) {
            return identity.equals(o.identity) && token.equals(o.token) && (seeded == o.seeded || !seeded);
        }
    }

    /** Sixty-four lowercase hex digits; anything else on a line is a torn or foreign record. */
    public static boolean isSha256(String token) {
        if (token.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = token.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    /** The in-memory map for one cache root, plus the single file it persists to. */
    private static final class Store {

        private final Path file;
        private final MemoLog log;
        private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
        private final AtomicBoolean trimming = new AtomicBoolean();
        private volatile boolean dirty;

        private Store(Path dir) {
            this.file = dir.resolve(STORE_FILE);
            this.log = new MemoLog(file);
        }

        static Store load(Path dir) {
            Store s = new Store(dir);
            try {
                s.log.load(line -> decode(line, s.entries));
            } catch (IOException | RuntimeException e) {
                // No store yet, or an unreadable one: start empty and refill.
                Log.debug("load: No store yet, or an unreadable one", e);
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
            } catch (IOException | RuntimeException e) {
                // Housekeeping, never load-bearing.
                Log.debug("sweepResidue: Housekeeping, never load-bearing", e);
            }
        }

        /** The entry for {@code path} when it still describes the file the caller just stat'ed. */
        @Nullable
        Entry get(String path, StatIdentity identity) {
            Entry e = entries.get(path);
            if (e == null || !e.identity.equals(identity)) return null;
            boolean valid = e.seeded || Settled.is(identity);
            if (!valid) return null;
            e.used = USE_TICK.incrementAndGet();
            return e;
        }

        void put(String path, Entry e) {
            Entry old = entries.get(path);
            // The same fingerprint again is not a change: nothing to append, nothing to flush.
            if (old != null && old.answersFor(e)) {
                old.used = USE_TICK.incrementAndGet();
                return;
            }
            entries.put(path, e);
            log.changed(path);
            dirty = true;
            if (entries.size() > MAX_ENTRIES + TRIM_SLACK) trim();
        }

        /** Drop the least recently used down to {@link #MAX_ENTRIES}. One trim at a time. */
        private void trim() {
            if (!trimming.compareAndSet(false, true)) return;
            try {
                for (Map.Entry<String, Entry> victim : victims(entries, MAX_ENTRIES)) {
                    if (entries.remove(victim.getKey(), victim.getValue())) log.dropped();
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
                log.flush(entries, Store::encode);
            } catch (IOException | RuntimeException e) {
                // The memo is an optimisation, never a requirement.
                dirty = true;
            }
        }

        /**
         * One record line. NUL-delimited, so a path carrying a newline or a NUL is one this format
         * cannot read back: it stays live in memory and is simply not written.
         */
        private static @Nullable String encode(String path, Entry v) {
            if (path.indexOf('\n') >= 0 || path.indexOf('\0') >= 0) return null;
            return path + '\0' + v.identity.encode() + '\0' + (v.seeded ? "s" : "h") + '\0' + v.token;
        }

        private static void decode(String line, ConcurrentMap<String, Entry> into) {
            int a = line.indexOf('\0');
            if (a < 0) return;
            int b = line.indexOf('\0', a + 1);
            if (b < 0) return;
            int c = line.indexOf('\0', b + 1);
            if (c < 0) return;
            String token = line.substring(c + 1);
            if (!isSha256(token)) return;
            StatIdentity identity = StatIdentity.decode(line.substring(a + 1, b));
            if (identity == null) return;
            into.put(line.substring(0, a), new Entry(identity, "s".equals(line.substring(b + 1, c)), token));
        }
    }
}
