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
     *
     * <p>CAS-seeded digests ({@link #rememberContent}) are trusted immediately. Self-hashes are
     * not trusted for unsettled mtimes (size+mtime alone can alias a same-tick content rewrite).
     */
    public static String contentHash(Path file) throws IOException {
        CONTENT_HASH_INVOCATIONS.incrementAndGet();
        Path abs = file.toAbsolutePath().normalize();
        long size = Files.size(abs);
        java.nio.file.attribute.FileTime ft = Files.getLastModifiedTime(abs);
        long mtime = ft.toMillis();
        boolean unsettled = System.currentTimeMillis() - mtime < SETTLE_MS;
        // Key includes size+mtime (nanosecond precision) so a same-path rewrite — even one
        // landing inside the same millisecond tick — never hits a stale entry.
        String tkey = abs + "\0" + size + "\0" + ft.to(java.util.concurrent.TimeUnit.NANOSECONDS);
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
            thread.put(tkey, (casSeed ? "known:" : "hash:") + token);
            return token;
        }

        CONTENT_READS.incrementAndGet();
        token = Hashing.sha256Hex(abs);
        store(abs, size, mtime, token);
        thread.put(tkey, "hash:" + token);
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
     *
     * <p>Trust is provenance-based: entries carrying a {@code nano=} field were seeded from a
     * <em>known</em> digest ({@link #rememberContent}) and are trusted immediately, but only when
     * the file's current nanosecond mtime still matches — an in-place rewrite that lands in the
     * same millisecond tick (compilers do this to restored class files) changes the nano stamp
     * and voids the seed. Everything else — including prefixed tokens written by {@link #store} —
     * is settle-gated, so a same-size rewrite in the same mtime tick cannot reuse a stale digest.
     */
    public static String lookup(Path file, long size, long mtimeMillis) {
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
            if (token.isEmpty()) return null;
            int nanoAt = token.lastIndexOf(" nano=");
            if (nanoAt >= 0) {
                long recorded = Long.parseLong(token.substring(nanoAt + " nano=".length()));
                token = token.substring(0, nanoAt).trim();
                if (token.isEmpty()) return null;
                long current = Files.getLastModifiedTime(file)
                        .to(java.util.concurrent.TimeUnit.NANOSECONDS);
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
            java.nio.file.attribute.FileTime ft = Files.getLastModifiedTime(abs);
            long nanos = ft.to(java.util.concurrent.TimeUnit.NANOSECONDS);
            String tkey = abs + "\0" + size + "\0" + nanos;
            THREAD_CACHE.get().put(tkey, "known:" + sha256Hex);
            // Disk: file: form so entry() short-circuits; contentHash strips the prefix. The
            // nano= stamp is the seed's provenance mark — lookup trusts it immediately but only
            // while the file's nanosecond mtime is unchanged (see lookup).
            forceStore(abs, size, ft.toMillis(), "file:" + sha256Hex + " nano=" + nanos);
        } catch (IOException | RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Seed a full fingerprint token ({@code file:…} / {@code jar:…}) for {@link
     * ClasspathFingerprint#entry} — bypasses settle (CAS restore / just-computed).
     */
    public static void rememberEntryToken(Path file, String entryToken) {
        if (entryToken == null || entryToken.isBlank()) return;
        try {
            Path abs = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(abs)) return;
            long size = Files.size(abs);
            long mtime = Files.getLastModifiedTime(abs).toMillis();
            // Thread cache stores bare hex for contentHash; entry tokens go to disk only.
            forceStore(abs, size, mtime, entryToken);
            // Also cache bare hex when token is file:<hex> so contentHash/hashTree hit.
            if (entryToken.startsWith("file:") && entryToken.length() > 5) {
                String tkey = abs + "\0" + size + "\0" + mtime;
                THREAD_CACHE.get().put(tkey, "known:" + entryToken.substring(5));
            }
        } catch (IOException | RuntimeException ignored) {
            // best-effort
        }
    }

    private static void forceStore(Path file, long size, long mtimeMillis, String token) {
        Path entry = entryPath(file);
        if (entry == null) return;
        try {
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
