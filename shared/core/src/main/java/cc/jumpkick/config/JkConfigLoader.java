// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;

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
    private static final String ENV_NO_OSC = "JK_NO_OSC";
    private static final String ENV_NOTIFY = "JK_NOTIFY";

    private JkConfigLoader() {}

    /**
     * File layers (from {@code startDir}) then env. Caller overlays CLI flags via
     * {@link JkConfig#mergedWith}.
     */
    public static JkConfig load(Path startDir, boolean noConfig, Optional<Path> explicitConfigFile) throws IOException {
        // File layers, lowest precedence first, then the env layer on top.
        JkConfig out = JkConfig.empty();
        for (Path layer :
                ConfigSources.discover(startDir, noConfig, explicitConfigFile).layers()) {
            out = out.mergedWith(loadTomlOrEmpty(layer));
        }
        // Env vars override files but are overridden by CLI flags (caller's job).
        out = out.mergedWith(loadFromEnv(System::getenv));
        return out;
    }

    /** Parse a TOML file's {@code [config]} table into a config layer; missing/malformed → empty. */
    static JkConfig loadTomlOrEmpty(Path path) throws IOException {
        // Missing/malformed → empty layer. TomlScan (flat scalars; no full TOML parser).
        TomlScan scan = TomlScan.scan(
                path,
                "config.color",
                "config.offline",
                "config.rerun",
                "config.refresh",
                "config.no-progress",
                "config.quiet",
                "config.verbose",
                "config.directory",
                "config.force",
                "config.no-ansi",
                "config.no-osc",
                "config.notify");
        return new JkConfig(
                Optional.ofNullable(scan.get("config.color")).flatMap(JkConfig.ColorChoice::parse),
                scanBool(scan, "config.offline"),
                Optional.empty(), // rebuild is a per-invocation CLI flag, not a config-file key
                scanBool(scan, "config.no-progress"),
                scanBool(scan, "config.quiet"),
                scanBool(scan, "config.verbose"),
                Optional.ofNullable(scan.get("config.directory")).map(Paths::get),
                scanBool(scan, "config.force"),
                scanBool(scan, "config.no-ansi"),
                scanBool(scan, "config.no-osc"),
                Optional.ofNullable(scan.get("config.notify")).flatMap(JkConfig.NotifyChoice::parse));
    }

    /** A scanned TOML boolean: strictly {@code true}/{@code false}, anything else = absent. */
    private static Optional<Boolean> scanBool(TomlScan scan, String key) {
        String v = scan.get(key);
        if ("true".equalsIgnoreCase(v)) return Optional.of(true);
        if ("false".equalsIgnoreCase(v)) return Optional.of(false);
        return Optional.empty();
    }

    /** Build a config layer from environment variables. */
    static JkConfig loadFromEnv(Function<String, String> env) {
        // NO_COLOR (any non-empty value) → never; defers to JK_COLOR if also set.
        Optional<JkConfig.ColorChoice> color = EnvValues.string(env, ENV_COLOR)
                .flatMap(JkConfig.ColorChoice::parse)
                .or(() -> {
                    String noColor = env.apply(ENV_NO_COLOR);
                    return (noColor != null && !noColor.isEmpty())
                            ? Optional.of(JkConfig.ColorChoice.NEVER)
                            : Optional.empty();
                });
        Optional<Boolean> force = EnvValues.bool(env, ENV_FORCE);
        Optional<JkConfig.NotifyChoice> notify =
                EnvValues.string(env, ENV_NOTIFY).flatMap(JkConfig.NotifyChoice::parse);
        return new JkConfig(
                color,
                EnvValues.bool(env, ENV_OFFLINE),
                Optional.empty(), // rebuild is a per-invocation CLI flag, not env-driven
                EnvValues.bool(env, ENV_NO_PROGRESS),
                EnvValues.bool(env, ENV_QUIET),
                EnvValues.bool(env, ENV_VERBOSE),
                Optional.empty(), // directory isn't env-var-driven
                force,
                EnvValues.bool(env, ENV_NO_ANSI),
                EnvValues.bool(env, ENV_NO_OSC),
                notify);
    }
}
