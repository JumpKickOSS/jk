// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects a plugin-worker module from on-disk authoring files — not from {@code [application]}.
 * Presence of {@code jk-plugin.toml} (or the {@link Plugin} ServiceLoader registration) means the
 * process entry is {@link #WORKER_MAIN}; authors do not declare it.
 */
public final class PluginModule {

    /** Fixed worker host every plugin jar runs under. */
    public static final String WORKER_MAIN = "cc.jumpkick.plugin.process.PluginMain";

    private static final String SERVICE = "META-INF/services/cc.jumpkick.plugin.Plugin";

    private PluginModule() {}

    /**
     * True when {@code moduleDir} is a plugin worker: a {@code jk-plugin.toml} at the module root
     * or on the resource path, or a {@code Plugin} service registration (compiler / tool workers
     * that have no consumer table).
     */
    public static boolean isWorker(Path moduleDir) {
        if (moduleDir == null) return false;
        return Files.isRegularFile(moduleDir.resolve("jk-plugin.toml"))
                || Files.isRegularFile(resource(moduleDir, "jk-plugin.toml"))
                || Files.isRegularFile(resource(moduleDir, SERVICE));
    }

    /**
     * {@link #WORKER_MAIN} for a plugin worker; otherwise {@link JkBuild#mainClass()} (the
     * {@code [application]} main, or {@code null}).
     */
    public static String mainClass(Path moduleDir, JkBuild build) {
        if (isWorker(moduleDir)) return WORKER_MAIN;
        return build == null ? null : build.mainClass();
    }

    /** Traditional ({@code src/main/resources}) then simple ({@code resources}) layout. */
    private static Path resource(Path moduleDir, String relative) {
        Path traditional =
                moduleDir.resolve("src").resolve("main").resolve("resources").resolve(relative);
        if (Files.isRegularFile(traditional)) return traditional;
        return moduleDir.resolve("resources").resolve(relative);
    }
}
