// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

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
 * jk install} under {@code repos/jk-local}.
 *
 * <p>Precedence: JVM system property &gt; {@code JK_*} env &gt; user file &gt; defaults, laid out
 * in rank order by {@link MachineConfig}. Malformed values fall back to defaults.
 */
public record JkM2Config(boolean integration, boolean install) {

    public static final JkM2Config DEFAULTS = new JkM2Config(true, true);

    /** {@code [m2] integration}: a boolean's decode is its validation, so no range rule. */
    private static final MachineConfig<Boolean> INTEGRATION = MachineConfig.of(DEFAULTS.integration());

    /** {@code [m2] install}. */
    private static final MachineConfig<Boolean> INSTALL = MachineConfig.of(DEFAULTS.install());

    public static JkM2Config resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv, System::getProperty);
    }

    /** As {@link #resolve()} but against an explicit config file + env, with no JVM properties. */
    static JkM2Config resolve(Path userConfig, Function<String, String> env) {
        return resolve(userConfig, env, name -> null);
    }

    /**
     * The whole ladder in one place, highest layer first. {@code lookup} is the pre-rename spelling
     * of {@code integration} and ranks directly under it on each substrate, so a machine that still
     * sets the old name is not overridden by the file.
     */
    static JkM2Config resolve(Path userConfig, Function<String, String> env, Function<String, String> property) {
        TomlScan scan = scan(userConfig);
        return new JkM2Config(
                INTEGRATION.layer(
                        EnvValues.parseBool(property.apply("jk.m2.integration")).orElse(null),
                        EnvValues.parseBool(property.apply("jk.m2.lookup")).orElse(null),
                        EnvValues.bool(env, "JK_M2_INTEGRATION").orElse(null),
                        EnvValues.bool(env, "JK_M2_LOOKUP").orElse(null),
                        tomlBool(scan, "m2.integration")),
                INSTALL.layer(
                        EnvValues.parseBool(property.apply("jk.m2.install")).orElse(null),
                        EnvValues.bool(env, "JK_M2_INSTALL").orElse(null),
                        tomlBool(scan, "m2.install")));
    }

    /** {@code [m2]} table; missing/malformed → {@link #DEFAULTS}. */
    public static JkM2Config fromToml(Path file) {
        TomlScan scan = scan(file);
        return new JkM2Config(
                INTEGRATION.layer(tomlBool(scan, "m2.integration")), INSTALL.layer(tomlBool(scan, "m2.install")));
    }

    private static TomlScan scan(Path file) {
        return TomlScan.scan(file, "m2.integration", "m2.install");
    }

    /**
     * TOML's booleans, not jk's env truth set: a config file is parsed by the spec it is written to,
     * so {@code yes} / {@code on} are not booleans here and read as absent.
     */
    private static @Nullable Boolean tomlBool(TomlScan scan, String key) {
        return switch (String.valueOf(scan.get(key))) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
