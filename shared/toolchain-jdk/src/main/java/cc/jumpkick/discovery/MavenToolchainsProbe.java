// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.Log;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * JDK homes listed in Maven's {@code ~/.m2/toolchains.xml}. They point at JDKs something else
 * installed, so {@code jk jdk uninstall} refuses them.
 */
public final class MavenToolchainsProbe extends HomeListProbe {

    private final Path file;
    private final Function<String, @Nullable String> env;

    public MavenToolchainsProbe() {
        this(Path.of(System.getProperty("user.home"), ".m2", "toolchains.xml"), JkDirs::env);
    }

    MavenToolchainsProbe(Path file, Function<String, @Nullable String> env) {
        this.file = file;
        this.env = env;
    }

    @Override
    public String name() {
        return "maven-toolchains";
    }

    @Override
    List<Path> candidateHomes() {
        return MavenToolchains.jdkHomes(file, env, message -> Log.warn(message));
    }
}
