// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.PluginDeclaration;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlParseResult;

/**
 * Machine-scoped {@code [plugins]} from {@code ~/.jk/config.toml}. Later alias wins when
 * merged with project {@code [plugins]}. The installer never writes this table — implied
 * first-party defaults live in the host.
 */
public final class UserPlugins {

    private UserPlugins() {}

    /**
     * Process-lifetime memo, keyed by path, with the staleness rule {@link StampedMemo} owns — the
     * same one {@code GlobalConfig} applies to this file, at the same path resolution.
     * {@code fromConfig} sits on every jk.toml parse (per-module hot paths); without this the
     * resident engine would re-run a full tomlj parse of the user config hundreds of times per
     * build.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, List<PluginDeclaration>> CACHE = StampedMemo.create();

    public static List<PluginDeclaration> fromConfig() {
        return fromConfig(JkDirs.userConfigFile());
    }

    /**
     * Parse {@code [plugins]} from {@code file}; missing file or missing table → empty.
     *
     * <p>Strict, like every reader that decides what a request runs: a pin is code that runs, so a
     * file that does not parse is a {@link UserConfigParseException}, never "no pins". Read as
     * empty, a broken file would silently swap a pinned plugin for the shipped one.
     *
     * @throws UserConfigParseException when the file exists and is not valid TOML
     */
    public static List<PluginDeclaration> fromConfig(@Nullable Path file) {
        if (file == null) return List.of();
        Path abs = file.toAbsolutePath().normalize();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(abs);
        if (stamp == null) {
            CACHE.forget(abs); // absent or not stat-able: no memo, so a later recreate is not served stale
            return read(abs);
        }
        return CACHE.get(abs, stamp, () -> read(abs));
    }

    private static List<PluginDeclaration> read(Path file) {
        return GlobalConfig.parseConfig(file).map(UserPlugins::plugins).orElse(List.of());
    }

    private static List<PluginDeclaration> plugins(TomlParseResult parsed) {
        if (parsed.getTable("plugins") == null) return List.of();
        return List.copyOf(ManifestBuild.parsePlugins(parsed));
    }

    /** {@code higher} aliases replace {@code lower}. Order is lower-then-higher remaining. */
    public static List<PluginDeclaration> merge(List<PluginDeclaration> lower, List<PluginDeclaration> higher) {
        LinkedHashMap<String, PluginDeclaration> byAlias = new LinkedHashMap<>();
        if (lower != null) {
            for (PluginDeclaration d : lower) byAlias.put(d.alias(), d);
        }
        if (higher != null) {
            for (PluginDeclaration d : higher) byAlias.put(d.alias(), d);
        }
        return List.copyOf(byAlias.values());
    }
}
