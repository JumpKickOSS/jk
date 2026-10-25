// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.InstallTarget;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped {@code [install]} policy from {@code ~/.jk/config.toml}: where {@code jk install}
 * puts a project's jar and POM when neither the command line nor the project's {@code jk.toml}
 * says. Precedence: JVM system property &gt; {@code JK_INSTALL_TARGET} env &gt; user file &gt;
 * {@code m2}, laid out in rank order by {@link MachineConfig}. An unknown word falls through.
 */
public record JkInstallConfig(InstallTarget target) {

    public static final JkInstallConfig DEFAULTS = new JkInstallConfig(InstallTarget.M2);

    private static final MachineConfig<InstallTarget> TARGET = MachineConfig.of(DEFAULTS.target());

    public static JkInstallConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv, System::getProperty);
    }

    /** As {@link #resolve()} but against an explicit config file + env, with no JVM properties. */
    static JkInstallConfig resolve(Path userConfig, Function<String, @Nullable String> env) {
        return resolve(userConfig, env, name -> null);
    }

    static JkInstallConfig resolve(
            Path userConfig, Function<String, @Nullable String> env, Function<String, @Nullable String> property) {
        TomlScan scan = TomlScan.scan(userConfig, "install.target");
        return new JkInstallConfig(TARGET.layer(
                word(property.apply("jk.install.target")),
                word(EnvValues.string(env, "JK_INSTALL_TARGET").orElse(null)),
                word(scan.get("install.target"))));
    }

    /** The {@code [install]} table of {@code file}; missing or malformed gives {@link #DEFAULTS}. */
    public static JkInstallConfig fromToml(Path file) {
        return new JkInstallConfig(
                TARGET.layer(word(TomlScan.scan(file, "install.target").get("install.target"))));
    }

    private static @Nullable InstallTarget word(@Nullable String raw) {
        return raw == null ? null : InstallTarget.parse(raw).orElse(null);
    }
}
