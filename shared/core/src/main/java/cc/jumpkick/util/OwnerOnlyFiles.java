// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * <p>The bytes go into a sibling <em>created</em> {@code 0600} and renamed over {@code file}:
     * these files hold secrets, and both an umask-default create followed by a chmod and an in-place
     * rewrite of an existing looser file are windows in which another local user can read one. An
     * existing file is never written through — a second name for its inode would carry the secret
     * at the old mode — it is replaced.
     */
    public static void write(Path dir, Path file, String content) throws IOException {
        directory(dir);
        Path parent = file.toAbsolutePath().getParent();
        Path tmp = staging(parent == null ? dir : parent, file);
        boolean moved = false;
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            AtomicWrites.moveInto(tmp, file);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
        setOwnerOnly(file, "rw-------");
    }

    /** A sibling of {@code file} created {@code 0600}; on a non-POSIX filesystem, a plain temp file. */
    private static Path staging(Path parent, Path file) throws IOException {
        String prefix = "." + file.getFileName() + "-";
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) == null) {
            return Files.createTempFile(parent, prefix, ".tmp");
        }
        try {
            return Files.createTempFile(parent, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException noPosixAttrs) {
            return Files.createTempFile(parent, prefix, ".tmp");
        }
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
