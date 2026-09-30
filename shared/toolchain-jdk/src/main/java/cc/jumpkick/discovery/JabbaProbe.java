// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Jabba's installs: {@code <JABBA_HOME>/jdk/<name>/}, with {@code JABBA_HOME} defaulting to {@code
 * ~/.jabba}. {@code jk jdk uninstall} delegates to {@code jabba uninstall} and never deletes one
 * itself.
 */
public final class JabbaProbe extends HomeListProbe {

    private final Path jdkRoot;

    public JabbaProbe() {
        this(JkDirs::env, System.getProperty("user.home"));
    }

    JabbaProbe(Function<String, @Nullable String> env, String userHome) {
        this.jdkRoot = jabbaHome(env, userHome).resolve("jdk");
    }

    @Override
    public String name() {
        return "jabba";
    }

    @Override
    List<Path> candidateHomes() throws IOException {
        return childHomes(jdkRoot);
    }

    /** {@code JABBA_HOME} when set, else {@code ~/.jabba}. */
    static Path jabbaHome(Function<String, @Nullable String> env, String userHome) {
        String configured = env.apply("JABBA_HOME");
        return configured != null && !configured.isBlank() ? Path.of(configured) : Path.of(userHome, ".jabba");
    }
}
