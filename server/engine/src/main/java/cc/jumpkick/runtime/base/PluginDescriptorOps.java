// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The engine's write side of {@link PluginDescriptorStore}: extract each locked third-party plugin
 * jar's {@code jk-plugin.toml} out of the SHA-verified CAS into the module's manifest store, so
 * the parser (a plain-file reader) can validate the plugin's table on the next parse. Manifest
 * extraction is data, not code — it happens for untrusted plugins too; the trust gate sits in
 * front of plugin forks ({@link PluginBuild#runWorker}).
 */
public final class PluginDescriptorOps {

    private PluginDescriptorOps() {}

    /**
     * Materialize every locked declaration's manifest that is missing from {@code moduleDir}'s
     * store. CAS-only — never touches the network (sync/lock own fetching). Returns true when
     * anything new was written, so callers can re-parse.
     */
    public static boolean ensureMaterialized(Path moduleDir, Path cache) {
        Path lock = LockPaths.lockFile(moduleDir);
        if (!Files.isRegularFile(lock)) return false;
        Lockfile lockfile;
        try {
            lockfile = LockfileReader.read(lock);
        } catch (Exception e) {
            return false;
        }
        boolean wrote = false;
        Cas cas = JkStores.storeCas();
        for (Lockfile.PluginEntry entry : lockfile.plugins()) {
            String sha = entry.sha256Hex();
            if (sha == null) continue; // a workspace module: no jar to extract a manifest from
            Path target = PluginDescriptorStore.fileFor(moduleDir, sha);
            if (Files.isRegularFile(target)) continue;
            Path jar = cas.pathFor(sha);
            if (!Files.isRegularFile(jar)) continue; // unsynced — jk sync fetches, then we extract
            try {
                materialize(moduleDir, sha, jar, entry.coordinate());
                wrote = true;
            } catch (IOException e) {
                // A jar without a root jk-plugin.toml is not a build plugin — leave it
                // unresolved; the unowned-table gate stays suppressed and the coordinate
                // is still usable as a plain locked artifact.
            }
        }
        return wrote;
    }

    /**
     * Extract {@code jar}'s root manifest into the store (atomic move over a temp file) once the
     * descriptor is shown to be the pinned artifact's own ({@link #requireOwnDescriptor}).
     *
     * @param pinnedCoordinate the lock's {@code group:artifact} for the jar, or a {@code path:<alias>} pin
     * @throws IOException when the jar has no root descriptor: not a build plugin
     * @throws IllegalStateException when the descriptor belongs to another plugin; nothing is written
     */
    public static void materialize(Path moduleDir, String sha256Hex, Path jar, String pinnedCoordinate)
            throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(ManifestPaths.PLUGIN_MANIFEST);
            if (entry == null) {
                throw new IOException(jar + " has no root " + ManifestPaths.PLUGIN_MANIFEST + " — not a build plugin");
            }
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            PluginDescriptor descriptor =
                    PluginDescriptors.parse(text, jar + "!" + ManifestPaths.PLUGIN_MANIFEST, false);
            requireOwnDescriptor(descriptor, pinnedCoordinate, jar);
            Path target = PluginDescriptorStore.fileFor(moduleDir, sha256Hex);
            AtomicWrites.replace(target, text);
        }
    }

    /**
     * The descriptor a pinned jar carries must be that artifact's own. A plugin jar vendors its
     * siblings' class trees, and a merge that lets a sibling's root descriptor through would
     * register this pin as the owner of the sibling's table — silently, since the parser reads
     * whatever descriptor the jar carries. The claim checked is the worker the descriptor names:
     * {@code [code] worker} when set, and for a {@code cc.jumpkick} pin the first-party default
     * {@code jk-<id>}, must be the pinned artifact. A third-party descriptor without a worker key
     * is its own worker and makes no claim; a path pin has no artifact to compare against.
     *
     * @throws IllegalStateException naming the pin, the descriptor and the fix
     */
    static void requireOwnDescriptor(PluginDescriptor descriptor, String pinnedCoordinate, Path jar) {
        int colon = pinnedCoordinate.indexOf(':');
        if (colon <= 0) return;
        String group = pinnedCoordinate.substring(0, colon);
        if (group.equals("path")) return;
        String artifact = pinnedCoordinate.substring(colon + 1);
        String claimed;
        if (PluginJar.GROUP.equals(group)) {
            claimed = BuiltInPluginJars.describedWorker(descriptor);
        } else {
            PluginDescriptor.Code code = descriptor.code();
            claimed = code == null ? null : code.worker();
        }
        if (claimed == null || claimed.isBlank() || claimed.equals(artifact)) return;
        throw new IllegalStateException("plugin pin " + pinnedCoordinate + " (" + jar + ") is refused: its root "
                + ManifestPaths.PLUGIN_MANIFEST + " describes plugin `" + descriptor.id() + "` (table ["
                + descriptor.table() + "], worker " + claimed + "), not the pinned artifact " + artifact
                + " — the jar carries another plugin's descriptor. Publish " + artifact + " with its own "
                + ManifestPaths.PLUGIN_MANIFEST + " at the jar root, or pin the artifact the descriptor names.");
    }

    /** The locked + synced jar for {@code decl}, or empty (remediation: {@code jk sync}). */
    public static Optional<Path> jarFor(Path moduleDir, PluginDeclaration decl, Path cache) {
        Optional<Lockfile.PluginEntry> entry = PluginDescriptorStore.lockEntry(moduleDir, decl);
        if (entry.isEmpty()) return Optional.empty();
        Lockfile.PluginEntry e = entry.get();
        String hex = e.sha256Hex();
        if (hex == null) return Optional.empty(); // a workspace module is built, not synced
        if (decl.isPathPin()) {
            // Path pins have no Maven coordinate or POM; the sha-verified blob is the whole
            // classpath (WorkerLaunchClasspath recognizes blob paths as self-contained).
            return Optional.of(JkStores.storeCas().pathFor(hex)).filter(Files::isRegularFile);
        }
        return pinnedLayoutJar(JkStores.storeCas(), e.coordinate(), e.version(), hex);
    }

    /**
     * The Maven-layout path for a lock-pinned worker jar: the first repo store whose {@code .jk}
     * memo matches the pin, else a copy into {@code repos/jk-local} from a leftover store-CAS blob.
     * Forks must get layout paths (a {@code .jar} name and a sibling POM).
     */
    public static Optional<Path> pinnedLayoutJar(Cas cas, String module, String version, String sha256Hex) {
        String rel = MavenLayout.artifactPath(Coordinate.ofModule(module, version));
        Path storeRoot = cas.root();
        // First-party stores first; the repos/ directory listing is paid only on a miss — this
        // runs per plugin per parse, and the common case lands in the first probe.
        List<RepoArtifactStore> firstParty = RepoArtifactStore.firstParty(storeRoot);
        Set<Path> probed = new HashSet<>();
        for (RepoArtifactStore store : firstParty) {
            probed.add(Objects.requireNonNull(store.root(), "store root"));
            Optional<Path> stored = store.locate(rel, sha256Hex);
            if (stored.isPresent()) return stored;
        }
        for (String storeId : RepoArtifactStore.storeIds(storeRoot)) {
            RepoArtifactStore store = RepoArtifactStore.forStoreId(storeRoot, storeId);
            if (probed.contains(Objects.requireNonNull(store.root(), "store root"))) continue;
            Optional<Path> stored = store.locate(rel, sha256Hex);
            if (stored.isPresent()) return stored;
        }
        Path blob = cas.pathFor(sha256Hex);
        if (!Files.isRegularFile(blob)) return Optional.empty();
        RepoArtifactStore local = RepoArtifactStore.forStoreId(storeRoot, RepoArtifactResolver.JK_LOCAL);
        local.materialize(rel, blob, sha256Hex);
        return local.locate(rel, sha256Hex);
    }

    /** The declaration whose materialized manifest carries {@code pluginId}, or empty. */
    public static Optional<PluginDeclaration> declarationOf(Path moduleDir, JkBuild project, String pluginId) {
        for (PluginDeclaration decl : project.plugins()) {
            var manifest = PluginDescriptorStore.manifestFor(moduleDir, decl);
            if (manifest.isPresent() && manifest.get().id().equals(pluginId)) return Optional.of(decl);
        }
        return Optional.empty();
    }
}
