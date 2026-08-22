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
 * <p>{@code integration} is the global kill switch for Maven local-repository lookup /
 * write-through of third-party jars. A project can still set {@code [m2] integration = false} to
 * host those jars only under {@code JK_STORE_DIR}.
 *
 * <p>{@code install} controls whether {@code jk install} writes first-party jars into the Maven
 * local repository. Independent of {@code integration}: {@code [m2] integration = true} with
 * {@code [m2] install = false} still reads third-party jars from {@code ~/.m2} but keeps {@code
 * jk install} under {@code repos/local}.
 *
 * <p>Precedence: JVM system property &gt; {@code JK_*} env &gt; user file &gt; defaults. Malformed
 * values fall back to defaults.
 */
public record JkM2Config(boolean integration, boolean install) {

    public static final JkM2Config DEFAULTS = new JkM2Config(true, true);

    public static JkM2Config resolve() {
        JkM2Config base = resolve(JkDirs.userConfigFile(), System::getenv);
        return new JkM2Config(
                property("jk.m2.integration").or(() -> property("jk.m2.lookup")).orElse(base.integration()),
                property("jk.m2.install").orElse(base.install()));
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
                EnvValues.bool(env, "JK_M2_INTEGRATION")
                        .or(() -> EnvValues.bool(env, "JK_M2_LOOKUP"))
                        .orElse(base.integration()),
                EnvValues.bool(env, "JK_M2_INSTALL").orElse(base.install()));
    }

    /** {@code [m2]} table; missing/malformed → {@link #DEFAULTS}. */
    public static JkM2Config fromToml(Path file) {
        TomlScan scan = TomlScan.scan(file, "m2.integration", "m2.install");
        return new JkM2Config(
                bool(scan.get("m2.integration"), DEFAULTS.integration()),
                bool(scan.get("m2.install"), DEFAULTS.install()));
    }

    private static boolean bool(Object raw, boolean fallback) {
        return switch (String.valueOf(raw)) {
            case "true" -> true;
            case "false" -> false;
            default -> fallback;
        };
    }
}
