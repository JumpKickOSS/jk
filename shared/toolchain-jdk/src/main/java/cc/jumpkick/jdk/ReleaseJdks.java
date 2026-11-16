// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The JDK build this jk release prefers for an unpinned module, per supported major: what a module
 * with no {@code jdk} pin, {@code .jdk-version}, lock requirement or {@code --jdk} compiles and
 * tests on, and what the engine runs on, when the host has it installed. Hosts that have it share
 * the module's keys (keys name the JDK build exactly, {@code JdkToolchain}). A preference only: jk
 * never downloads it and never requires it; a host without it builds on any installed JDK that
 * satisfies the module, and a pin of the user's wins (JK-ADBJV).
 *
 * <p>Updated at each release to the newest build of each vendor line (docs/contributors/releases.md,
 * "Cutting a release"); {@link #REVIEWED} says when it last was.
 */
public final class ReleaseJdks {

    /** When the table was last brought to the feed's newest builds. */
    public static final LocalDate REVIEWED = LocalDate.of(2026, 11, 15);

    private ReleaseJdks() {}

    /** Every major this release names a build for, ascending, to its install spec. */
    public static Map<Integer, String> builds() {
        Map<Integer, String> out = new TreeMap<>();
        for (int major = SupportedJdk.MIN_MAJOR; major <= 99; major++) {
            int m = major;
            spec(m).ifPresent(spec -> out.put(m, spec));
        }
        return out;
    }

    /** The build this release names for {@code major}, as an install spec. */
    public static Optional<String> spec(int major) {
        return Optional.ofNullable(
                switch (major) {
                    case 17 -> "temurin-17.0.20.1";
                    case 21 -> "temurin-21.0.12.1";
                    case 25 -> "temurin-25.0.4.1";
                    case 26 -> "temurin-26.0.2.1";
                    case 27 -> "temurin-27.0.1.0.2";
                    default -> null;
                });
    }

    /**
     * The major an unpinned module runs on: the latest LTS, unless its {@code java} level is newer,
     * as the resolution's java-release floor has it.
     */
    public static int majorFor(int javaRelease, int latestLtsMajor) {
        return javaRelease > latestLtsMajor ? javaRelease : latestLtsMajor;
    }

    /** The release build for an unpinned module at {@code javaRelease}. */
    public static Optional<String> specFor(int javaRelease, int latestLtsMajor) {
        return spec(majorFor(javaRelease, latestLtsMajor));
    }

    /** The version a spec names, {@code 25.0.4.1} of {@code temurin-25.0.4.1}. */
    public static String version(String spec) {
        return spec.substring(spec.indexOf('-') + 1);
    }

    /** The vendor a spec names, {@code temurin} of {@code temurin-25.0.4.1}. */
    public static String vendor(String spec) {
        int dash = spec.indexOf('-');
        return dash < 0 ? spec : spec.substring(0, dash);
    }

    /**
     * Whether {@code installedVersion} (a JDK's {@code JAVA_VERSION} or probe version, {@code
     * 25.0.4.1} or {@code 25.0.4.1+1}) is the build {@code spec} names.
     */
    public static boolean isBuild(String spec, @Nullable String installedVersion) {
        if (installedVersion == null) return false;
        String v = installedVersion.strip();
        int plus = v.indexOf('+');
        if (plus > 0) v = v.substring(0, plus);
        return v.equals(version(spec));
    }

    /**
     * Whether {@code home} is this release's build of its major, by its {@code release} file: the
     * vendor line ({@code IMPLEMENTOR}) and the full {@code JAVA_VERSION}.
     */
    public static boolean isReleaseHome(@Nullable Path home) {
        if (home == null) return false;
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) return false;
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(release, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            return false;
        }
        String version = unquote(props.getProperty("JAVA_VERSION"));
        String implementor = unquote(props.getProperty("IMPLEMENTOR"));
        Integer major = version == null ? null : JdkKeywords.leadingMajor(version);
        if (major == null) return false;
        Optional<String> spec = spec(major);
        return spec.isPresent() && isBuild(spec.get(), version) && vendorIs(vendor(spec.get()), implementor);
    }

    private static boolean vendorIs(String vendor, @Nullable String implementor) {
        if (implementor == null) return false;
        return switch (vendor) {
            case "temurin" -> implementor.equals("Eclipse Adoptium");
            default -> false;
        };
    }

    private static @Nullable String unquote(@Nullable String raw) {
        if (raw == null) return null;
        String v = raw.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        return v.isEmpty() ? null : v;
    }

    /**
     * Whether a JDK at version {@code installed} is older than this release's build of its major,
     * comparing dotted numbers; false when the major has no build here.
     */
    public static boolean olderThanRelease(String installed) {
        Integer major = JdkKeywords.leadingMajor(installed);
        if (major == null) return false;
        Optional<String> spec = spec(major);
        return spec.isPresent()
                && JdkSelector.versionKey(installed).compareTo(JdkSelector.versionKey(version(spec.get()))) < 0;
    }
}
