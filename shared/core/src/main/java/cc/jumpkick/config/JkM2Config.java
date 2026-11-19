// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [m2]} policy from {@code ~/.jk/config.toml}.
 *
 * <p>{@code integration} says how far jk goes into the Maven local repository for third-party
 * jars ({@link Mode}): {@code "read"} (the default) uses a verified file already there and keeps
 * its own downloads under {@code JK_STORE_DIR}; {@code "share"} also downloads into it; {@code
 * "off"} leaves it alone. A project can still set {@code [m2] integration = false} to keep its
 * jars only under {@code JK_STORE_DIR}.
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
public record JkM2Config(Mode mode, boolean autoClean, int cleanAfterDays) {

    /** What {@code [m2] integration} allows in the Maven local repository. */
    public enum Mode {
        /** The local repository is neither read nor written for third-party jars. */
        OFF("off"),
        /** A verified file already there is used where it is; what jk downloads goes to its own store. */
        READ("read"),
        /** As {@link #READ}, and what jk downloads is written there, with Maven's sidecars, for Maven to use. */
        SHARE("share");

        private final String spelling;

        Mode(String spelling) {
            this.spelling = spelling;
        }

        /** The word {@code config.toml} and {@code JK_M2_INTEGRATION} spell this mode with. */
        public String spelling() {
            return spelling;
        }

        /**
         * The mode {@code raw} names, or null. A boolean keeps what it meant before the modes had
         * names: {@code false} is {@link #OFF} and {@code true}, which then wrote downloads through,
         * is {@link #SHARE}.
         */
        static @Nullable Mode parse(@Nullable String raw) {
            if (raw == null) return null;
            String word = raw.strip().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.spelling.equals(word)) return mode;
            }
            return EnvValues.parseBool(raw).map(on -> on ? SHARE : OFF).orElse(null);
        }
    }

    public static final JkM2Config DEFAULTS = new JkM2Config(Mode.READ, false, 60);

    /** {@code [m2] integration}: a mode's decode is its validation, so no range rule. */
    private static final MachineConfig<Mode> INTEGRATION = MachineConfig.of(DEFAULTS.mode());

    /** True unless the mode is {@link Mode#OFF}: the local repository may be read. */
    public boolean integration() {
        return mode != Mode.OFF;
    }

    /** True when what jk downloads is written into the local repository ({@link Mode#SHARE}). */
    public boolean share() {
        return mode == Mode.SHARE;
    }

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
                        Mode.parse(property.apply("jk.m2.integration")),
                        Mode.parse(property.apply("jk.m2.lookup")),
                        Mode.parse(EnvValues.string(env, "JK_M2_INTEGRATION").orElse(null)),
                        Mode.parse(EnvValues.string(env, "JK_M2_LOOKUP").orElse(null)),
                        tomlMode(scan)),
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
                INTEGRATION.layer(tomlMode(scan)),
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

    /** {@code [m2] integration} as the file spells it: a mode's word, or TOML's {@code true} / {@code false}. */
    private static @Nullable Mode tomlMode(TomlScan scan) {
        String raw = scan.get("m2.integration");
        Boolean bool = tomlBool(scan, "m2.integration");
        if (bool != null) return bool ? Mode.SHARE : Mode.OFF;
        if (raw == null) return null;
        for (Mode mode : Mode.values()) {
            if (mode.spelling.equals(raw.strip())) return mode;
        }
        return null;
    }

    private static @Nullable Boolean tomlBool(TomlScan scan, String key) {
        return switch (String.valueOf(scan.get(key))) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
