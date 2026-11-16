// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Which JDK's records a compile or a suite may take. Compile keys and run-tests stamps name the
 * module's Java level ({@code java:<level>}), not the JDK that ran; a record says which JDK made
 * it in its {@code TOOLCHAIN} line, and this class is the reader's policy over that field: the match
 * level ({@link Match}, {@code major} by default), a record from a JDK below the module's level
 * never, and an organisation's hard requirement ({@link Requirement}) when the project makes it
 * binding for the cache. Among acceptable records the closest wins ({@link Expectation#closeness}).
 *
 * <p>The step that computes a key says what its reader expects ({@link #expect}), for the rest of
 * the request; the action cache consults that when it reads the key ({@link #of}). A key no step described (packaging, plugin
 * steps, guard lanes: their keys still name the JDK) is read as before, any record taken.
 */
public final class ToolchainPolicy {

    private ToolchainPolicy() {}

    /** How close a record's JDK must be to the JDK this build uses. */
    public enum Match {
        /** Any JDK whose major is at least the module's level (opt-in, loosest). */
        LEVEL,
        /** The same Java major, any vendor and patch (the default). */
        MAJOR,
        /** The same vendor, version and build (opt-in, strictest). */
        BUILD;

        /** {@code level}, {@code major} or {@code build}; empty for anything else. */
        public static Optional<Match> parse(@Nullable String raw) {
            if (raw == null) return Optional.empty();
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "level" -> Optional.of(LEVEL);
                case "major" -> Optional.of(MAJOR);
                case "build" -> Optional.of(BUILD);
                default -> Optional.empty();
            };
        }

        /** The word in configuration and messages. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * An organisation's requirement, from the project's {@code jdk = "=<vendor>-<version>"} pin: a
     * vendor and a version that is a major ({@code 21}) or a version prefix ({@code 21.0.4}), binding
     * for the cache when {@code jdk-cache = "required"} sits beside it.
     */
    public record Requirement(String vendor, String version, boolean bindsCache) {

        /** Whether {@code toolchain} (a {@link JdkToolchain} token) is a JDK this requirement admits. */
        public boolean admits(String toolchain) {
            Toolchain t = Toolchain.parse(toolchain);
            if (t == null) return false;
            return vendorMatches(t.vendor()) && versionMatches(t.version());
        }

        private boolean vendorMatches(String tokenVendor) {
            String v = vendor.toLowerCase(Locale.ROOT);
            String implementor =
                    switch (v) {
                        case "temurin" -> "eclipse";
                        case "corretto" -> "amazon";
                        case "zulu", "zulu-prime" -> "azul";
                        case "liberica" -> "bellsoft";
                        case "sapmachine" -> "sap";
                        case "semeru" -> "ibm";
                        case "microsoft" -> "microsoft";
                        case "graalvm", "graalvm-ce", "openjdk", "oracle" -> "oracle";
                        default -> v;
                    };
            return tokenVendor.startsWith(implementor) || tokenVendor.contains(v);
        }

        private boolean versionMatches(String tokenVersion) {
            String plain =
                    tokenVersion.contains("+") ? tokenVersion.substring(0, tokenVersion.indexOf('+')) : tokenVersion;
            return plain.equals(version) || plain.startsWith(version + ".");
        }
    }

    /**
     * What a step reading a key accepts: the JDK it runs on ({@code toolchain}), the module's Java
     * level, the match level (already raised to {@code major} when class files are tied to one major,
     * {@code --enable-preview}) and the project's requirement when it binds the cache.
     */
    public record Expectation(
            String toolchain,
            int level,
            Match match,
            @Nullable Requirement requirement) {

        /** Whether a record made by {@code recordToolchain} may serve this step. */
        public boolean accepts(@Nullable String recordToolchain) {
            if (recordToolchain == null) return true; // a record from before TOOLCHAIN lines: as it was keyed
            if (requirement != null && requirement.bindsCache() && !requirement.admits(recordToolchain)) return false;
            if (recordToolchain.equals(toolchain)) return true;
            Toolchain mine = Toolchain.parse(toolchain);
            Toolchain theirs = Toolchain.parse(recordToolchain);
            if (mine == null || theirs == null) return false; // a JDK named only by its content: exact only
            if (theirs.major() < level) return false; // never a JDK below the module's level
            return switch (match) {
                case LEVEL -> true;
                case MAJOR -> theirs.major() == mine.major();
                case BUILD -> false;
            };
        }

        /** Lower is closer: same build, same vendor and major, same major, then by major distance. */
        public int closeness(@Nullable String recordToolchain) {
            if (recordToolchain == null) return 1000;
            if (recordToolchain.equals(toolchain)) return 0;
            Toolchain mine = Toolchain.parse(toolchain);
            Toolchain theirs = Toolchain.parse(recordToolchain);
            if (mine == null || theirs == null) return 999;
            if (theirs.major() == mine.major()) return theirs.vendor().equals(mine.vendor()) ? 1 : 2;
            return 3 + Math.abs(theirs.major() - mine.major());
        }
    }

    /** A {@link JdkToolchain} token's parts: vendor slug, runtime version, major. */
    record Toolchain(String vendor, String version, int major) {
        static @Nullable Toolchain parse(@Nullable String token) {
            if (token == null || !token.startsWith(JdkToolchain.PREFIX)) return null;
            String[] parts = token.substring(JdkToolchain.PREFIX.length()).split("/", -1);
            if (parts.length < 2) return null;
            int major = leadingInt(parts[1]);
            return major <= 0 ? null : new Toolchain(parts[0], parts[1], major);
        }

        private static int leadingInt(String s) {
            int i = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            try {
                return i == 0 ? -1 : Integer.parseInt(s.substring(0, i));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /** The major of a {@link JdkToolchain} token, or -1 when it names no version. */
    public static int major(@Nullable String token) {
        Toolchain t = Toolchain.parse(token);
        return t == null ? -1 : t.major();
    }

    /** {@code eclipse-adoptium 25.0.5+11-LTS} of a token, for messages. */
    public static String describe(@Nullable String token) {
        return JdkToolchain.describe(token);
    }

    /** The request-scope key of the expectations the steps of this request stated. */
    private record Expected() {}

    private static final Expected EXPECTED = new Expected();

    /** This request's expectations, key to expectation; a fresh map off a request. */
    private static Map<String, Expectation> expected() {
        return RequestScope.current().get(EXPECTED, k -> new ConcurrentHashMap<>());
    }

    /**
     * Note what a step that computed {@code key} on {@code jdkHome} for a module at {@code level}
     * accepts when it reads it. {@code moduleHint} is any path inside the module (its output
     * directory will do); its {@code jk.toml}, and its workspace root's, give the match level and the
     * requirement. {@code tiedToMajor}: class files only one major reads ({@code --enable-preview}).
     */
    public static void expect(
            String key, @Nullable Path jdkHome, int level, boolean tiedToMajor, @Nullable Path moduleHint) {
        try {
            String toolchain = JdkToolchain.token(jdkHome);
            Path module = moduleHint == null ? null : moduleDir(moduleHint);
            Match match = match(module);
            if (tiedToMajor && match == Match.LEVEL) match = Match.MAJOR;
            expected().put(key, new Expectation(toolchain, level, match, requirement(module)));
        } catch (IOException | RuntimeException unread) {
            // No expectation: the key reads as it always did, any record taken. The key itself
            // still names the level, so nothing below it can match.
        }
    }

    /** Test seam: state {@code expectation} for {@code key} as a step would. */
    static void expectForTests(String key, Expectation expectation) {
        expected().put(key, expectation);
    }

    /** What the step that computed {@code key} accepts, or null when no step said. */
    public static @Nullable Expectation of(String key) {
        return expected().get(key);
    }

    /** The effective level of a compile or a suite: {@code release} when set, else {@code jdkHome}'s major. */
    public static int level(int release, @Nullable Path jdkHome) {
        if (release > 0) return release;
        if (jdkHome == null) return Runtime.version().feature();
        try {
            return JavaHomes.featureVersion(jdkHome);
        } catch (RuntimeException unreadable) {
            return Runtime.version().feature();
        }
    }

    /** Whether {@code options} tie class files to one major. */
    public static boolean tiedToMajor(List<String> options) {
        return options.contains("--enable-preview");
    }

    /** The environment variable that sets the match level for one shell. */
    public static final String ENV = "JK_CACHE_JDK_MATCH";

    /**
     * The match level for {@code moduleDir}: {@link #ENV}, else {@code [cache] jdk-match} in the
     * module's {@code jk.toml}, else in its workspace root's, else in {@code ~/.jk/config.toml}, else
     * {@link Match#MAJOR}.
     */
    public static Match match(@Nullable Path moduleDir) {
        Optional<Match> env = Match.parse(ProcessEnv.get(ENV));
        if (env.isPresent()) return env.get();
        for (Path manifest : manifests(moduleDir)) {
            Optional<Match> m =
                    Match.parse(TomlScan.scan(manifest, "cache.jdk-match").get("cache.jdk-match"));
            if (m.isPresent()) return m.get();
        }
        Path user = JkDirs.userConfigFile();
        if (Files.isRegularFile(user)) {
            Optional<Match> m =
                    Match.parse(TomlScan.scan(user, "cache.jdk-match").get("cache.jdk-match"));
            if (m.isPresent()) return m.get();
        }
        return Match.MAJOR;
    }

    /**
     * The requirement the module's {@code jk.toml}, else its workspace root's, states: a {@code jdk}
     * pin with {@code =} naming a vendor ({@code =corretto-21}, {@code =temurin-25.0.4}), with {@code
     * jdk-cache = "required"} making it binding for the cache. Null without one.
     */
    public static @Nullable Requirement requirement(@Nullable Path moduleDir) {
        for (Path manifest : manifests(moduleDir)) {
            TomlScan scan = TomlScan.scan(manifest, "jdk", "jdk-cache");
            String jdk = scan.get("jdk");
            if (jdk == null || jdk.isBlank()) continue;
            if (!jdk.startsWith("=")) return null;
            String spec = jdk.substring(1).strip();
            int dash = spec.lastIndexOf('-');
            if (dash <= 0 || dash == spec.length() - 1) return null;
            boolean binds = "required".equals(scan.get("jdk-cache"));
            return new Requirement(spec.substring(0, dash), spec.substring(dash + 1), binds);
        }
        return null;
    }

    /** The module's {@code jk.toml}, then its workspace root's when that is another. */
    private static List<Path> manifests(@Nullable Path moduleDir) {
        if (moduleDir == null) return List.of();
        Path own = ManifestPaths.manifestIn(moduleDir);
        Path root =
                WorkspaceScan.findRoot(moduleDir).map(ManifestPaths::manifestIn).orElse(null);
        if (root == null || root.equals(own)) return Files.isRegularFile(own) ? List.of(own) : List.of();
        return Files.isRegularFile(own) ? List.of(own, root) : List.of(root);
    }

    /** The nearest directory at or above {@code hint} holding a {@code jk.toml}, else {@code hint}. */
    static Path moduleDir(Path hint) {
        Path dir = hint.toAbsolutePath().normalize();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isRegularFile(ManifestPaths.manifestIn(d))) return d;
        }
        return dir;
    }
}
