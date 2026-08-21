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

    /**
     * Install every store-resident plugin manifest into the registry. Store-only by design:
     * engine startup must not fetch — a plugin absent from the store installs lazily on first
     * use via {@link #registerMissingBuiltInFetcher()}.
     */
    public static void install() {
        for (Located located : locatedTablePlugins()) {
            PluginTableRegistry.putBuiltIn(
                    cc.jumpkick.plugin.manifest.PluginDescriptors.parse(
                            located.manifestToml(), located.path() + "!jk-plugin.toml"),
                    located.path());
        }
    }

    /**
     * Register the parse-time hook that fetches exactly the built-in plugin owning an unowned
     * table (first cold-store use), instead of startup mass-downloading all of them.
     */
    public static void registerMissingBuiltInFetcher() {
        PluginTableRegistry.missingBuiltInFetcher(BuiltInPluginJars::fetchAndInstall);
    }

    /** Fetch + install the built-in whose worker is {@code jk-<table>}; failure detail or null. */
    private static String fetchAndInstall(String table) {
        var plugin = PluginJar.byArtifactId("jk-" + table);
        if (plugin.isEmpty()) return null; // not a first-party table — the plain error stands
        try {
            Path jar = plugin.get().locate(JkStores.storeCas());
            PluginTableRegistry.installFromJar(jar);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
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

    /** Store-resident first-party jars that carry a root {@code jk-plugin.toml}. */
    public static List<Path> tablePluginJars() {
        return locatedTablePlugins().stream().map(Located::path).toList();
    }

    public record Located(PluginJar plugin, Path path, String manifestToml) {}

    /** Store-only enumeration ({@link PluginJar#locateStored}): never fetches, one zip open per jar. */
    public static List<Located> locatedTablePlugins() {
        List<Located> out = new ArrayList<>();
        for (PluginJar jar : PluginJar.values()) {
            Path path = jar.locateStored(JkStores.storeCas());
            if (path == null) continue;
            try {
                String toml = zipText(path, PluginDescriptorOps.MANIFEST_ENTRY);
                if (toml != null) {
                    out.add(new Located(jar, path, toml));
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
