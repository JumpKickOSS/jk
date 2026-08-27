// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.util.MinimalXml;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the Maven local repository root ({@code ~/.m2/repository} by default).
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code jk.m2.local} system property (tests)
 *   <li>{@code JK_M2_LOCAL} environment variable
 *   <li>{@code maven.repo.local} system property
 *   <li>user {@code ~/.m2/settings.xml} top-level {@code <localRepository>} (no profile merge;
 *       tests may point at a file via {@code jk.m2.settings})
 *   <li>{@code ~/.m2/repository}
 * </ol>
 * An unparseable {@code settings.xml} localRepository is warned once per run and skipped.
 */
public final class M2Dirs {

    private M2Dirs() {}

    public static Path localRepository() {
        return localRepository(System.getenv("JK_M2_LOCAL"));
    }

    /**
     * The resolution above with {@code JK_M2_LOCAL} passed in. The environment is the one input a
     * test cannot clear — and the build sets {@code JK_M2_LOCAL} on every test JVM so the real
     * {@code ~/.m2} is never written — so the lower-precedence steps are only reachable through
     * this entry point.
     */
    static Path localRepository(String override) {
        String prop = System.getProperty("jk.m2.local");
        if (prop != null && !prop.isBlank()) return Path.of(prop.trim());
        if (override != null && !override.isBlank()) return Path.of(override.trim());
        String mavenProp = System.getProperty("maven.repo.local");
        if (mavenProp != null && !mavenProp.isBlank()) return Path.of(mavenProp.trim());
        Path fromSettings = settingsLocalRepository();
        if (fromSettings != null) return fromSettings;
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    /** Top-level {@code <localRepository>} only; null when missing or unusable. */
    static Path settingsLocalRepository() {
        Path settings = settingsXml();
        if (settings == null) return null;
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(settings);
        if (stamp == null) return null; // absent or unreadable
        return SETTINGS_REPO.get(settings.toAbsolutePath().normalize(), stamp, () -> parseSettings(settings));
    }

    /**
     * Memo of the {@code <localRepository>} parse, stamped on {@code (size, mtime)}.
     *
     * <p>{@code localRepository} is reached on <em>per-artifact</em> paths — {@code MavenRepo}
     * consults it when placing an artifact, when trying {@code ~/.m2}, and when trying the local
     * mirror — so a 500-artifact sync stat'ed, read and XML-parsed {@code ~/.m2/settings.xml} over a
     * thousand times for a value that cannot change during a resolve. {@code RunNotices.warnOnce}
     * beside the failure paths is a good hint that the authors expected repeat entry (JK-1033).
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, Path> SETTINGS_REPO = StampedMemo.create();

    private static @Nullable Path parseSettings(Path settings) {
        try {
            MinimalXml.Element doc = MinimalXml.parse(Files.readString(settings));
            String raw =
                    doc.element("localRepository").map(MinimalXml.Element::text).orElse(null);
            if (raw == null || raw.isBlank()) return null;
            String trimmed = raw.strip();
            if (trimmed.contains("${") || trimmed.indexOf('<') >= 0) {
                RunNotices.warnOnce(
                        "m2-settings-localrepo-not-plain",
                        () -> "jk: warning: ~/.m2/settings.xml <localRepository> is not a plain path; using "
                                + defaultRepository()
                                + " (or JK_STORE_DIR repos on fetch fallback)");
                return null;
            }
            return Path.of(trimmed);
        } catch (Exception e) {
            RunNotices.warnOnce(
                    "m2-settings-localrepo-unparseable",
                    () -> "jk: warning: could not parse ~/.m2/settings.xml <localRepository> ("
                            + e.getMessage()
                            + "); using "
                            + defaultRepository());
            return null;
        }
    }

    /** {@code jk.m2.settings} (tests) or {@code ~/.m2/settings.xml}. */
    private static Path settingsXml() {
        String prop = System.getProperty("jk.m2.settings");
        if (prop != null && !prop.isBlank()) return Path.of(prop.trim());
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return Path.of(home, ".m2", "settings.xml");
    }

    private static Path defaultRepository() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
}
