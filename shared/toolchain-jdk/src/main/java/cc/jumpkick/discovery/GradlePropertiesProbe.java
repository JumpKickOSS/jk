// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.Log;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * JDK homes named by {@code org.gradle.java.installations.paths}, {@code fromEnv}, and {@code
 * maven-toolchains-file} in the Gradle user home's {@code gradle.properties}, and in the build
 * root's when a build is running. They are pointers, so {@code jk jdk uninstall} refuses them.
 * Gradle's {@code auto-detect=false} does not turn any jk probe off.
 */
public final class GradlePropertiesProbe extends HomeListProbe {

    private final List<Path> files;
    private final Function<String, @Nullable String> env;

    /** The Gradle user home's file, plus {@code buildRoot}'s when not null. */
    public GradlePropertiesProbe(@Nullable Path buildRoot) {
        this(JkDirs::env, System.getProperty("user.home"), buildRoot);
    }

    GradlePropertiesProbe(Function<String, @Nullable String> env, String userHome, @Nullable Path buildRoot) {
        List<Path> files = new ArrayList<>();
        files.add(GradleProbe.gradleUserHome(env, userHome).resolve("gradle.properties"));
        if (buildRoot != null) files.add(buildRoot.resolve("gradle.properties"));
        this.files = List.copyOf(files);
        this.env = env;
    }

    @Override
    public String name() {
        return "gradle-properties";
    }

    @Override
    List<Path> candidateHomes() {
        List<Path> homes = new ArrayList<>();
        for (Path file : files) {
            homes.addAll(InstallationLists.fromGradleProperties(file, env, message -> Log.warn(message)));
        }
        return homes;
    }
}
