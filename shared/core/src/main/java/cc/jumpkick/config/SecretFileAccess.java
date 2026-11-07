// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether jk may read a secret file: the POSIX permission rule behind {@code ${file:}} and {@code
 * JK_REPO_<ID>_*_FILE}.
 *
 * <p><b>Everywhere</b> a file its group or other users can read is refused. A secret anyone on the
 * machine can read is not one the user meant to keep, and the refusal names the {@code chmod 600},
 * Kubernetes {@code defaultMode} and Docker {@code mode} fixes.
 *
 * <p><b>Under a secret-mount root</b> ({@link #MOUNT_ROOTS}: Docker and Compose mount secrets in
 * {@code /run/secrets}, Kubernetes its service-account and workload-identity tokens in {@code
 * /var/run/secrets}) a readable file is accepted when nobody but its owner can write it and that
 * owner is root or the user jk runs as. Those are the defaults ({@code 0444}, {@code 0644}) the
 * platforms mount with, and the trees are root's: only root creates a directory under {@code /run}.
 * So the location, not a guess about being in a container, carries the trust. What it accepts:
 * another local user may read the same file, which the mount already allows whether jk reads it or
 * not. What it still refuses: a file someone else owns or can rewrite, so nobody but root or the
 * user can choose the credential jk sends. The judgement follows symlinks — a Kubernetes volume's
 * {@code ..data} links — and is made on the file they reach, which must itself lie under a root.
 *
 * <p>A file system without POSIX permissions (Windows) is not checked.
 */
final class SecretFileAccess {

    /** Where container platforms mount secrets by default. */
    static final List<Path> MOUNT_ROOTS = List.of(Path.of("/run/secrets"), Path.of("/var/run/secrets"));

    /** The rule as jk applies it. */
    static final SecretFileAccess DEFAULT = new SecretFileAccess(MOUNT_ROOTS);

    private final List<Path> mountRoots;

    /** The rule with {@code mountRoots} as the secret-mount roots; a test passes its own. */
    SecretFileAccess(List<Path> mountRoots) {
        this.mountRoots = List.copyOf(mountRoots);
    }

    /** Throws naming {@code where} and the fix when {@code file} may not be read as a secret. */
    void check(Path file, String where) {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view == null) return;
        PosixFileAttributes attrs;
        try {
            attrs = view.readAttributes();
        } catch (IOException | UnsupportedOperationException e) {
            return;
        }
        Set<PosixFilePermission> perms = attrs.permissions();
        if (!perms.contains(PosixFilePermission.GROUP_READ) && !perms.contains(PosixFilePermission.OTHERS_READ)) return;
        Path real = realPath(file);
        if (real != null && underMountRoot(real)) {
            String why = mountRefusal(perms, attrs.owner().getName(), real);
            if (why == null) return;
            throw new JkBuildParseException(
                    where + ": secret " + file + " " + why + "; only root or you may own and write it");
        }
        throw new JkBuildParseException(
                where + ": secret " + file + " is readable by other users; run chmod 600 " + file);
    }

    /** Why a readable file under a mount root is still refused, or null when it is accepted. */
    private static @Nullable String mountRefusal(Set<PosixFilePermission> perms, String owner, Path real) {
        if (perms.contains(PosixFilePermission.GROUP_WRITE) || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
            return "is writable by other users";
        }
        if (!owner.equals("root") && !owner.equals(System.getProperty("user.name")) && !rootUid(real)) {
            return "is owned by " + owner;
        }
        return null;
    }

    /** True when {@code file}'s owner is uid 0, whatever the name lookup says. */
    private static boolean rootUid(Path file) {
        try {
            return Files.getAttribute(file, "unix:uid") instanceof Integer uid && uid == 0;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean underMountRoot(Path real) {
        return rootOf(real) != null;
    }

    /** The mount root {@code real} lies under, compared as real paths ({@code /var/run} is often {@code /run}). */
    private @Nullable Path rootOf(Path real) {
        for (Path root : realRoots()) {
            if (real.startsWith(root) && !real.equals(root)) return root;
        }
        return null;
    }

    private List<Path> realRoots() {
        List<Path> out = new ArrayList<>();
        for (Path root : mountRoots) {
            Path real = realPath(root);
            out.add(real != null ? real : root.toAbsolutePath().normalize());
        }
        return out;
    }

    private static @Nullable Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}
