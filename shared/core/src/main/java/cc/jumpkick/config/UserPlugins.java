// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Machine-scoped {@code [plugins]} from {@code ~/.config/jk/config.toml}. Later alias wins when
 * merged with project {@code [plugins]}. The installer never writes this table — implied
 * first-party defaults live in the host.
 */
public final class UserPlugins {

    private UserPlugins() {}

    /**
     * Process-lifetime memo, keyed by path with the (size, mtime) stamp in the value — same shape
     * as {@code JkBuildParser.PARSE_CACHE}. {@code fromConfig} sits on every jk.toml parse (527
     * call sites, per-module hot paths); without this the resident engine re-ran a full tomlj
     * parse of the user config hundreds of times per build.
     */
    private static final ConcurrentHashMap<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long size, FileTime modified, List<PluginDeclaration> decls) {}

    public static List<PluginDeclaration> fromConfig() {
        return fromConfig(JkDirs.userConfigFile());
    }

    /** Parse {@code [plugins]} from {@code file}; missing file or missing table → empty. */
    public static List<PluginDeclaration> fromConfig(Path file) {
        if (file == null) return List.of();
        Path abs = file.toAbsolutePath().normalize();
        long size;
        FileTime modified;
        try {
            size = Files.size(abs);
            modified = Files.getLastModifiedTime(abs);
        } catch (IOException missing) {
            CACHE.remove(abs);
            return List.of();
        }
        Cached hit = CACHE.get(abs);
        if (hit != null && hit.size() == size && hit.modified().equals(modified)) {
            return hit.decls();
        }
        List<PluginDeclaration> parsed = parse(abs);
        CACHE.put(abs, new Cached(size, modified, parsed));
        return parsed;
    }

    private static List<PluginDeclaration> parse(Path file) {
        if (!Files.isRegularFile(file)) return List.of();
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
