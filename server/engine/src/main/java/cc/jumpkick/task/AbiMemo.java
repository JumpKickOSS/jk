// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Content identity → ABI token: the JVM ABI token of a jar or classes directory, or, under the
 * {@code kotlin:} namespace, the digest of its Kotlin classpath snapshot. Keyed by {@link
 * ClasspathFingerprint#entry}, never path or mtime: the same jar bytes at two paths share one extract. A hit is a map read; a miss is the
 * caller's to extract. Fail-open — a lost entry costs one re-extract. The file is a {@link MemoLog}:
 * a flush appends the tokens put since the last one rather than rewriting every one the memo holds.
 */
public final class AbiMemo {

    private static final int MAX_ENTRIES = 32_768;
    private static final int TRIM_SLACK = MAX_ENTRIES / 4;
    private static final String STORE_FILE = "memo.v1";

    private static final CacheRootStores<Store> STORES =
            new CacheRootStores<>(root -> Store.load(CacheTree.ABI_MEMO.under(root)), Store::flush);
    private static final AtomicLong USE_TICK = new AtomicLong();
    private static final AtomicLong LOOKUPS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();

    private AbiMemo() {}

    /** Previously stored ABI token for {@code contentIdentity}, or {@code null}. */
    public static @Nullable String get(@Nullable String contentIdentity) {
        LOOKUPS.incrementAndGet();
        if (contentIdentity == null || contentIdentity.isBlank()) return null;
        Store store = store();
        if (store == null) return null;
        String hit = store.get(contentIdentity);
        if (hit != null) HITS.incrementAndGet();
        return hit;
    }

    /** Remember {@code abiToken} for {@code contentIdentity}. Best-effort. */
    public static void put(String contentIdentity, String abiToken) {
        if (contentIdentity == null || contentIdentity.isBlank()) return;
        if (abiToken == null || abiToken.isBlank()) return;
        Store store = store();
        if (store == null) return;
        store.put(contentIdentity, abiToken);
    }

    public static void flush() {
        for (Store s : STORES.loaded()) s.flush();
    }

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

    public static long lookups() {
        return LOOKUPS.get();
    }

    public static long hits() {
        return HITS.get();
    }

    public static void resetStats() {
        LOOKUPS.set(0);
        HITS.set(0);
    }

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

    /** One memoized ABI token and the use tick that ranks it for eviction. */
    static final class Entry {
        final String token;
        volatile long used;

        Entry(String token) {
            this.token = token;
            this.used = USE_TICK.incrementAndGet();
        }
    }

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
                // No store yet, or unreadable: start empty.
                Log.debug("load: No store yet, or unreadable", e);
            }
            sweepResidue(dir, s.file);
            return s;
        }

        private static void sweepResidue(Path dir, Path keep) {
            try (Stream<Path> children = Files.list(dir)) {
                for (Path p : (Iterable<Path>) children::iterator) {
                    if (p.equals(keep)) continue;
                    PathUtil.deleteRecursively(p);
                }
            } catch (IOException | RuntimeException e) {
                // Housekeeping.
                Log.debug("sweepResidue: Housekeeping", e);
            }
        }

        @Nullable
        String get(String key) {
            Entry e = entries.get(key);
            if (e == null) return null;
            e.used = USE_TICK.incrementAndGet();
            return e.token;
        }

        void put(String key, String token) {
            Entry old = entries.get(key);
            // The token the memo already holds: nothing to append, nothing to flush.
            if (old != null && old.token.equals(token)) {
                old.used = USE_TICK.incrementAndGet();
                return;
            }
            entries.put(key, new Entry(token));
            log.changed(key);
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
                dirty = true;
            }
        }

        /** One record line, or {@code null} for a key or token this NUL-delimited format cannot carry. */
        private static @Nullable String encode(String key, Entry e) {
            if (key.indexOf('\n') >= 0 || key.indexOf('\0') >= 0) return null;
            if (e.token.indexOf('\n') >= 0 || e.token.indexOf('\0') >= 0) return null;
            return key + '\0' + e.token;
        }

        private static void decode(String line, ConcurrentMap<String, Entry> into) {
            int a = line.indexOf('\0');
            if (a < 0) return;
            String token = line.substring(a + 1);
            // A NUL in the token is two records run together: not an entry.
            if (token.isEmpty() || token.indexOf('\0') >= 0) return;
            into.put(line.substring(0, a), new Entry(token));
        }
    }
}
