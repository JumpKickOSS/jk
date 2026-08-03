// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Marks JDK install trees that JumpKick extracted, so uninstall/GC never deletes an alien
 * install that happens to share the IntelliJ JDK root ({@code ~/.jdks} /
 * {@code ~/Library/Java/JavaVirtualMachines}).
 */
public final class JdkOwnership {

    /** Marker file name inside an install directory (or next to a macOS {@code .jdk} bundle). */
    public static final String MARKER = ".jk-owned";

    private JdkOwnership() {}

    /** Write the ownership marker under {@code installDir} (best-effort; never throws). */
    public static void mark(Path installDir) {
        if (installDir == null) return;
        try {
            Files.createDirectories(installDir);
            Path marker = installDir.resolve(MARKER);
            if (!Files.exists(marker)) {
                Files.writeString(marker, "jumpkick\n");
            }
        } catch (IOException ignored) {
            // Best-effort: missing marker only affects attribution/uninstall, not runtime use.
        }
    }

    /**
     * Whether {@code installDir} (the tree under the jdks root, not necessarily JAVA_HOME) was
     * marked as jk-installed.
     */
    public static boolean isJkOwned(Path installDir) {
        if (installDir == null) return false;
        return Files.isRegularFile(installDir.resolve(MARKER));
    }

    /**
     * Resolve the install directory from a JAVA_HOME (unwraps macOS {@code Contents/Home}) and
     * check ownership.
     */
    public static boolean isJkOwnedJavaHome(Path javaHome) {
        return isJkOwned(IntellijJdkDir.installDirOf(javaHome));
    }
}
