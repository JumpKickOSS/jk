// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * SHA-256 of a config file's bytes, memoized on its stat identity.
 *
 * <p>A content hash is the only stamp that survives a same-length edit, but reading every byte on
 * every call is what a memo exists to avoid — the guard rule files and the lock are consulted once
 * per lane per module, and the lock alone is hundreds of kilobytes. So the bytes are read when the
 * file's {@code (size, mtime)} has moved, and within {@link #SETTLE_MS} of its mtime, where stat
 * identity cannot be trusted: a rewrite inside one filesystem tick, or one that restores the
 * previous mtime, leaves the same stamp over different bytes. Outside that window a call costs one
 * stat. Same rule as {@code FileHashMemo}, for readers below the engine.
 */
public final class FileContentStamp {

    /** Distrust stat identity for a file modified within this window. */
    private static final long SETTLE_MS = 2_000;

    /**
     * Bounded because a workspace has a handful of these files, not one per source: the members'
     * rule files, the root's, the lock, and the packs' unpack markers.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, String> HASHES = StampedMemo.bounded(512);

    private FileContentStamp() {}

    /**
     * The hex SHA-256 of {@code file}'s bytes, or null when it is not a regular file or will not
     * read. Never throws: a caller stamping several files wants one unreadable one to be a
     * distinguishable answer, not a failed pass.
     */
    public static @Nullable String of(Path file) {
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(file);
        if (stamp == null || !Files.isRegularFile(file)) return null;
        if (Clock.SYSTEM.millis() - stamp.modified().toMillis() < SETTLE_MS) {
            // Unsettled: hash the bytes and do not record a stamp this write could still share.
            return read(file);
        }
        return HASHES.get(file, stamp, () -> read(file));
    }

    private static @Nullable String read(Path file) {
        try {
            return Hashing.sha256Hex(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            return null;
        }
    }
}
