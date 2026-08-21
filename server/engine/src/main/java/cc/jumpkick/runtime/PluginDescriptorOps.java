// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    /** The manifest entry name at the root of a plugin jar. */
    public static final String MANIFEST_ENTRY = "jk-plugin.toml";

    /**
     * Materialize every locked declaration's manifest that is missing from {@code moduleDir}'s
     * store. CAS-only — never touches the network (sync/lock own fetching). Returns true when
     * anything new was written, so callers can re-parse.
     */
    public static boolean ensureMaterialized(Path moduleDir, Path cache) {
        Path lock = cc.jumpkick.lock.LockPaths.lockFile(moduleDir);
        if (!Files.isRegularFile(lock)) return false;
        Lockfile lockfile;
        try {
            lockfile = LockfileReader.read(lock);
        } catch (Exception e) {
            return false;
        }
        boolean wrote = false;
        Cas cas = JkStores.cas(cache);
        for (Lockfile.PluginEntry entry : lockfile.plugins()) {
            String sha = entry.sha256Hex();
            Path target = PluginDescriptorStore.fileFor(moduleDir, sha);
            if (Files.isRegularFile(target)) continue;
            Path jar = cas.pathFor(sha);
            if (!Files.isRegularFile(jar)) continue; // unsynced — jk sync fetches, then we extract
            try {
                materialize(moduleDir, sha, jar);
                wrote = true;
            } catch (IOException e) {
                // A jar without a root jk-plugin.toml is not a build plugin — leave it
                // unresolved; the unowned-table gate stays suppressed and the coordinate
                // is still usable as a plain locked artifact.
            }
        }
        return wrote;
    }

    /** Extract {@code jar}'s root manifest into the store (atomic move over a temp file). */
    public static void materialize(Path moduleDir, String sha256Hex, Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
            if (entry == null) {
                throw new IOException(jar + " has no root " + MANIFEST_ENTRY + " — not a build plugin");
            }
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Path target = PluginDescriptorStore.fileFor(moduleDir, sha256Hex);
            AtomicWrites.replace(target, text);
        }
    }

    /** The locked + synced jar for {@code decl}, or empty (remediation: {@code jk sync}). */
    public static Optional<Path> jarFor(Path moduleDir, PluginDeclaration decl, Path cache) {
        Optional<Lockfile.PluginEntry> entry = PluginDescriptorStore.lockEntry(moduleDir, decl);
        if (decl.isPathPin()) {
            // Path pins have no Maven coordinate or POM; the sha-verified blob is the whole
            // classpath (WorkerLaunchClasspath recognizes blob paths as self-contained).
            return entry.map(e -> JkStores.cas(cache).pathFor(e.sha256Hex())).filter(Files::isRegularFile);
        }
        return entry.flatMap(e -> pinnedLayoutJar(JkStores.cas(cache), e.coordinate(), e.version(), e.sha256Hex()));
    }

    /**
     * The Maven-layout path for a lock-pinned worker jar: the first repo store whose sidecar hash
     * matches the pin, else materialized into {@code repos/local} from the CAS blob. Forks must get
     * layout paths, never bare CAS blobs — a blob has no {@code .jar} name and no coordinate, so
     * {@link cc.jumpkick.repo.PomRuntimeClasspath} cannot reach its POM. Repos live beside the CAS
     * under {@code cas.root()}.
     */
    static Optional<Path> pinnedLayoutJar(Cas cas, String module, String version, String sha256Hex) {
        String rel = MavenLayout.artifactPath(Coordinate.ofModule(module, version));
        Path storeRoot = cas.root();
        for (String repoName : List.of("local", PluginJar.OFFICIAL_REPO, "central")) {
            Optional<Path> stored =
                    RepoArtifactStore.forRepoName(storeRoot, repoName).locate(rel, sha256Hex);
            if (stored.isPresent()) return stored;
        }
        Path blob = cas.pathFor(sha256Hex);
        if (!Files.isRegularFile(blob)) return Optional.empty();
        RepoArtifactStore local = RepoArtifactStore.forRepoName(storeRoot, "local");
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
