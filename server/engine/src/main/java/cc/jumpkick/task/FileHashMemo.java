// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Disk memo {@code (path, size, mtime) → content fingerprint} under {@code <cache>/hash-memo/}.
 * Trust only when size+mtime match and mtime is ≥ {@link #SETTLE_MS} old; store only after settle.
 * Fail open (re-hash) on any I/O error.
 */
public final class FileHashMemo {

    /** Distrust stat-identity for files modified within this window (mtime-granularity guard). */
    private static final long SETTLE_MS = 2_000;

    private FileHashMemo() {}

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
