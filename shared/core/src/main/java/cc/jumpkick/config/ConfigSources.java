// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Ordered TOML config file layers, lowest precedence first: user-global
 * {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}), then project {@code jk.toml}
 * (nearest ancestor) or an explicit {@code --config-file}. No system/{@code ~/.config} layer.
 * Env and CLI flags sit above files and are applied by loaders, not modeled here.
 */
public final class ConfigSources {

    private final List<Path> layers;

    private ConfigSources(List<Path> layers) {
        this.layers = List.copyOf(layers);
    }

    /**
     * The file layers to merge, lowest precedence first. Paths are not guaranteed to exist; loaders
     * skip absent/unreadable files. Empty when {@code noConfig} short-circuits file discovery.
     */
    public List<Path> layers() {
        return layers;
    }

    /**
     * Discover the layers for a run. {@code startDir} is the search root for the project {@code
     * jk.toml} (normally the working directory). {@code noConfig} ({@code --no-config}) drops all
     * file layers — env vars and CLI flags still apply. {@code explicitConfigFile} ({@code
     * --config-file}) replaces the project layer; the user-global layer still merges underneath.
     */
    public static ConfigSources discover(Path startDir, boolean noConfig, @Nullable Path explicitConfigFile) {
        if (noConfig) return new ConfigSources(List.of());
        List<Path> out = new ArrayList<>(2);
        out.add(JkDirs.userConfigFile());
        if (explicitConfigFile != null) {
            out.add(explicitConfigFile);
        } else {
            Path project = findProjectConfig(startDir);
            if (project != null) out.add(project);
        }
        return new ConfigSources(out);
    }

    /** The user-global config file, {@code ~/.jk/config.toml}. */
    public static Path userConfig() {
        return JkDirs.userConfigFile();
    }

    /**
     * Search {@code startDir} and its ancestors for the nearest {@code jk.toml}; {@code null} when
     * none is found before the filesystem root.
     */
    public static @Nullable Path findProjectConfig(Path startDir) {
        Path here = startDir == null ? null : startDir.toAbsolutePath().normalize();
        while (here != null) {
            Path candidate = here.resolve(ManifestPaths.MANIFEST);
            if (Files.isRegularFile(candidate)) return candidate;
            here = here.getParent();
        }
        return null;
    }
}
