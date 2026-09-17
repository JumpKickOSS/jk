// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.UserPlugins;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
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
import org.jspecify.annotations.Nullable;

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
            try {
                PluginTableRegistry.putBuiltIn(describe(located, true), located.path());
            } catch (RuntimeException e) {
                // Store jars are managed artifacts: one garbled, version-incompatible or
                // mis-described manifest must not kill the engine machine-wide. Skip it loudly —
                // a build referencing its table retries through the lazy fetcher and surfaces
                // this cause there.
                Log.warn("jk engine: skipping plugin jar " + located.path() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Register the parse-time hook that fetches exactly the built-in plugin owning an unowned
     * table (first cold-store use), instead of startup mass-downloading all of them.
     */
    public static void registerMissingBuiltInFetcher() {
        PluginTableRegistry.missingBuiltInFetcher(BuiltInPluginJars::fetchAndInstall);
    }

    /**
     * Fetch + install the built-in whose worker is {@code jk-<table>}; failure detail or null. A
     * worker jar this machine holds that does not own the table is the detail of {@link
     * #doesNotOwn}: the project was written for a newer plugin than the one installed here.
     */
    private static @Nullable String fetchAndInstall(String table) {
        var plugin = PluginJar.byArtifactId("jk-" + table);
        if (plugin.isEmpty()) return null; // not a first-party table — the plain error stands
        try {
            Path jar = plugin.get().locate(JkStores.storeCas());
            String toml = manifestToml(jar);
            if (toml == null || toml.isBlank()) return doesNotOwn(plugin.get(), jar, table);
            PluginTableRegistry.putBuiltIn(describe(new Located(plugin.get(), jar, toml), true), jar);
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getMessage();
        }
    }

    /**
     * Why {@code [table]} stays unowned when the installed {@code plugin} jar carries no root
     * descriptor for it: the shelf is behind the plugin the project was written for. Names the
     * jar, the checkout install that refreshes the shelf and the {@code -D} override that points
     * one build at another jar.
     */
    static String doesNotOwn(PluginJar plugin, Path jar, String table) {
        return "the installed " + plugin.artifactId() + " (" + jar + ") does not own [" + table
                + "] — the project was written for a newer " + plugin.artifactId()
                + " than this shelf holds. Run `jk install` from the jk checkout that has it, or set -D"
                + plugin.jarProperty() + "=<path to its jar> to use that jar";
    }

    /**
     * The descriptor {@code located} carries, checked to be the jar's own: the worker it names
     * ({@code [code] worker}, or {@code jk-<id>} when the code table names none) must be the
     * artifact the jar is shelved as. A worker vendors sibling plugins' class trees, and a merge
     * that lets a sibling's root descriptor through would otherwise register this jar as the
     * owner of the sibling's table and pin it in every project configuring that table — so a
     * jar whose descriptor is another plugin's is refused, never registered.
     *
     * @param enforceJkCompat as {@link PluginDescriptors#parse(String, String, boolean)}
     * @throws IllegalStateException when the descriptor belongs to another plugin
     */
    public static PluginDescriptor describe(Located located, boolean enforceJkCompat) {
        PluginDescriptor descriptor = PluginDescriptors.parse(
                located.manifestToml(), located.path() + "!" + ManifestPaths.PLUGIN_MANIFEST, enforceJkCompat);
        String artifact = located.plugin().artifactId();
        String owner = describedWorker(descriptor);
        if (!owner.equals(artifact)) {
            throw new IllegalStateException(located.path() + " is the " + artifact + " worker but its root "
                    + ManifestPaths.PLUGIN_MANIFEST + " describes plugin `" + descriptor.id() + "` (table ["
                    + descriptor.table() + "], worker " + owner
                    + ") — a vendored sibling's descriptor took the jar root; the jar is not registered."
                    + " Reinstall it so its own descriptor sits at the root: `jk install` from the jk checkout,"
                    + " or `jk storage clean --workers` and let the next build fetch the published jar.");
        }
        return descriptor;
    }

    /** The worker artifact a descriptor says carries its code: {@code [code] worker}, else {@code jk-<id>}. */
    public static String describedWorker(PluginDescriptor descriptor) {
        PluginDescriptor.Code code = descriptor.code();
        String worker = code == null ? null : code.worker();
        return worker == null || worker.isBlank() ? "jk-" + descriptor.id() : worker;
    }

    /**
     * Overlay {@code ~/.jk/config.toml [plugins]} path pins onto the registry. Maven pins
     * wait for {@code jk lock} (project lock is law). A pin that cannot be honored — missing
     * file, unreadable jar, hash mismatch — throws: the pin is explicit user intent, and
     * silently running the shipped plugin instead is wrong code with no diagnostic. This matches
     * the project-pin posture ({@code jk lock} errors naming both digests).
     */
    public static void installUserConfig() {
        Path config = JkDirs.userConfigFile();
        Path base = config.getParent() != null ? config.getParent() : Path.of(".");
        for (PluginDeclaration decl : UserPlugins.fromConfig(config)) {
            if (!decl.isPathPin()) continue;
            Path jar = Path.of(decl.path());
            if (!jar.isAbsolute()) jar = base.resolve(jar).normalize();
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException(config + " [plugins] pins " + decl.path() + " but no file exists at "
                        + jar + " — fix or remove the pin");
            }
            String actual;
            try {
                actual = Hashing.sha256Hex(jar);
            } catch (IOException e) {
                throw new IllegalStateException(
                        config + " [plugins] pin " + jar + " is unreadable: " + e.getMessage(), e);
            }
            if (!actual.equals(decl.sha256())) {
                throw new IllegalStateException(config + " [plugins] pin " + jar
                        + " hash mismatch: declared sha256 " + decl.sha256() + ", file is " + actual
                        + " — update the pin after rebuilding the jar");
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
                String toml = zipText(path, ManifestPaths.PLUGIN_MANIFEST);
                if (toml != null) {
                    out.add(new Located(jar, path, toml));
                }
            } catch (IOException ignored) {
                // skip unreadable jars
            }
        }
        return out;
    }

    public static @Nullable String manifestToml(Path jar) throws IOException {
        return zipText(jar, ManifestPaths.PLUGIN_MANIFEST);
    }

    private static @Nullable String zipText(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
