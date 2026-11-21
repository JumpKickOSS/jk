// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import org.jspecify.annotations.Nullable;

/**
 * A CAS blob a restore resolved once: where it is and how big, from one {@code readAttributes}.
 * It lays itself down at a target path, verified against the digest it is filed under.
 */
record CachedBlob(Path path, long size) {

    /** Below this a clone saves too little to be worth a second read of the bytes to verify them; such blobs stream and hash in one pass. */
    static final long CLONE_MIN_BYTES = 1024L * 1024;

    /** How a {@link #copyTo} ended. */
    enum Copy {
        /** The target holds the blob's bytes, and they hash to its digest. */
        LAID,
        /** The blob was gone by the time it was opened: another process pruned it. */
        VANISHED,
        /** The copied bytes did not hash to the digest: the blob at rest is corrupt. */
        CORRUPT
    }

    /** The regular file at {@code p} with its size, or {@code null} when there is none. */
    static @Nullable CachedBlob at(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            return a.isRegularFile() ? new CachedBlob(p, a.size()) : null;
        } catch (IOException absent) {
            return null;
        }
    }

    /**
     * True when {@code target} already holds exactly this blob, filed under {@code sha}. One stat of
     * the target answers presence and size, so the hash is only paid when it can actually match.
     */
    boolean identicalAt(Path target, String sha) throws IOException {
        CachedBlob existing = at(target);
        if (existing == null || existing.size() != size) return false;
        // The memo, not a full re-read: a restore seeds this very file via rememberContent, so the
        // check that exists to avoid churning mtime costs a map lookup rather than re-reading a fat
        // jar on every warm build. Only a settled entry counts: a step that rewrote the output in
        // place at the same size within a tick of the restore left the seed's identity intact.
        return sha.equals(FileHashMemo.settledContentHash(target));
    }

    /**
     * Copy this blob to {@code target}, which must not exist, and verify it hashes to {@code sha}; a
     * target that does not is deleted. COPY, never link: compilers and packagers rewrite restored
     * files in place, and a hard link would let that rewrite mutate the CAS blob (see {@code
     * Cas.putFile}). A large blob is laid down by {@code Files.copy}, which clones where the
     * filesystem can: no space until one side is written, and as safe from an in-place rewrite as a
     * copy; its bytes are verified by reading the result. A streamed copy that hashes on the way can
     * never be a clone, so only a small blob takes that path.
     */
    Copy copyTo(Path target, String sha) throws IOException {
        if (size >= CLONE_MIN_BYTES) {
            try {
                Files.copy(path, target);
            } catch (NoSuchFileException vanished) {
                return Copy.VANISHED;
            }
            if (sha.equalsIgnoreCase(Hashing.sha256Hex(target))) return Copy.LAID;
            Files.deleteIfExists(target);
            return Copy.CORRUPT;
        }
        MessageDigest md = Hashing.newSha256();
        try (var in = Files.newInputStream(path);
                var out = new DigestOutputStream(
                        Files.newOutputStream(
                                target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE),
                        md)) {
            in.transferTo(out);
        } catch (NoSuchFileException vanished) {
            Files.deleteIfExists(target);
            return Copy.VANISHED;
        }
        if (sha.equalsIgnoreCase(Hashing.hex(md.digest()))) return Copy.LAID;
        Files.deleteIfExists(target);
        return Copy.CORRUPT;
    }
}
