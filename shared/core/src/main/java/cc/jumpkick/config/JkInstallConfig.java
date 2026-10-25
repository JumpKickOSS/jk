// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [install]} policy from {@code ~/.jk/config.toml}: where {@code jk install}
 * puts a project's jar and POM when neither the command line nor the project's {@code jk.toml}
 * says. Precedence: JVM system property &gt; {@code JK_INSTALL_DESTINATION} env &gt; user file &gt;
 * {@code m2}, laid out in rank order by {@link MachineConfig}. An unknown word falls through.
 */
public record JkInstallConfig(InstallDestination destination) {

    public static final JkInstallConfig DEFAULTS = new JkInstallConfig(InstallDestination.M2);

    private static final MachineConfig<InstallDestination> DESTINATION = MachineConfig.of(DEFAULTS.destination());

    public static JkInstallConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv, System::getProperty);
    }

    /** As {@link #resolve()} but against an explicit config file + env, with no JVM properties. */
    static JkInstallConfig resolve(Path userConfig, Function<String, @Nullable String> env) {
        return resolve(userConfig, env, name -> null);
    }

    static JkInstallConfig resolve(
            Path userConfig, Function<String, @Nullable String> env, Function<String, @Nullable String> property) {
        TomlScan scan = TomlScan.scan(userConfig, "install.destination");
        return new JkInstallConfig(DESTINATION.layer(
                word(property.apply("jk.install.destination")),
                word(EnvValues.string(env, "JK_INSTALL_DESTINATION").orElse(null)),
                word(scan.get("install.destination"))));
    }

    /** The {@code [install]} table of {@code file}; missing or malformed gives {@link #DEFAULTS}. */
    public static JkInstallConfig fromToml(Path file) {
        return new JkInstallConfig(DESTINATION.layer(
                word(TomlScan.scan(file, "install.destination").get("install.destination"))));
    }

    private static @Nullable InstallDestination word(@Nullable String raw) {
        return raw == null ? null : InstallDestination.parse(raw).orElse(null);
    }
}
