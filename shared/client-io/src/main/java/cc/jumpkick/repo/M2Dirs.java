// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.util.MinimalXml;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
 *   <li>the user settings file's top-level {@code <localRepository>} (no profile merge; the file
 *       is the one {@link MavenSettings#userSettingsPath()} names), its {@code ${env.NAME}} and
 *       {@code ${user.home}} expressions expanded for the request — see {@link MavenSettings#expand}
 *   <li>{@code ~/.m2/repository}
 * </ol>
 * An unparseable {@code settings.xml} localRepository, or one naming an expression that does not
 * expand, is warned once per run and skipped.
 */
public final class M2Dirs {

    /** The system property that names the local repository ahead of every other source; tests set it. */
    public static final String LOCAL_PROPERTY = "jk.m2.local";

    private M2Dirs() {}

    public static Path localRepository() {
        return localRepository(ProcessEnv.get("JK_M2_LOCAL"));
    }

    /**
     * The resolution above with {@code JK_M2_LOCAL} passed in. The environment is the one input a
     * test cannot clear — and the build sets {@code JK_M2_LOCAL} on every test JVM so the real
     * {@code ~/.m2} is never written — so the lower-precedence steps are only reachable through
     * this entry point.
     */
    static Path localRepository(@Nullable String override) {
        String prop = System.getProperty(LOCAL_PROPERTY);
        if (prop != null && !prop.isBlank()) return Path.of(prop.trim());
        if (override != null && !override.isBlank()) return Path.of(override.trim());
        return userRepository();
    }

    /**
     * The repository under a {@code .m2} directory the caller named ({@code jk install --m2-dir}),
     * else {@link #localRepository()}.
     */
    public static Path repository(@Nullable Path m2Dir) {
        return m2Dir == null ? localRepository() : m2Dir.resolve("repository");
    }

    /**
     * The user's own repository: the resolution above without the two redirects a test JVM is
     * given ({@code jk.m2.local}, {@code JK_M2_LOCAL}).
     */
    public static Path userRepository() {
        String mavenProp = System.getProperty("maven.repo.local");
        if (mavenProp != null && !mavenProp.isBlank()) return Path.of(mavenProp.trim());
        Path fromSettings = settingsLocalRepository();
        if (fromSettings != null) return fromSettings;
        return defaultRepository();
    }

    /**
     * Top-level {@code <localRepository>} only, expanded for the request; null when missing or
     * unusable. The parse is memoized on the file and the expansion runs per call, so the path a
     * request sees follows its own environment.
     */
    static @Nullable Path settingsLocalRepository() {
        Path settings = settingsXml();
        if (settings == null) return null;
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(settings);
        if (stamp == null) return null; // absent or unreadable
        String raw = SETTINGS_REPO.get(settings.toAbsolutePath().normalize(), stamp, () -> parseSettings(settings));
        if (raw == null) return null;
        MavenSettings.Expansion expanded = MavenSettings.expand(raw);
        if (!expanded.unresolved().isEmpty()) {
            String names = String.join(
                    ", ",
                    expanded.unresolved().stream().map(e -> "${" + e + "}").toList());
            RunNotices.warnOnce(
                    "m2-settings-localrepo-unexpanded:" + settings + " " + names,
                    () -> "jk: warning: " + settings + " <localRepository> names " + names
                            + ", which is not set; using " + defaultRepository());
            return null;
        }
        try {
            return Path.of(expanded.value());
        } catch (InvalidPathException notAPath) {
            RunNotices.warnOnce(
                    "m2-settings-localrepo-not-a-path:" + settings,
                    () -> "jk: warning: " + settings + " <localRepository> is not a path once expanded; using "
                            + defaultRepository());
            return null;
        }
    }

    /**
     * Memo of the {@code <localRepository>} parse, stamped on {@code (size, mtime)}.
     *
     * <p>{@code localRepository} is reached on <em>per-artifact</em> paths — {@code MavenRepo}
     * consults it when placing an artifact, when trying {@code ~/.m2}, and when trying the local
     * mirror — so a 500-artifact sync stat'ed, read and XML-parsed {@code ~/.m2/settings.xml} over a
     * thousand times for a value that cannot change during a resolve. {@code RunNotices.warnOnce}
     * beside the failure paths is a good hint that the authors expected repeat entry.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, @Nullable String> SETTINGS_REPO =
            StampedMemo.create();

    /** The {@code <localRepository>} text as written, stripped; null when absent or not a path. */
    private static @Nullable String parseSettings(Path settings) {
        try {
            MinimalXml.Element doc = MinimalXml.parse(Files.readString(settings));
            String raw =
                    doc.element("localRepository").map(MinimalXml.Element::text).orElse(null);
            if (raw == null || raw.isBlank()) return null;
            String trimmed = raw.strip();
            if (trimmed.indexOf('<') >= 0) {
                RunNotices.warnOnce(
                        "m2-settings-localrepo-not-plain",
                        () -> "jk: warning: ~/.m2/settings.xml <localRepository> is not a plain path; using "
                                + defaultRepository());
                return null;
            }
            return trimmed;
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

    /** The user settings file — {@link MavenSettings#userSettingsPath()}. */
    private static @Nullable Path settingsXml() {
        return MavenSettings.userSettingsPath();
    }

    private static Path defaultRepository() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
}
