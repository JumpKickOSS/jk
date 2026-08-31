// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Stable {@code <vendor>-<major>} handle under {@link JkDirs#jdks()} (symlink; Windows junction) that
 * tracks the current patch install so IDE {@code homePath}s survive point-release upgrades. Superseded
 * dirs go to {@link JdkGarbage}; {@link #javaHome} does not resolve the link.
 */
public final class StableJdkPointer {

    private final Path jdksRoot;

    public StableJdkPointer(Path jdksRoot) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
    }

    /** Pointer rooted at jk's default managed JDK directory. */
    public static StableJdkPointer atDefaultRoot() {
        return new StableJdkPointer(JkDirs.jdks());
    }

    /**
     * {@code <jdksRoot>/<pointerName>} — the pointer path (link or, degenerate, the install itself).
     */
    public Path pointerDir(String pointerName) {
        return jdksRoot.resolve(pointerName);
    }

    /**
     * Stable {@code JAVA_HOME} for the pointer, resolving the macOS {@code Contents/Home} bundle
     * layout but <em>not</em> the link itself, so the returned path stays {@code
     * <jdksRoot>/<pointerName>[/Contents/Home]}.
     */
    public Path javaHome(String pointerName) {
        return IntellijJdkDir.javaHome(pointerDir(pointerName));
    }

    /**
     * Ensure {@code <jdksRoot>/<pointerName>} resolves to {@code installDir}. Idempotent: a no-op
     * when the link already resolves there, or when the pointer name already <em>is</em> the install
     * dir (degenerate case where a vendor's version equals its major, e.g. a bare {@code
     * graalvm-25}). A no-op when {@code installDir} doesn't exist.
     */
    public void ensure(String pointerName, Path installDir) throws IOException {
        Objects.requireNonNull(pointerName, "pointerName");
        Objects.requireNonNull(installDir, "installDir");
        if (!Files.exists(installDir)) return;

        Path pointer = jdksRoot.resolve(pointerName);
        Path canonicalInstall = installDir.toRealPath();

        if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // toRealPath resolves a symlink OR a Windows junction; if it
                // already lands on the install (or IS the install dir), done.
                if (pointer.toRealPath().equals(canonicalInstall)) return;
            } catch (IOException dangling) {
                // fall through and recreate
            }
            removeExisting(pointer);
        }

        Files.createDirectories(jdksRoot);
        DirLinks.replace(pointer, installDir);
    }

    /**
     * Remove a pointer whether it's a POSIX symlink, a Windows junction, or a real directory —
     * without ever recursing <em>through</em> a link, and without ever deleting a JDK jk did not
     * install.
     *
     * <p>{@link Files#delete} removes a symlink, a junction, or an empty dir in one shot and never
     * follows the link, so a junction's target is untouched. Only a genuinely populated directory
     * reaches the recursive branch (a junction never does — {@code delete} succeeds on it first).
     * This is the load-bearing invariant: {@link Files#walk} would descend into a junction (Java
     * doesn't classify junctions as symlinks) and delete the real JDK.
     *
     * <p><strong>And a populated directory is not automatically ours to remove.</strong> The
     * pointer name is {@code <vendor>-<major>} and {@link JkDirs#jdks()} is a <em>shared</em> root —
     * IntelliJ's {@code ~/.jdks}, which is the entire point of installing there. So {@code
     * graalvm-25} is simultaneously a name jk wants and, on a real machine, very often a JDK the
     * user or the IDE put there first. This branch used to assume any real directory in the way was
     * jk's own leftover from a repoint and delete it outright; on the developer's machine that
     * silently destroyed a GraalVM 25 and a Temurin install, leaving empty directories behind
     * (JK-2624). {@link JdkOwnership} exists precisely to tell the two apart — it was written so
     * "uninstall/GC never deletes an alien install that happens to share the IntelliJ JDK root" —
     * and this site simply never asked it.
     *
     * <p>Refusing throws rather than returning quietly: every caller treats a pointer failure as
     * non-fatal (the pointer is a convenience, the install is already complete), so the build still
     * succeeds — but the reason lands somewhere a human can read instead of a JDK going missing.
     */
    private static void removeExisting(Path pointer) throws IOException {
        try {
            Files.delete(pointer);
        } catch (NoSuchFileException gone) {
            // already removed
        } catch (DirectoryNotEmptyException realDir) {
            // A populated directory sits where the pointer belongs. Ours to reclaim only if we
            // put it there.
            if (!JdkOwnership.isJkOwned(pointer)) {
                throw new IOException("refusing to remove " + pointer
                        + " to free the stable pointer name: it is a JDK not installed by jk (no "
                        + JdkOwnership.MARKER + " marker). Remove it yourself, or install jk's JDK "
                        + "under a different root with JK_JDKS_DIR.");
            }
            PathUtil.deleteRecursively(pointer);
        }
    }
}
