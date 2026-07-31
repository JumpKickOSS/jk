// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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
 */
public final class FileHashMemo {

    /** Distrust stat-identity for files modified within this window (mtime-granularity guard). */
    private static final long SETTLE_MS = 2_000;

    /** Absolute-path → hex for the current thread (request / pipeline worker). */
    private static final ThreadLocal<Map<String, String>> THREAD_CACHE = ThreadLocal.withInitial(HashMap::new);

    private static final AtomicLong CONTENT_HASH_INVOCATIONS = new AtomicLong();
    private static final AtomicLong THREAD_HITS = new AtomicLong();
    private static final AtomicLong DISK_HITS = new AtomicLong();
    private static final AtomicLong CONTENT_READS = new AtomicLong();

    private FileHashMemo() {}

    /**
     * SHA-256 hex of {@code file}'s contents, using thread-local then disk memo when safe. Streams
     * the file (never slurp) on a miss. IOException propagates (caller decides).
     */
    public static String contentHash(Path file) throws IOException {
        CONTENT_HASH_INVOCATIONS.incrementAndGet();
        Path abs = file.toAbsolutePath().normalize();
        long size = Files.size(abs);
        long mtime = Files.getLastModifiedTime(abs).toMillis();
        // Key includes size+mtime so a same-path rewrite never hits a stale thread entry.
        String tkey = abs + "\0" + size + "\0" + mtime;
        Map<String, String> thread = THREAD_CACHE.get();
        String cached = thread.get(tkey);
        if (cached != null) {
            THREAD_HITS.incrementAndGet();
            return cached;
        }

        String token = lookup(abs, size, mtime);
        if (token != null) {
            DISK_HITS.incrementAndGet();
            thread.put(tkey, token);
            return token;
        }

        CONTENT_READS.incrementAndGet();
        token = Hashing.sha256Hex(abs);
        store(abs, size, mtime, token);
        thread.put(tkey, token);
        return token;
    }

    /** Drop this thread's walk cache (tests / long-lived worker threads). */
    public static void clearThreadCache() {
        THREAD_CACHE.remove();
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
     */
    public static String lookup(Path file, long size, long mtimeMillis) {
        if (System.currentTimeMillis() - mtimeMillis < SETTLE_MS) return null;
        Path entry = entryPath(file);
        if (entry == null) return null;
        try {
            String content = Files.readString(entry, StandardCharsets.UTF_8);
            int sp1 = content.indexOf(' ');
            int sp2 = content.indexOf(' ', sp1 + 1);
            if (sp1 < 0 || sp2 < 0) return null;
            if (Long.parseLong(content.substring(0, sp1)) != size) return null;
            if (Long.parseLong(content.substring(sp1 + 1, sp2)) != mtimeMillis) return null;
            String token = content.substring(sp2 + 1).trim();
            return token.isEmpty() ? null : token;
        } catch (IOException | NumberFormatException e) {
            return null; // fail open — caller hashes content
        }
    }

    /** Record {@code token} for {@code file}; best-effort (an I/O failure just skips the memo). */
    public static void store(Path file, long size, long mtimeMillis, String token) {
        if (System.currentTimeMillis() - mtimeMillis < SETTLE_MS) return; // not settled — don't trust the stat
        Path entry = entryPath(file);
        if (entry == null) return;
        try {
            // Write-then-move so a concurrent reader never sees a torn entry.
            AtomicWrites.replace(entry, size + " " + mtimeMillis + " " + token);
        } catch (IOException | RuntimeException e) {
            // best-effort — the memo is an optimisation, never a requirement
        }
    }

    /** {@code <cache>/hash-memo/<aa>/<sha256(abs path)>}, or {@code null} when no session cache resolves. */
    private static Path entryPath(Path file) {
        try {
            Path cache = cc.jumpkick.config.SessionContext.current().cacheDir();
            String key = Hashing.sha256Hex(
                    file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return cache.resolve("hash-memo").resolve(key.substring(0, 2)).resolve(key.substring(2));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
