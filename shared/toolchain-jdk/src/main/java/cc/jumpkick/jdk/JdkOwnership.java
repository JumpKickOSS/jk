// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.util.JkOwnership;
import java.nio.file.Path;

/**
 * JDK-shaped face of {@link JkOwnership}: marks install trees JumpKick extracted, so uninstall/GC
 * never deletes an alien install that happens to share the IntelliJ JDK root ({@code ~/.jdks} /
 * {@code ~/Library/Java/JavaVirtualMachines}).
 *
 * <p>The marker itself is not JDK vocabulary — {@code <home>/bin} and the tool store ask the same
 * question — so it lives in {@link JkOwnership} and this type adds only the part that is about
 * JDKs: unwrapping a macOS {@code Contents/Home} bundle to find the tree the marker sits in.
 */
public final class JdkOwnership {

    /** Marker file name inside an install directory (or next to a macOS {@code .jdk} bundle). */
    public static final String MARKER = JkOwnership.MARKER;

    private JdkOwnership() {}

    /** Write the ownership marker under {@code installDir} (best-effort; never throws). */
    public static void mark(Path installDir) {
        JkOwnership.mark(installDir);
    }

    /**
     * Whether {@code installDir} (the tree under the jdks root, not necessarily JAVA_HOME) was
     * marked as jk-installed.
     */
    public static boolean isJkOwned(Path installDir) {
        return JkOwnership.isOwned(installDir);
    }

    /**
     * Resolve the install directory from a JAVA_HOME (unwraps macOS {@code Contents/Home}) and
     * check ownership.
     */
    public static boolean isJkOwnedJavaHome(Path javaHome) {
        return isJkOwned(IntellijJdkDir.installDirOf(javaHome));
    }
}
