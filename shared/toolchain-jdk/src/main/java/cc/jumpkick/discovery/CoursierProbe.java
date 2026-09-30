// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.Os;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * JDKs in Coursier's JVM cache, where Mill and {@code cs java} install them. {@code
 * COURSIER_JVM_CACHE} replaces the platform directory. Coursier has no uninstall command and the
 * cache is not jk's, so {@code jk jdk uninstall} refuses these.
 */
public final class CoursierProbe extends HomeListProbe {

    private final Path cacheDir;

    public CoursierProbe() {
        this(JkDirs::env, System.getProperty("user.home"), Os.name());
    }

    CoursierProbe(Function<String, @Nullable String> env, String userHome, String osName) {
        this.cacheDir = jvmCacheDir(env, userHome, osName);
    }

    @Override
    public String name() {
        return "coursier";
    }

    @Override
    List<Path> candidateHomes() throws IOException {
        return childHomes(cacheDir);
    }

    /**
     * {@code COURSIER_JVM_CACHE}, else {@code ~/Library/Caches/Coursier/jvm} on macOS, {@code
     * ~\AppData\Local\Coursier\Cache\jvm} on Windows, and {@code ~/.cache/coursier/jvm}
     * elsewhere.
     */
    static Path jvmCacheDir(Function<String, @Nullable String> env, String userHome, String osName) {
        String override = env.apply("COURSIER_JVM_CACHE");
        if (override != null && !override.isBlank()) return Path.of(override);
        if (Os.isDarwin(osName)) return Path.of(userHome, "Library", "Caches", "Coursier", "jvm");
        if (Os.isWindows(osName)) return Path.of(userHome, "AppData", "Local", "Coursier", "Cache", "jvm");
        return Path.of(userHome, ".cache", "coursier", "jvm");
    }
}
