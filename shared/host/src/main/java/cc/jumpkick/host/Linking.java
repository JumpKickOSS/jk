// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * CAS/ActionCache materialization: hard-link when possible, else copy. Deletes any existing
 * {@code target} first.
 *
 * <p><strong>A hard link is only safe for a consumer that never writes through it.</strong> This
 * javadoc used to say linking was safe "because writers use create/truncate or temp-and-rename (they
 * break the link, not mutate the shared file)" — and half of that is wrong.
 * {@code open(O_TRUNC)} does <em>not</em> break a hard link: it truncates the shared inode, so every
 * other name for that file sees it. Only temp-and-rename breaks the link.
 *
 * <p>Nothing is broken today — every current caller links immutable jars — which is exactly why the
 * sentence was dangerous. It told a future reader that a create/truncate writer was safe to link, and
 * acting on that would let a compiler truncate a CAS blob in place, corrupting every action record
 * that references that content hash. {@code ActionCache} says the opposite in its own comments, and
 * {@code ActionCache} is right: it copies compile outputs rather than linking them, because compilers
 * rewrite class files in place.
 *
 * <p>So the rule is: <strong>link out of the CAS only for a consumer that reads</strong> (a classpath
 * entry, a launcher's lib dir). A consumer that may rewrite the file gets a copy. Linking <em>into</em>
 * the CAS is safe in the other direction, because a blob's name is its content hash and nothing
 * rewrites one in place.
 *
 * <p>Portable across Linux, macOS, and Windows: {@link Files#createLink} is a real hard link
 * ({@code link(2)} / {@code CreateHardLinkW} on NTFS). No admin rights required on Windows
 * (unlike symlinks). Cross-volume or non-NTFS volumes fall back to copy.
 */
public final class Linking {

    private Linking() {}

    /**
     * Materialise {@code target} as a hard link to {@code source}, or copy the bytes if linking isn't
     * supported on this filesystem pair. Replaces any existing entry at {@code target}.
     */
    public static void linkOrCopy(Path source, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.deleteIfExists(target);
        VolumePair pair = VolumePair.of(source, target);
        if (linkable(pair)) {
            try {
                Files.createLink(target, source);
                return;
            } catch (UnsupportedOperationException | FileSystemException notLinkable) {
                // Remember it for this volume pair rather than rediscovering it per file. On a mount
                // that refuses links, every file used to pay a failed createLink (79.6 us on Windows,
                // 17x Linux) before the 305 us copy — the capability question answered once per file
                // instead of once per pair.
                if (pair != null) LINKABLE.put(pair, Boolean.FALSE);
            }
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Whether this volume pair is still believed to support hard links. Unknown pairs are tried. */
    private static boolean linkable(@Nullable VolumePair pair) {
        return pair == null || LINKABLE.getOrDefault(pair, Boolean.TRUE);
    }

    /**
     * Known-unlinkable volume pairs. Only negatives are cached: a pair that linked once may still fail
     * later (permissions, a full volume), and re-trying costs a failed {@code createLink} rather than
     * a wrong answer — whereas caching a positive and being wrong would mean a copy that silently
     * became a link.
     */
    private static final ConcurrentMap<VolumePair, Boolean> LINKABLE = new ConcurrentHashMap<>();

    /** The (source, target) filesystem pair, or {@code null} when either store cannot be read. */
    private record VolumePair(String source, String target) {
        static @Nullable VolumePair of(Path source, Path target) {
            try {
                Path parent = target.getParent();
                return new VolumePair(
                        Files.getFileStore(source).name(),
                        Files.getFileStore(parent == null ? target : parent).name());
            } catch (IOException | RuntimeException unknown) {
                return null; // cannot classify — try the link and let it answer
            }
        }
    }
}
