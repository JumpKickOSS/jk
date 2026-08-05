// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes secret files as {@code 0600} inside a {@code 0700} directory. On non-POSIX filesystems
 * permission tightening is a best-effort no-op.
 */
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {}

    /**
     * Write {@code content} to {@code file} readable only by the owner, ensuring {@code dir} is
     * {@code 0700}.
     *
     * <p>A fresh file is <em>created</em> {@code 0600} rather than created-then-tightened: these
     * files hold secrets, and the gap between an umask-default create and the chmod is a window in
     * which another local user can read one.
     */
    public static void write(Path dir, Path file, String content) throws IOException {
        Files.createDirectories(dir);
        setOwnerOnly(dir, "rwx------");
        if (!Files.exists(file) && Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (java.nio.file.FileAlreadyExistsException | UnsupportedOperationException ignored) {
                // raced or non-POSIX — the tighten below still applies
            }
        }
        Files.writeString(
                file,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        setOwnerOnly(file, "rw-------");
    }

    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    /** Best-effort tighten POSIX permissions on {@code path}; a no-op where POSIX perms are unsupported. */
    public static void setOwnerOnly(Path path, String perms) {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return; // non-POSIX (Windows) — nothing to tighten
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (IOException ignored) {
            // best-effort; the file still exists with default umask perms
        }
    }
}
