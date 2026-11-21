// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.InstallDestination;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [install]} policy from {@code ~/.jk/config.toml}: where {@code jk install}
 * puts a project's jar and POM when neither the command line nor the project's {@code jk.toml}
 * says. Precedence: {@code JK_INSTALL_DESTINATION} env &gt; user file &gt; what {@code [m2]
 * integration} implies ({@link JkM2Config}), laid out in rank order by {@link MachineConfig}: the Maven
 * local repository on a machine that shares its downloads with Maven ({@code "share"}), else jk's
 * own {@code repos/jk-local}, so that nothing is written into {@code ~/.m2} unasked. An unknown word
 * falls through.
 */
public record JkInstallConfig(InstallDestination destination) {

    public static final JkInstallConfig DEFAULTS = new JkInstallConfig(InstallDestination.JK);

    private static final MachineConfig<InstallDestination> DESTINATION = MachineConfig.of(DEFAULTS.destination());

    public static JkInstallConfig resolve() {
        return resolve(JkDirs.userConfigFile(), JkDirs::env);
    }

    /** As {@link #resolve()} but against an explicit config file and environment. */
    static JkInstallConfig resolve(Path userConfig, Function<String, @Nullable String> env) {
        TomlScan scan = TomlScan.scan(userConfig, "install.destination");
        InstallDestination implied =
                JkM2Config.resolve(userConfig, env).share() ? InstallDestination.M2 : InstallDestination.JK;
        return new JkInstallConfig(DESTINATION.layerOver(
                implied,
                word(EnvValues.string(env, "JK_INSTALL_DESTINATION").orElse(null)),
                word(scan.get("install.destination"))));
    }

    /** The {@code [install]} table of {@code file}; missing or malformed gives {@link #DEFAULTS}. */
    public static JkInstallConfig fromToml(Path file) {
        InstallDestination implied = JkM2Config.fromToml(file).share() ? InstallDestination.M2 : InstallDestination.JK;
        return new JkInstallConfig(DESTINATION.layerOver(
                implied, word(TomlScan.scan(file, "install.destination").get("install.destination"))));
    }

    private static @Nullable InstallDestination word(@Nullable String raw) {
        return raw == null ? null : InstallDestination.parse(raw).orElse(null);
    }
}
