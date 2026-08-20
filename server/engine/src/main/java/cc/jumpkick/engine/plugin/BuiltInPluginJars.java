// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.runtime.PluginDescriptorOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /** Located first-party jars that carry a root {@code jk-plugin.toml}. */
    public static List<Path> tablePluginJars() {
        List<Path> out = new ArrayList<>();
        for (PluginJar jar : PluginJar.values()) {
            Path path = jar.locateOrNull(JkStores.storeCas());
            if (path == null) continue;
            try {
                if (zipText(path, PluginDescriptorOps.MANIFEST_ENTRY) != null) out.add(path);
            } catch (IOException ignored) {
                // skip unreadable jars
            }
        }
        return out;
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
