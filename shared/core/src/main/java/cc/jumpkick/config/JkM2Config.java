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
 * <p>{@code enabled} is the global kill switch for Maven local-repository integration. A project
 * can still set {@code m2integration = false} to host third-party artifacts only under
 * {@code JK_STORE_DIR}.
 *
 * <p>Precedence: JVM system property &gt; {@code JK_*} env &gt; user file &gt; defaults. Malformed
 * values fall back to defaults.
 */
public record JkM2Config(boolean enabled) {

    public static final JkM2Config DEFAULTS = new JkM2Config(true);

    public static JkM2Config resolve() {
        JkM2Config base = resolve(JkDirs.userConfigFile(), System::getenv);
        return new JkM2Config(property("jk.m2.lookup").orElse(base.enabled()));
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
        return new JkM2Config(EnvValues.bool(env, "JK_M2_LOOKUP").orElse(base.enabled()));
    }

    /** {@code [m2]} table; missing/malformed → {@link #DEFAULTS}. */
    public static JkM2Config fromToml(Path file) {
        TomlScan scan = TomlScan.scan(file, "m2.enabled");
        return new JkM2Config(bool(scan.get("m2.enabled"), DEFAULTS.enabled()));
    }

    private static boolean bool(Object raw, boolean fallback) {
        return switch (String.valueOf(raw)) {
            case "true" -> true;
            case "false" -> false;
            default -> fallback;
        };
    }
}
