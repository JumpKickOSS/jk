// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * CAS/ActionCache materialization: hard-link when possible, else copy. Deletes any existing
 * {@code target} first.
 *
 * <p><strong>A hard link is only safe for a consumer that never writes through it.</strong>
 * {@code open(O_TRUNC)} does <em>not</em> break a hard link: it truncates the shared inode, so every
 * other name for that file sees it. Only temp-and-rename breaks the link.
 *
 * <p>Link out of the CAS only for a consumer that reads (a classpath entry, a launcher's lib dir).
 * A consumer that may rewrite the file gets a copy. {@code ActionCache} copies compile outputs
 * rather than linking them, because compilers rewrite class files in place. Linking <em>into</em>
 * the CAS is safe in the other direction: a blob's name is its content hash and nothing rewrites
 * one in place.
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
        linkOrCopy(source, target, () -> {});
    }

    /**
     * Test seam: {@code betweenDeleteAndLink} runs in the window where a racing materialiser of the
     * same target can land, between the delete of {@code target} and the link attempt.
     */
    static void linkOrCopy(Path source, Path target, Interleave betweenDeleteAndLink) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.deleteIfExists(target);
        betweenDeleteAndLink.run();
        VolumePair pair = VolumePair.of(source, target);
        if (linkable(pair)) {
            try {
                createLinkRetryingOnce(source, target);
                return;
            } catch (UnsupportedOperationException notLinkable) {
                markUnlinkable(pair);
            } catch (NoSuchFileException gone) {
                // The blob is missing (pruned concurrently) — a copy cannot succeed either, and the
                // volume pair has said nothing about links.
                throw gone;
            } catch (FileSystemException failed) {
                // Only a verdict about the volume pair is worth remembering. A lost race for the same
                // target, a permissions problem or a full volume is about this file, not about
                // whether the pair can link; caching those would demote every later materialisation
                // on the pair to a copy for the rest of the process.
                if (isVolumeVerdict(failed)) markUnlinkable(pair);
            }
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Link {@code target} to {@code source}. Two materialisers of the same blob into the same target
     * can interleave between the delete and the link; the loser sees {@link
     * FileAlreadyExistsException}, deletes what the winner wrote and links again. The second attempt
     * raising it again means a writer is still busy — the caller's copy replaces the entry.
     */
    private static void createLinkRetryingOnce(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
        } catch (FileAlreadyExistsException raced) {
            Files.deleteIfExists(target);
            Files.createLink(target, source);
        }
    }

    /**
     * True when the failure describes the (source, target) volume pair rather than this file:
     * a cross-device link, or a filesystem that does not implement hard links at all.
     */
    static boolean isVolumeVerdict(FileSystemException failed) {
        if (failed instanceof FileAlreadyExistsException || failed instanceof NoSuchFileException) return false;
        String reason = failed.getReason();
        if (reason == null) return false;
        String r = reason.toLowerCase(Locale.ROOT);
        return r.contains("cross-device")
                || r.contains("cross device")
                || r.contains("not supported")
                || r.contains("not permitted")
                || r.contains("incorrect function")
                || r.contains("invalid function");
    }

    /** Whether this volume pair is still believed to support hard links. Unknown pairs are tried. */
    private static boolean linkable(@Nullable VolumePair pair) {
        return pair == null || LINKABLE.getOrDefault(pair, Boolean.TRUE);
    }

    private static void markUnlinkable(@Nullable VolumePair pair) {
        // Remember it for this volume pair rather than rediscovering it per file. On a mount that
        // refuses links, a failed createLink is 79.6 us on Windows (17x Linux) before the 305 us
        // copy — answer the capability question once per pair, not once per file.
        if (pair != null) LINKABLE.put(pair, Boolean.FALSE);
    }

    /** Test seam: forget every cached verdict. */
    static void forgetVerdicts() {
        LINKABLE.clear();
    }

    /**
     * Known-unlinkable volume pairs. Only negatives are cached: a pair that linked once may still fail
     * later (permissions, a full volume), and re-trying costs a failed {@code createLink} rather than
     * a wrong answer — whereas caching a positive and being wrong would mean a copy that silently
     * became a link.
     */
    private static final ConcurrentMap<VolumePair, Boolean> LINKABLE = new ConcurrentHashMap<>();

    /** A step that may touch the filesystem, run between the delete and the link. */
    @FunctionalInterface
    interface Interleave {
        void run() throws IOException;
    }

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
