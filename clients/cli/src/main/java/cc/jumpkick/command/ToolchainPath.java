// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.host.SearchPath;
import java.nio.file.Path;

/**
 * Surgical {@code PATH} edits for {@code jk hook-env}: swap {@code JAVA_HOME/bin} and {@code
 * GRAALVM_HOME/bin} onto the live search path without freezing or replacing unrelated entries (nvm,
 * pyenv, user prepends, …).
 */
public final class ToolchainPath {

    private ToolchainPath() {}

    /**
     * Remove bins for the homes jk is leaving (and the homes it is about to prepend, to avoid
     * duplicates), then prepend {@code toJavaHome/bin} and — when distinct — {@code
     * toGraalHome/bin}. Pass null {@code to*} homes to strip only (session leave / deactivate).
     */
    public static String swap(
            String currentPath, String fromJavaHome, String fromGraalHome, String toJavaHome, String toGraalHome) {
        String path = currentPath == null ? "" : currentPath;
        path = removeHomeBin(fromJavaHome, path);
        path = removeHomeBin(fromGraalHome, path);
        path = removeHomeBin(toJavaHome, path);
        path = removeHomeBin(toGraalHome, path);
        // Graal first, then Java: java/javac win; native-image still resolves from Graal when
        // the JDK home does not ship it.
        String graalBin = binOf(toGraalHome);
        String javaBin = binOf(toJavaHome);
        if (graalBin != null && !graalBin.equals(javaBin)) {
            path = SearchPath.prepend(graalBin, path);
        }
        if (javaBin != null) {
            path = SearchPath.prepend(javaBin, path);
        }
        return path;
    }

    static String binOf(String home) {
        if (home == null || home.isBlank()) return null;
        return Path.of(home).resolve("bin").toString();
    }

    private static String removeHomeBin(String home, String path) {
        String bin = binOf(home);
        return bin == null ? path : SearchPath.remove(bin, path);
    }
}
