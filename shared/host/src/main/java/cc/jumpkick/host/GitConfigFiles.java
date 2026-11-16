// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Where git's own configuration files are, as the {@code git} command finds them: {@code
 * GIT_CONFIG_GLOBAL} names the user's file in place of {@code ~/.gitconfig}, and {@code
 * GIT_CONFIG_NOSYSTEM} drops the system file. Everything in jk that reads git configuration without
 * running git (JGit, the {@code jk new} group guess) reads it from here, so a process whose git is
 * pointed elsewhere is pointed elsewhere all through.
 *
 * <p>The {@code jk.git.config.global} and {@code jk.git.config.nosystem} system properties stand in
 * for the two variables in a JVM that cannot change its own environment: a test JVM of jk's own
 * suites sets them to its sandbox.
 */
public final class GitConfigFiles {

    /** Stands in for {@code GIT_CONFIG_GLOBAL}. */
    public static final String GLOBAL_PROPERTY = "jk.git.config.global";

    /** Stands in for {@code GIT_CONFIG_NOSYSTEM}. */
    public static final String NOSYSTEM_PROPERTY = "jk.git.config.nosystem";

    private GitConfigFiles() {}

    /** {@code GIT_CONFIG_GLOBAL}, or its property, when either is set: the file git reads instead of the home's. */
    public static @Nullable Path named() {
        String named = nonBlank(System.getProperty(GLOBAL_PROPERTY));
        if (named == null) named = nonBlank(ProcessEnv.get("GIT_CONFIG_GLOBAL"));
        return named == null ? null : Path.of(named);
    }

    private static @Nullable String nonBlank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
