// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes secret files as {@code 0600} inside a {@code 0700} directory, and creates the directories
 * jk's own security rests on {@code 0700}. On non-POSIX filesystems permission tightening is a
 * best-effort no-op.
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
            } catch (FileAlreadyExistsException | UnsupportedOperationException ignored) {
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

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");

    /**
     * Create {@code dir} (and any missing parents) {@code rwx------}, or tighten a pre-existing one
     * that lets group or others in. Parents that already exist are left as they are.
     *
     * <p>The create itself carries the mode, so there is no umask-default window; a failed tighten
     * is best-effort like every other chmod here, and {@code jk doctor} reports what remains loose.
     * On non-POSIX filesystems this is a plain create.
     */
    public static void directory(Path dir) throws IOException {
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null) {
            Files.createDirectories(dir);
            return;
        }
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
        if (!OWNER_ONLY_DIR.equals(Files.getPosixFilePermissions(dir))) {
            setOwnerOnly(dir, "rwx------");
        }
    }

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
