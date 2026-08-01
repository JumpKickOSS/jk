// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * CAS/ActionCache materialization: hard-link when possible, else copy. Safe for build outputs
 * because writers use create/truncate or temp-and-rename (they break the link, not mutate the
 * shared file). Deletes any existing {@code target} first.
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
        try {
            Files.createLink(target, source);
            return;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // Fall through to copy. UnsupportedOperationException covers
            // filesystems that don't implement hard links at all
            // (some FUSE mounts); FileSystemException covers cross-volume,
            // non-NTFS Windows volumes, and permission-denied cases.
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
    }
}
