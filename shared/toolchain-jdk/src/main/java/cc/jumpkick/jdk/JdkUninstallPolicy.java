// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What {@code jk jdk uninstall} may remove. jk deletes a JDK directory only when its real path is
 * under the managed JDK root; anything else goes through its owning tool's uninstall or stays. A
 * refused source is another owner's install (the OS, an IDE, Coursier's cache) or a pointer jk only
 * read from a file or the environment, and is refused wherever it sits.
 */
public final class JdkUninstallPolicy {

    private static final Map<String, String> REFUSED = Map.of(
            "system",
            "the OS package manager owns it",
            "intellij",
            "IntelliJ owns it; remove it under Project Structure > SDKs",
            "maven-toolchains",
            "it comes from Maven's toolchains.xml",
            "coursier",
            "it is in Coursier's JVM cache",
            "gradle-properties",
            "it comes from org.gradle.java.installations in gradle.properties",
            "jdk-paths",
            "it comes from JK_JDK_PATHS or JK_JDK_FROM_ENV");

    private JdkUninstallPolicy() {}

    /** Every source a probe can report that {@code jk jdk uninstall} refuses. */
    public static Set<String> refusedSources() {
        return REFUSED.keySet();
    }

    /** Whether {@code jk jdk uninstall} may remove an install reported under {@code source}. */
    public static boolean removable(String source) {
        return !REFUSED.containsKey(source);
    }

    /** The refusal for {@code source}, naming it; empty when the source is removable. */
    public static Optional<String> refusal(String source) {
        String reason = REFUSED.get(source);
        return reason == null
                ? Optional.empty()
                : Optional.of("jk jdk uninstall: not removing a `" + source + "` JDK; " + reason);
    }

    /**
     * The refusal for one hit, naming its source and home: a refused source, or a home outside
     * {@code managedRoot} that no owning-tool uninstall covers. Empty when the uninstall may proceed.
     */
    public static Optional<String> refusal(JdkHit hit, Path managedRoot) {
        String reason = REFUSED.get(hit.source());
        Optional<String> bySource = reason == null
                ? Optional.empty()
                : Optional.of("jk jdk uninstall: not removing the `" + hit.source() + "` JDK at " + hit.home() + "; "
                        + reason);
        if (bySource.isPresent() || JdkToolUninstaller.hasRecipe(hit) || deletable(hit.home(), managedRoot)) {
            return bySource;
        }
        return Optional.of(outsideRoot(hit, managedRoot));
    }

    /**
     * Whether jk may delete the install directory of {@code home} itself: its real path lies strictly
     * under the real path of {@code managedRoot}, so a symlink out of the root does not qualify.
     */
    public static boolean deletable(Path home, Path managedRoot) {
        Path install = real(IntellijJdkDir.installDirOf(home));
        Path root = real(managedRoot);
        return !install.equals(root) && install.startsWith(root);
    }

    /** Why jk leaves {@code hit}'s directory in place: it is outside {@code managedRoot}. */
    public static String outsideRoot(JdkHit hit, Path managedRoot) {
        return "jk jdk uninstall: not removing " + hit.home() + "; it is outside " + managedRoot
                + ", remove it with the tool that installed it";
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
