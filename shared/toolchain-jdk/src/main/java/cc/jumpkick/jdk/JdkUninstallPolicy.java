// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which discovery sources {@code jk jdk uninstall} refuses. A refused source is either another
 * owner's install (the OS, an IDE, Coursier's cache) or a pointer jk only read from a file or the
 * environment; jk never deletes either.
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

    /** As {@link #refusal(String)} for one hit, naming its home too. */
    public static Optional<String> refusal(JdkHit hit) {
        Path home = hit.home();
        return refusal(hit.source()).map(message -> message + " Nothing was deleted at " + home + ".");
    }
}
