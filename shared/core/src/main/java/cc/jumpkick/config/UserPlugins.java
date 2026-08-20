// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Machine-scoped {@code [plugins]} from {@code ~/.config/jk/config.toml}. Later alias wins when
 * merged with project {@code [plugins]}. The installer never writes this table — implied
 * first-party defaults live in the host.
 */
public final class UserPlugins {

    private UserPlugins() {}

    public static List<PluginDeclaration> fromConfig() {
        return fromConfig(JkDirs.userConfigFile());
    }

    /** Parse {@code [plugins]} from {@code file}; missing file or missing table → empty. */
    public static List<PluginDeclaration> fromConfig(Path file) {
        if (file == null || !Files.isRegularFile(file)) return List.of();
        TomlParseResult parsed;
        try {
            parsed = Toml.parse(file);
        } catch (Exception e) {
            return List.of();
        }
        if (parsed.hasErrors()) {
            if (parsed.getTable("plugins") != null) {
                throw new JkBuildParseException(
                        file + " [plugins]: " + parsed.errors().getFirst().getMessage());
            }
            return List.of();
        }
        if (parsed.getTable("plugins") == null) return List.of();
        return ManifestBuild.parsePlugins(parsed);
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
