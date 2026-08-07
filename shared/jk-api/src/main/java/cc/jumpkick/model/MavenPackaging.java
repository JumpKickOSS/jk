// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;
import java.util.Map;

/**
 * Maven packaging type → published file extension.
 *
 * <p>A packaging type is not an extension. Maven publishes {@code test-jar} packaging as
 * {@code artifact-version-tests.jar}, and {@code bundle} / {@code maven-plugin} / {@code ejb} as
 * plain jars. Treating the type as the extension asks a repository for a file that was never
 * published — the fetch 404s and the dependency silently drops off the classpath.
 *
 * <p>Types not listed here are their own extension ({@code jar}, {@code war}, {@code aar},
 * {@code pom}, {@code zip}, …), which is the common case.
 */
public final class MavenPackaging {

    /** Packaging types whose published file is something other than the type name. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "test-jar", "jar",
            "bundle", "jar",
            "maven-plugin", "jar",
            "ejb", "jar",
            "ejb-client", "jar",
            "java-source", "jar",
            "javadoc", "jar",
            "gwt-lib", "jar");

    private MavenPackaging() {}

    /** File extension for a packaging type; {@code jar} when the type is absent. */
    public static String extensionOf(String type) {
        if (type == null || type.isBlank()) return "jar";
        String key = type.trim().toLowerCase(Locale.ROOT);
        return EXTENSIONS.getOrDefault(key, key);
    }
}
