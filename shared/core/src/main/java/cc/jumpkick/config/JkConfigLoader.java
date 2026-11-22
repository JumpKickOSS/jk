// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Merges {@link ConfigSources} file layers and {@code JK_*} env into a {@link JkConfig}. CLI flags
 * are applied by the caller after {@link #load}. Reads only the {@code [config]} table.
 */
public final class JkConfigLoader {

    /** Env var → JkConfig setter. */
    private static final String ENV_COLOR = "JK_COLOR";

    private static final String ENV_OFFLINE = "JK_OFFLINE";
    private static final String ENV_FORCE = "JK_FORCE";
    private static final String ENV_NO_PROGRESS = "JK_NO_PROGRESS";
    private static final String ENV_QUIET = "JK_QUIET";
    private static final String ENV_VERBOSE = "JK_VERBOSE";
    private static final String ENV_NO_COLOR = "NO_COLOR";
    private static final String ENV_NO_ANSI = "JK_NO_ANSI";
    private static final String ENV_FORCE_ANSI = "JK_FORCE_ANSI";
    private static final String ENV_NO_OSC = "JK_NO_OSC";
    private static final String ENV_NOTIFY = "JK_NOTIFY";
    private static final String ENV_BUILD_OUTPUT = "JK_BUILD_OUTPUT";

    private JkConfigLoader() {}

    /** The {@code [config]} table, in {@code ~/.jk/config.toml} and a project's {@code jk.toml} alike. */
    public static final UserConfigTable CONFIG = UserConfigTable.of("config");

    private static final String COLOR = CONFIG.key("color").dotted();
    private static final String OFFLINE = CONFIG.key("offline").dotted();
    private static final String NO_PROGRESS = CONFIG.key("no-progress").dotted();
    private static final String QUIET = CONFIG.key("quiet").dotted();
    private static final String VERBOSE = CONFIG.key("verbose").dotted();
    private static final String DIR = CONFIG.key("dir").dotted();
    private static final String FORCE = CONFIG.key("force").dotted();
    private static final String NO_ANSI = CONFIG.key("no-ansi").dotted();
    private static final String FORCE_ANSI = CONFIG.key("force-ansi").dotted();
    private static final String NO_OSC = CONFIG.key("no-osc").dotted();
    private static final String NOTIFY = CONFIG.key("notify").dotted();
    private static final String BUILD_OUTPUT = CONFIG.key("build-output").dotted();

    /** The keys of a {@code [config]} table; a project's {@code jk.toml} refuses any other. */
    public static final List<String> KEYS =
            CONFIG.keys().stream().map(UserConfigTable.Key::name).toList();

    /**
     * File layers (from {@code startDir}) then env. Caller overlays CLI flags via
     * {@link JkConfig#mergedWith}.
     */
    public static JkConfig load(Path startDir, boolean noConfig, Optional<Path> explicitConfigFile) throws IOException {
        // File layers, lowest precedence first, then the env layer on top.
        JkConfig out = JkConfig.empty();
        for (Path layer : ConfigSources.discover(startDir, noConfig, explicitConfigFile.orElse(null))
                .layers()) {
            out = out.mergedWith(loadTomlOrEmpty(layer));
        }
        // Env vars override files but are overridden by CLI flags (caller's job).
        out = out.mergedWith(loadFromEnv(BuildEnv::get));
        return out;
    }

    /**
     * Whether this host is offline for a fetch no command asked for (the engine's background
     * refreshes): {@code JK_OFFLINE}, else {@code [config] offline} in {@code ~/.jk/config.toml}.
     */
    public static boolean hostOffline() {
        return hostOffline(JkDirs.userConfigFile(), BuildEnv::get);
    }

    /** As {@link #hostOffline()} against an explicit user config file and environment. */
    static boolean hostOffline(Path userConfig, Function<String, @Nullable String> env) {
        try {
            return loadTomlOrEmpty(userConfig).mergedWith(loadFromEnv(env)).offlineOr(false);
        } catch (IOException e) {
            return false;
        }
    }

    /** Parse a TOML file's {@code [config]} table into a config layer; missing/malformed → empty. */
    static JkConfig loadTomlOrEmpty(Path path) throws IOException {
        // Missing/malformed → empty layer. TomlScan (flat scalars; no full TOML parser).
        TomlScan scan = TomlScan.scan(
                path, CONFIG.keys().stream().map(UserConfigTable.Key::dotted).toArray(String[]::new));
        return new JkConfig(
                JkConfig.ColorChoice.parse(scan.get(COLOR)).orElse(null),
                scanBool(scan, OFFLINE),
                null, // rebuild is a per-invocation CLI flag, not a config-file key
                scanBool(scan, NO_PROGRESS),
                scanBool(scan, QUIET),
                scanBool(scan, VERBOSE),
                Optional.ofNullable(scan.get(DIR)).map(Paths::get).orElse(null),
                scanBool(scan, FORCE),
                scanBool(scan, NO_ANSI),
                scanBool(scan, FORCE_ANSI),
                scanBool(scan, NO_OSC),
                JkConfig.NotifyChoice.parse(scan.get(NOTIFY)).orElse(null),
                scanBool(scan, BUILD_OUTPUT));
    }

    /** A scanned {@code jk.toml} boolean, per the jk-wide truth set; anything else = unset. */
    private static @Nullable Boolean scanBool(TomlScan scan, String key) {
        return EnvValues.parseBool(scan.get(key)).orElse(null);
    }

    /** Build a config layer from environment variables. */
    static JkConfig loadFromEnv(Function<String, @Nullable String> env) {
        // NO_COLOR (any non-empty value) → never; defers to JK_COLOR if also set.
        JkConfig.ColorChoice color = EnvValues.string(env, ENV_COLOR)
                .flatMap(JkConfig.ColorChoice::parse)
                .or(() -> {
                    String noColor = env.apply(ENV_NO_COLOR);
                    return (noColor != null && !noColor.isEmpty())
                            ? Optional.of(JkConfig.ColorChoice.NEVER)
                            : Optional.empty();
                })
                .orElse(null);
        return new JkConfig(
                color,
                envBool(env, ENV_OFFLINE),
                null, // rebuild is a per-invocation CLI flag, not env-driven
                envBool(env, ENV_NO_PROGRESS),
                envBool(env, ENV_QUIET),
                envBool(env, ENV_VERBOSE),
                null, // dir isn't env-var-driven
                envBool(env, ENV_FORCE),
                envBool(env, ENV_NO_ANSI),
                envBool(env, ENV_FORCE_ANSI),
                envBool(env, ENV_NO_OSC),
                EnvValues.string(env, ENV_NOTIFY)
                        .flatMap(JkConfig.NotifyChoice::parse)
                        .orElse(null),
                envBool(env, ENV_BUILD_OUTPUT));
    }

    /** A {@code JK_*} boolean, per the jk-wide truth set; anything else = unset. */
    private static @Nullable Boolean envBool(Function<String, @Nullable String> env, String name) {
        return EnvValues.bool(env, name).orElse(null);
    }
}
