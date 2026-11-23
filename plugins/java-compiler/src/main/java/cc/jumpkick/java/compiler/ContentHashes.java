// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * File content hashes kept for this worker's life, read again only when a file's size or mtime
 * moved: a rewrite with the bytes it had costs one read and leaves the hash alone.
 */
final class ContentHashes {

    private static final Map<Path, Hashed> HASHED = new ConcurrentHashMap<>();

    private ContentHashes() {}

    /** The SHA-256 of {@code file}'s bytes, in hex. */
    static String of(Path file) throws IOException {
        return of(file, Files.readAttributes(file, BasicFileAttributes.class));
    }

    /** {@link #of(Path)} with {@code file}'s attributes already read. */
    static String of(Path file, BasicFileAttributes attrs) throws IOException {
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        Hashed known = HASHED.get(file);
        if (known != null && known.size == size && known.mtime == mtime) return known.sha;
        String sha = Hashing.sha256Hex(file);
        HASHED.put(file, new Hashed(size, mtime, sha));
        return sha;
    }

    private record Hashed(long size, long mtime, String sha) {}
}
