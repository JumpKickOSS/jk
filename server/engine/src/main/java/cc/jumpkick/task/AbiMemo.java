// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Content identity → JVM ABI token. Keyed by {@link ClasspathFingerprint#entry}, never path or
 * mtime: the same jar bytes at two paths share one extract. A hit is a map read; a miss is the
 * caller's to extract. Fail-open — a lost entry costs one re-extract.
 */
public final class AbiMemo {

    private static final int MAX_ENTRIES = 32_768;
    private static final int TRIM_SLACK = MAX_ENTRIES / 4;
    private static final String STORE_FILE = "memo.v1";

    private static final ConcurrentMap<Path, Store> STORES = new ConcurrentHashMap<>();
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
        for (Store s : STORES.values()) s.flush();
    }

    public static void reset() {
        STORES.clear();
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
            Path cache = SessionContext.current().cacheDir();
            return STORES.computeIfAbsent(
                    cache.toAbsolutePath().normalize(), root -> Store.load(CacheTree.ABI_MEMO.under(root)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Entry {
        final String token;
        volatile long used;

        Entry(String token) {
            this.token = token;
            this.used = USE_TICK.incrementAndGet();
        }
    }

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
                // No store yet, or unreadable: start empty.
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
            } catch (IOException | RuntimeException ignored) {
                // Housekeeping.
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
            entries.put(key, new Entry(token));
            dirty = true;
            if (entries.size() > MAX_ENTRIES + TRIM_SLACK) trim();
        }

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
                StringBuilder sb = new StringBuilder(entries.size() * 96);
                for (Map.Entry<String, Entry> e : entries.entrySet()) {
                    String key = e.getKey();
                    if (key.indexOf('\n') >= 0 || key.indexOf('\0') >= 0) continue;
                    String token = e.getValue().token;
                    if (token.indexOf('\n') >= 0 || token.indexOf('\0') >= 0) continue;
                    sb.append(key).append('\0').append(token).append('\n');
                }
                AtomicWrites.replace(file, sb.toString());
            } catch (IOException | RuntimeException e) {
                dirty = true;
            }
        }

        private static void decode(String line, ConcurrentMap<String, Entry> into) {
            int a = line.indexOf('\0');
            if (a < 0) return;
            String token = line.substring(a + 1);
            if (token.isEmpty()) return;
            into.put(line.substring(0, a), new Entry(token));
        }
    }
}
