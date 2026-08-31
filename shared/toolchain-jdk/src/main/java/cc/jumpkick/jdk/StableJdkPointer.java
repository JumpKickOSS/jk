// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
            JkOwnership.removeIfOwned(pointer);
        }

        Files.createDirectories(jdksRoot);
        DirLinks.replace(pointer, installDir);
    }

}
