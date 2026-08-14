// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Machine-scoped {@code [m2]} policy from {@code ~/.config/jk/config.toml}.
 *
 * <p>Before spending bandwidth on an artifact, jk can {@code stat} the Maven local repository for the
 * same coordinate. A hit still has to be confirmed against a checksum fetched from the repository the
 * artifact would have come from — {@code ~/.m2} is writable by anything on the machine and Maven
 * enforces no integrity, so a hand-built jar can sit at a coordinate that looks legitimate. Confirmed
 * bytes are the right bytes whatever their provenance, which is what makes an untrusted directory usable.
 *
 * <p>{@code link = false} by default: the artifact is copied into the store. A hard link would save the
 * disk but leaves the blob sharing an inode with a file jk does not own, so any tool rewriting it in
 * place would mutate content the CAS believes it has hashed. Opt in with {@code link = true} when the
 * saving is worth more than that.
 *
 * <p>Precedence: JVM system property &gt; {@code JK_*} env &gt; user file &gt; defaults. Malformed values fall
 * back to defaults.
 */
public record JkM2Config(boolean enabled, boolean link) {

    public static final JkM2Config DEFAULTS = new JkM2Config(true, false);

    /**
     * Effective machine config: user-global file, then env, then JVM system properties.
     *
     * <p>The property layer exists for the same reason {@code jk.m2.local} does in {@link
     * cc.jumpkick.repo.M2Dirs}: environment variables cannot be set from inside a running JVM, so
     * without it no in-process test could exercise the linking path — and a "link" mode that silently
     * kept copying would look identical from the outside.
     */
    public static JkM2Config resolve() {
        JkM2Config base = resolve(JkDirs.userConfigFile(), System::getenv);
        return new JkM2Config(
                property("jk.m2.lookup").orElse(base.enabled()),
                property("jk.m2.link").orElse(base.link()));
    }

    private static Optional<Boolean> property(String name) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) return Optional.empty();
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on", "1", "yes" -> Optional.of(true);
            case "false", "off", "0", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkM2Config resolve(Path userConfig, Function<String, String> env) {
        JkM2Config base = fromToml(userConfig);
        return new JkM2Config(
                EnvValues.bool(env, "JK_M2_LOOKUP").orElse(base.enabled()),
                EnvValues.bool(env, "JK_M2_LINK").orElse(base.link()));
    }

    /** {@code [m2]} table; missing/malformed → {@link #DEFAULTS}. */
    public static JkM2Config fromToml(Path file) {
        TomlScan scan = TomlScan.scan(file, "m2.enabled", "m2.link");
        return new JkM2Config(
                bool(scan.get("m2.enabled"), DEFAULTS.enabled()), bool(scan.get("m2.link"), DEFAULTS.link()));
    }

    private static boolean bool(Object raw, boolean fallback) {
        return switch (String.valueOf(raw)) {
            case "true" -> true;
            case "false" -> false;
            default -> fallback;
        };
    }
}
