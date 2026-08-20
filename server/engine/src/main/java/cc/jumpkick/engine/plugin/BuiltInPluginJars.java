// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.UserPlugins;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.runtime.PluginDescriptorOps;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads first-party table plugins from self-describing worker jars ({@code jk-plugin.toml} at
 * the zip root). Compiler/tool workers without that entry are skipped.
 */
public final class BuiltInPluginJars {

    private BuiltInPluginJars() {}

    /** Locate each {@link PluginJar} and install any root manifest into the registry. */
    public static void install() {
        for (Path path : tablePluginJars()) {
            PluginTableRegistry.installFromJar(path);
        }
    }

    /**
     * Overlay {@code ~/.config/jk/config.toml [plugins]} path pins onto the registry. Maven pins
     * wait for {@code jk lock} (project lock is law).
     */
    public static void installUserConfig() {
        Path config = JkDirs.userConfigFile();
        Path base = config.getParent() != null ? config.getParent() : Path.of(".");
        for (PluginDeclaration decl : UserPlugins.fromConfig(config)) {
            if (!decl.isPathPin()) continue;
            Path jar = Path.of(decl.path());
            if (!jar.isAbsolute()) jar = base.resolve(jar).normalize();
            if (!Files.isRegularFile(jar)) continue;
            try {
                if (!Hashing.sha256Hex(jar).equals(decl.sha256())) continue;
            } catch (IOException e) {
                continue;
            }
            PluginTableRegistry.installFromJar(jar);
        }
    }

    /** Located first-party jars that carry a root {@code jk-plugin.toml}. */
    public static List<Path> tablePluginJars() {
        return locatedTablePlugins().stream().map(Located::path).toList();
    }

    public record Located(PluginJar plugin, Path path) {}

    public static List<Located> locatedTablePlugins() {
        List<Located> out = new ArrayList<>();
        for (PluginJar jar : PluginJar.values()) {
            Path path = jar.locateOrNull(JkStores.storeCas());
            if (path == null) continue;
            try {
                if (zipText(path, PluginDescriptorOps.MANIFEST_ENTRY) != null) {
                    out.add(new Located(jar, path));
                }
            } catch (IOException ignored) {
                // skip unreadable jars
            }
        }
        return out;
    }

    public static String manifestToml(Path jar) throws IOException {
        return zipText(jar, PluginDescriptorOps.MANIFEST_ENTRY);
    }

    private static String zipText(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
