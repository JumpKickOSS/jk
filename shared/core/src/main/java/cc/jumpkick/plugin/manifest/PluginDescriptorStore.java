// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.PluginDeclaration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read side of materialized third-party manifests under
 * {@code <module>/target/plugin-manifests/<sha256>.jk-plugin.toml}. Missing → unresolved;
 * manifests are data from SHA-verified jars (no classload).
 */
public final class PluginDescriptorStore {

    private PluginDescriptorStore() {}

    /** Parsed manifests memoized by the jar SHA the store file is named for. */
    private static final Map<String, PluginDescriptor> BY_SHA = new ConcurrentHashMap<>();

    public static Path storeDir(Path moduleDir) {
        return moduleDir.resolve(BuildLayout.TARGET).resolve("plugin-manifests");
    }

    public static Path fileFor(Path moduleDir, String sha256Hex) {
        return storeDir(moduleDir).resolve(sha256Hex + ".jk-plugin.toml");
    }

    /** The lock's pinned entry for {@code decl}, or empty when unlocked/no lock. */
    public static Optional<Lockfile.PluginEntry> lockEntry(Path moduleDir, PluginDeclaration decl) {
        Path lock = LockPaths.lockFile(moduleDir);
        if (!Files.isRegularFile(lock)) return Optional.empty();
        try {
            for (Lockfile.PluginEntry e : LockfileReader.read(lock).plugins()) {
                if (e.coordinate().equals(decl.coordinate()) && e.version().equals(decl.version())) {
                    return Optional.of(e);
                }
            }
        } catch (Exception ignored) {
            // unreadable lock — reads as unlocked, the same soft behavior every reader has
        }
        return Optional.empty();
    }

    /** The materialized manifest for {@code decl}, or empty when not yet locked + extracted. */
    public static Optional<PluginDescriptor> manifestFor(Path moduleDir, PluginDeclaration decl) {
        if (moduleDir == null) return Optional.empty();
        Optional<Lockfile.PluginEntry> entry = lockEntry(moduleDir, decl);
        if (entry.isEmpty()) return Optional.empty();
        String sha = entry.get().sha256Hex();
        PluginDescriptor memo = BY_SHA.get(sha);
        if (memo != null) return Optional.of(memo);
        Path file = fileFor(moduleDir, sha);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            PluginDescriptor parsed = PluginDescriptors.parse(
                    Files.readString(file, StandardCharsets.UTF_8),
                    decl.coordinateWithVersion() + "!" + ManifestPaths.PLUGIN_MANIFEST);
            // Clear-on-overflow (ProjectIds idiom): a resident engine otherwise pins one
            // descriptor per plugin sha it ever met, across every checkout and upgrade.
            if (BY_SHA.size() >= 1_024) BY_SHA.clear();
            BY_SHA.put(sha, parsed);
            return Optional.of(parsed);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** True when any declaration lacks a materialized manifest (validation must stay soft). */
    public static boolean hasUnresolved(Path moduleDir, List<PluginDeclaration> decls) {
        for (PluginDeclaration decl : decls) {
            if (manifestFor(moduleDir, decl).isEmpty()) return true;
        }
        return false;
    }
}
