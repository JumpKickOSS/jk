// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [m2]} policy from {@code ~/.jk/config.toml}.
 *
 * <p>{@code integration} is the global kill switch for Maven local-repository lookup /
 * write-through of third-party jars. A project can still set {@code [m2] integration = false} to
 * host those jars only under {@code JK_STORE_DIR}.
 *
 * <p>{@code auto-clean} lets the engine's maintenance remove files from the Maven local repository
 * on its own — a build server where the admin wants {@code ~/.m2} bounded. It is off by default and
 * reaches only files jk's own memo proves jk downloaded and that nothing has changed since;
 * {@code clean-after-days} is how long after the download one becomes eligible. The same removal is
 * always available on request ({@code jk cache clean --m2}), without this setting.
 *
 * <p>Precedence: JVM system property &gt; {@code JK_*} env &gt; user file &gt; defaults, laid out
 * in rank order by {@link MachineConfig}. Malformed values fall back to defaults.
 */
public record JkM2Config(boolean integration, boolean autoClean, int cleanAfterDays) {

    public static final JkM2Config DEFAULTS = new JkM2Config(true, false, 60);

    /** {@code [m2] integration}: a boolean's decode is its validation, so no range rule. */
    private static final MachineConfig<Boolean> INTEGRATION = MachineConfig.of(DEFAULTS.integration());

    /** {@code [m2] auto-clean}. */
    private static final MachineConfig<Boolean> AUTO_CLEAN = MachineConfig.of(DEFAULTS.autoClean());

    /** {@code [m2] clean-after-days}: a window of zero or less is no window, so only a positive value is a value. */
    private static final MachineConfig<Integer> CLEAN_AFTER_DAYS =
            MachineConfig.of(DEFAULTS.cleanAfterDays(), days -> days > 0);

    public static JkM2Config resolve() {
        return resolve(JkDirs.userConfigFile(), ProcessEnv::get, System::getProperty);
    }

    /** As {@link #resolve()} but against an explicit config file + env, with no JVM properties. */
    static JkM2Config resolve(Path userConfig, Function<String, @Nullable String> env) {
        return resolve(userConfig, env, name -> null);
    }

    /**
     * The whole ladder in one place, highest layer first. {@code lookup} is the pre-rename spelling
     * of {@code integration} and ranks directly under it on each substrate, so a machine that still
     * sets the old name is not overridden by the file.
     */
    static JkM2Config resolve(
            Path userConfig, Function<String, @Nullable String> env, Function<String, @Nullable String> property) {
        TomlScan scan = scan(userConfig);
        return new JkM2Config(
                INTEGRATION.layer(
                        EnvValues.parseBool(property.apply("jk.m2.integration")).orElse(null),
                        EnvValues.parseBool(property.apply("jk.m2.lookup")).orElse(null),
                        EnvValues.bool(env, "JK_M2_INTEGRATION").orElse(null),
                        EnvValues.bool(env, "JK_M2_LOOKUP").orElse(null),
                        tomlBool(scan, "m2.integration")),
                AUTO_CLEAN.layer(
                        EnvValues.parseBool(property.apply("jk.m2.auto-clean")).orElse(null),
                        EnvValues.bool(env, "JK_M2_AUTO_CLEAN").orElse(null),
                        tomlBool(scan, "m2.auto-clean")),
                CLEAN_AFTER_DAYS.layer(
                        EnvValues.intValue(env, "JK_M2_CLEAN_AFTER_DAYS").orElse(null),
                        scanInt(scan, "m2.clean-after-days")));
    }

    /** {@code [m2]} table; missing/malformed → {@link #DEFAULTS}. */
    public static JkM2Config fromToml(Path file) {
        TomlScan scan = scan(file);
        return new JkM2Config(
                INTEGRATION.layer(tomlBool(scan, "m2.integration")),
                AUTO_CLEAN.layer(tomlBool(scan, "m2.auto-clean")),
                CLEAN_AFTER_DAYS.layer(scanInt(scan, "m2.clean-after-days")));
    }

    private static TomlScan scan(Path file) {
        return TomlScan.scan(file, "m2.integration", "m2.auto-clean", "m2.clean-after-days");
    }

    /**
     * TOML's booleans, not jk's env truth set: a config file is parsed by the spec it is written to,
     * so {@code yes} / {@code on} are not booleans here and read as absent.
     */
    private static @Nullable Integer scanInt(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Boolean tomlBool(TomlScan scan, String key) {
        return switch (String.valueOf(scan.get(key))) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
