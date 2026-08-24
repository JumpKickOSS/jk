// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content fingerprints for source/input files.
 *
 * <ul>
 * <li><b>Thread-local walk cache</b> — each absolute path is content-hashed at most once per
 * thread (so {@code ActionKey.forJavac} + {@code snapshotInputs} share one walk).
 * <li><b>Disk memo</b> — {@code (path, size, mtime) → hex} under {@code <cache>/hash-memo/}.
 * Trust only when size+mtime match and mtime is ≥ {@link #SETTLE_MS} old; store only after
 * settle. Fail open (re-hash) on any I/O error.
 * </ul>
 *
 * <p>One entry per source file at {@code <cache>/hash-memo/<aa>/<sha256(abs path)>}, holding two
 * lines:
 *
 * <pre>{@code
 * <size> <mtimeMillis> <token>
 * <absolute path>
 * }</pre>
 *
 * <p>The second line is what lets retention be exact: {@link CacheRetention} drops the entries
 * whose source no longer exists instead of guessing by age, which it cannot do here because
 * {@link #forceStore} rewrites on every content change and so mtime measures churn, not use. An
 * entry without that line is not trusted — {@link #lookup} fails open, the caller re-hashes, and
 * the store that follows writes the whole shape.
 */
public final class FileHashMemo {

    /** Distrust stat-identity for files modified within this window (mtime-granularity guard). */
    private static final long SETTLE_MS = 2_000;

    /**
     * Pathology backstop per thread cache: the idle boundary clears these anyway, but a
     * single build over an enormous tree must not grow one map without limit either. ~300 bytes
     * per entry; the disk memo absorbs the cost of a mid-build clear.
     */
    private static final int MAX_THREAD_ENTRIES = 131_072;

    /**
     * Every live thread's walk cache, weakly held so a dead thread's map can be collected. The
     * idle boundary clears them all ({@link #clearAllThreadCaches}) — without that, the immortal
     * {@code jk-cpu-N} pool threads accrete entries forever, because the cache key embeds the
     * nanosecond mtime and every rebuild mints new keys.
     */
    private static final Set<Map<String, String>> LIVE_CACHES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Absolute-path → hex for the current thread (request / plan worker). */
    private static final ThreadLocal<Map<String, String>> THREAD_CACHE = ThreadLocal.withInitial(() -> {
        // Concurrent map: the owner thread is the only writer on the hot path, but the idle
        // boundary clears from another thread, and a plain HashMap can corrupt under that race.
        Map<String, String> m = new ConcurrentHashMap<>();
        LIVE_CACHES.add(m);
        return m;
    });

    private static final AtomicLong CONTENT_HASH_INVOCATIONS = new AtomicLong();
    private static final AtomicLong THREAD_HITS = new AtomicLong();
    private static final AtomicLong DISK_HITS = new AtomicLong();
    private static final AtomicLong CONTENT_READS = new AtomicLong();

    private FileHashMemo() {}

    /**
     * SHA-256 hex of {@code file}'s contents, using thread-local then disk memo when safe. Streams
     * the file (never slurp) on a miss. IOException propagates (caller decides).
     *
     * <p>CAS-seeded digests ({@link #rememberContent}) are trusted immediately. Self-hashes are
     * not trusted for unsettled mtimes (size+mtime alone can alias a same-tick content rewrite).
     */
    public static String contentHash(Path file) throws IOException {
        CONTENT_HASH_INVOCATIONS.incrementAndGet();
        Path abs = file.toAbsolutePath().normalize();
        long size = Files.size(abs);
        FileTime ft = Files.getLastModifiedTime(abs);
        long mtime = ft.toMillis();
        boolean unsettled = System.currentTimeMillis() - mtime < SETTLE_MS;
        // Key includes size+mtime (nanosecond precision) so a same-path rewrite — even one
        // landing inside the same millisecond tick — never hits a stale entry.
        String tkey = abs + "\0" + size + "\0" + ft.to(TimeUnit.NANOSECONDS);
        Map<String, String> thread = THREAD_CACHE.get();
        String cached = thread.get(tkey);
        if (cached != null) {
            // "known:" = CAS restore seed — always safe. "hash:" = self-hash — only if settled.
            if (cached.startsWith("known:")) {
                THREAD_HITS.incrementAndGet();
                return cached.substring("known:".length());
            }
            if (cached.startsWith("hash:") && !unsettled) {
                THREAD_HITS.incrementAndGet();
                return cached.substring("hash:".length());
            }
            // Unsettled self-hash or unknown prefix — fall through and re-read.
        }

        String token = lookup(abs, size, mtime);
        // Disk may hold a ClasspathFingerprint token (file:<hex> / jar:…) or bare hex.
        // file: = CAS seed (trusted immediately). bare hex = self-hash (settle-gated).
        boolean casSeed = false;
        if (token != null) {
            if (token.startsWith("file:") && token.length() > 5) {
                token = token.substring(5);
                casSeed = true;
            } else if (token.startsWith("jar:")) {
                token = null; // not a raw content hash — fall through to hash
            } else if (unsettled) {
                // bare hex from store() must not apply until settle (same-size rewrite safety)
                token = null;
            }
        }
        if (token != null) {
            DISK_HITS.incrementAndGet();
            // known: always trusted; hash: only when settled (same tick rewrite safety).
            if (thread.size() >= MAX_THREAD_ENTRIES) thread.clear();
            thread.put(tkey, (casSeed ? "known:" : "hash:") + token);
            return token;
        }

        CONTENT_READS.incrementAndGet();
        token = Hashing.sha256Hex(abs);
        store(abs, size, mtime, token);
        if (thread.size() >= MAX_THREAD_ENTRIES) thread.clear();
        thread.put(tkey, "hash:" + token);
        return token;
    }

    /** Drop this thread's walk cache (tests / long-lived worker threads). */
    public static void clearThreadCache() {
        THREAD_CACHE.remove();
    }

    /**
     * Drop every live thread's walk cache. Called at the idle boundary so pool-thread caches do
     * not outlive the build that filled them; safe cross-thread because the maps are concurrent.
     */
    public static void clearAllThreadCaches() {
        synchronized (LIVE_CACHES) {
            for (Map<String, String> m : LIVE_CACHES) m.clear();
        }
    }

    /** Test seam: total {@link #contentHash} calls since process start (or last {@link #resetStats}). */
    public static long contentHashInvocations() {
        return CONTENT_HASH_INVOCATIONS.get();
    }

    public static long threadHits() {
        return THREAD_HITS.get();
    }

    public static long diskHits() {
        return DISK_HITS.get();
    }

    public static long contentReads() {
        return CONTENT_READS.get();
    }

    /** Test seam. */
    public static void resetStats() {
        CONTENT_HASH_INVOCATIONS.set(0);
        THREAD_HITS.set(0);
        DISK_HITS.set(0);
        CONTENT_READS.set(0);
    }

    /**
     * The memoized fingerprint token for {@code file}, or {@code null} when absent, stale, or not
     * yet settled. {@code size}/{@code mtimeMillis} are the caller's freshly-stat'ed values (the
     * caller stats anyway; passing them avoids a second stat).
     *
     * <p>Trust is provenance-based: entries carrying a {@code nano=} field were seeded from a
     * <em>known</em> digest ({@link #rememberContent}) and are trusted immediately, but only when
     * the file's current nanosecond mtime still matches — an in-place rewrite that lands in the
     * same millisecond tick (compilers do this to restored class files) changes the nano stamp
     * and voids the seed. Everything else — including prefixed tokens written by {@link #store} —
     * is settle-gated, so a same-size rewrite in the same mtime tick cannot reuse a stale digest.
     */
    public static String lookup(Path file, long size, long mtimeMillis) {
        Path abs = file.toAbsolutePath().normalize();
        Path entry = entryPath(abs);
        if (entry == null) return null;
        try {
            String record = Files.readString(entry, StandardCharsets.UTF_8);
            int nl = record.indexOf('\n');
            // No recorded path, or one naming a different file: nothing here is about `file`.
            if (nl < 0 || !record.substring(nl + 1).equals(abs.toString())) return null;
            String content = record.substring(0, nl);
            int sp1 = content.indexOf(' ');
            int sp2 = content.indexOf(' ', sp1 + 1);
            if (sp1 < 0 || sp2 < 0) return null;
            if (Long.parseLong(content.substring(0, sp1)) != size) return null;
            if (Long.parseLong(content.substring(sp1 + 1, sp2)) != mtimeMillis) return null;
            String token = content.substring(sp2 + 1).trim();
            if (token.isEmpty()) return null;
            int nanoAt = token.lastIndexOf(" nano=");
            if (nanoAt >= 0) {
                long recorded = Long.parseLong(token.substring(nanoAt + " nano=".length()));
                token = token.substring(0, nanoAt).trim();
                if (token.isEmpty()) return null;
                long current = Files.getLastModifiedTime(file).to(TimeUnit.NANOSECONDS);
                return recorded == current ? token : null;
            }
            if (System.currentTimeMillis() - mtimeMillis < SETTLE_MS) return null;
            return token;
        } catch (IOException | NumberFormatException e) {
            return null; // fail open — caller hashes content
        }
    }

    /** Record {@code token} for {@code file}; best-effort (an I/O failure just skips the memo). */
    public static void store(Path file, long size, long mtimeMillis, String token) {
        if (System.currentTimeMillis() - mtimeMillis < SETTLE_MS) return; // not settled — don't trust the stat
        forceStore(file, size, mtimeMillis, token);
    }

    /**
     * Seed the memo with a <em>known</em> content hash (e.g. a CAS blob just restored). Always fills
     * the thread-local cache; also writes the disk memo without the settle delay — the bytes are
     * complete and the mtime is ours. This is what keeps {@code TestStamp} / package keys cheap
     * after {@code jk clean} + action-cache restore (otherwise every class file and fat jar is
     * re-hashed / zip-walked).
     *
     * <p>Disk uses the {@code file:<hex>} form so {@link ClasspathFingerprint#entry} short-circuits
     * for both class trees and jars (packagers are byte-reproducible, so raw jar SHA is a valid
     * content fingerprint).
     */
    public static void rememberContent(Path file, String sha256Hex) {
        if (sha256Hex == null || sha256Hex.isBlank()) return;
        try {
            Path abs = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(abs)) return;
            long size = Files.size(abs);
            FileTime ft = Files.getLastModifiedTime(abs);
            long nanos = ft.to(TimeUnit.NANOSECONDS);
            String tkey = abs + "\0" + size + "\0" + nanos;
            Map<String, String> thread = THREAD_CACHE.get();
            if (thread.size() >= MAX_THREAD_ENTRIES) thread.clear();
            thread.put(tkey, "known:" + sha256Hex);
            // Disk: file: form so entry() short-circuits; contentHash strips the prefix. The
            // nano= stamp is the seed's provenance mark — lookup trusts it immediately but only
            // while the file's nanosecond mtime is unchanged (see lookup).
            forceStore(abs, size, ft.toMillis(), "file:" + sha256Hex + " nano=" + nanos);
        } catch (IOException | RuntimeException ignored) {
            // best-effort
        }
    }

    private static void forceStore(Path file, long size, long mtimeMillis, String token) {
        Path abs = file.toAbsolutePath().normalize();
        Path entry = entryPath(abs);
        if (entry == null) return;
        try {
            AtomicWrites.replace(entry, size + " " + mtimeMillis + " " + token + "\n" + abs);
        } catch (IOException | RuntimeException e) {
            // best-effort — the memo is an optimisation, never a requirement
        }
    }

    /**
     * {@code <cache>/hash-memo/<aa>/<sha256(abs path)>}, or {@code null} when no session cache
     * resolves. {@code abs} must already be absolute and normalized — it is the string that is
     * hashed, and the one the entry records.
     */
    private static Path entryPath(Path abs) {
        try {
            Path cache = SessionContext.current().cacheDir();
            String key = Hashing.sha256Hex(abs.toString().getBytes(StandardCharsets.UTF_8));
            return cache.resolve("hash-memo").resolve(key.substring(0, 2)).resolve(key.substring(2));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
