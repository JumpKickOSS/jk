// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * JDK homes named by {@value #PATHS_ENV} (comma-separated homes) and {@value #FROM_ENV_ENV}
 * (comma-separated names of variables holding homes): Gradle's installation lists in jk's names.
 * They are pointers, so {@code jk jdk uninstall} refuses them.
 */
public final class JdkPathsProbe extends HomeListProbe {

    public static final String PATHS_ENV = "JK_JDK_PATHS";
    public static final String FROM_ENV_ENV = "JK_JDK_FROM_ENV";

    private final Function<String, @Nullable String> env;

    public JdkPathsProbe() {
        this(JkDirs::env);
    }

    JdkPathsProbe(Function<String, @Nullable String> env) {
        this.env = env;
    }

    @Override
    public String name() {
        return "jdk-paths";
    }

    @Override
    List<Path> candidateHomes() {
        return InstallationLists.homes(env.apply(PATHS_ENV), env.apply(FROM_ENV_ENV), env);
    }
}
