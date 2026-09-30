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
            "it is managed by the OS package manager (remove it with apt, dnf, brew, or the OS installer)",
            "intellij",
            "it is registered in your IDE (remove it in IntelliJ under Project Structure ▸ SDKs)",
            "maven-toolchains",
            "it is listed in Maven's toolchains.xml, which jk only reads",
            "coursier",
            "it is in Coursier's JVM cache, which Coursier and Mill own and Coursier has no uninstall for",
            "gradle-properties",
            "it is named by org.gradle.java.installations in a gradle.properties file, which jk only reads",
            "jdk-paths",
            "it is named by JK_JDK_PATHS or JK_JDK_FROM_ENV, which jk only reads");

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
                : Optional.of("jk jdk uninstall: refusing to remove a `" + source + "` JDK: " + reason + ".");
    }

    /**
     * The refusal for one hit, naming its source and home: a refused source, or a home outside
     * {@code managedRoot} that no owning-tool uninstall covers. Empty when the uninstall may proceed.
     */
    public static Optional<String> refusal(JdkHit hit, Path managedRoot) {
        Optional<String> bySource =
                refusal(hit.source()).map(message -> message + " Nothing was deleted at " + hit.home() + ".");
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
        return "jk jdk uninstall: refusing to delete a `" + hit.source() + "` JDK at " + hit.home()
                + ": it is outside jk's JDK root (" + managedRoot + "), and jk deletes only JDKs under that root."
                + " Remove it with the tool that installed it. Nothing was deleted.";
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
