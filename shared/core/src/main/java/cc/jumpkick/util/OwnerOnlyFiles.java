// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes secret files as {@code 0600} inside a {@code 0700} directory, and creates the directories
 * jk's own security rests on {@code 0700}. Everything jk creates under the state root
 * ({@link #inState}) goes through here or {@link AtomicWrites}, which applies the same modes there,
 * so no file or directory in it depends on the umask. On non-POSIX filesystems permission
 * tightening is a best-effort no-op.
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

    /**
     * {@link Files#createDirectories}, with every directory it creates {@code 0700} when {@code dir}
     * lies under the state root ({@link #inState}).
     */
    public static Path createDirectories(Path dir) throws IOException {
        if (!inState(dir)) return Files.createDirectories(dir);
        directory(dir);
        return dir;
    }

    /** {@link Files#createDirectory} at {@code 0700}: fails when {@code dir} exists. */
    public static Path createDirectory(Path dir) throws IOException {
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null) {
            return Files.createDirectory(dir);
        }
        return Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
    }

    /**
     * Give every file under {@code root} mode {@code 0600} and every directory {@code 0700}. For a
     * staging tree jk wrote into a {@code 0700} directory and is about to publish under the state
     * root; best-effort like {@link #setOwnerOnly}.
     */
    public static void seal(Path root) throws IOException {
        if (Files.getFileAttributeView(root, PosixFileAttributeView.class) == null) return;
        PathUtil.forEachEntry(root, dir -> false, (p, attrs) -> {
            if (attrs.isDirectory()) tighten(p, OWNER_ONLY_DIR);
            else if (attrs.isRegularFile()) tighten(p, OWNER_ONLY);
            return true;
        });
    }

    /** True when {@code path} lies under the state root, where every file is 0600 and every directory 0700. */
    public static boolean inState(Path path) {
        return path.toAbsolutePath()
                .normalize()
                .startsWith(JkDirs.state().toAbsolutePath().normalize());
    }

    /**
     * Open {@code file} with {@code options}, creating it {@code 0600} and its parent {@code 0700}.
     * An existing file that lets group or others in is tightened before the channel is returned. For
     * jk's own files only: the parent directory is tightened too.
     */
    public static FileChannel channel(Path file, OpenOption... options) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) directory(parent);
        Set<OpenOption> opts = Set.of(options);
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
            return FileChannel.open(file, opts);
        }
        FileChannel ch = FileChannel.open(file, opts, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        tighten(file, OWNER_ONLY);
        return ch;
    }

    /** Create {@code file} empty and {@code 0600} unless it exists; an existing looser one is tightened. */
    public static Path touch(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) directory(parent);
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
            if (!Files.exists(file)) Files.createFile(file);
            return file;
        }
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (FileAlreadyExistsException exists) {
            tighten(file, OWNER_ONLY);
        }
        return file;
    }

    /** Write {@code content} (UTF-8) to {@code file} in place, {@code 0600}; see {@link #channel}. */
    public static void writeString(Path file, String content, OpenOption... options) throws IOException {
        OpenOption[] opts = options.length > 0
                ? options
                : new OpenOption[] {
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
                };
        ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        try (FileChannel ch = channel(file, opts)) {
            while (bytes.hasRemaining()) ch.write(bytes);
        }
    }

    private static void tighten(Path path, Set<PosixFilePermission> mode) {
        try {
            if (!mode.equals(Files.getPosixFilePermissions(path))) Files.setPosixFilePermissions(path, mode);
        } catch (IOException | UnsupportedOperationException ignored) {
            // best-effort, like every other chmod here; jk doctor reports what remains loose
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
