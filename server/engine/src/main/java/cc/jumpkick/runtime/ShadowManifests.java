// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.mvn.PomShadow;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's {@link ManifestPaths.ShadowSource}: a module with a {@code pom.xml} and no {@code
 * jk.toml} gets its manifest rendered from the effective POM into {@link ManifestPaths#shadowDir},
 * and rendered again whenever the POM's bytes (or the running jk) change. Every reader of the
 * module's manifest goes through {@link ManifestPaths#manifestIn}, so the shadow is materialized
 * by the first reader and shared by the rest.
 *
 * <p>Rendering happens once per POM change; the rows of the import report that {@code jk import}
 * would grade Tier 3 are parked per module until the next build's parse step {@linkplain
 * #drainTier3 drains} them into its warnings, so a build says once what the shadow does not carry.
 */
public final class ShadowManifests {

    private ShadowManifests() {}

    private static final Map<Path, List<String>> PENDING_TIER3 = new ConcurrentHashMap<>();

    /** Per-module render gate: two readers racing on the same POM render it once. */
    private static final Map<Path, Object> GATES = new ConcurrentHashMap<>();

    /** Make this class the process's shadow source. Idempotent. */
    public static void install() {
        ManifestPaths.installShadowSource(ShadowManifests::materialize);
    }

    /**
     * The shadow manifest of {@code dir}, rendered now when absent or behind its POM. A reactor
     * root is refused ({@link PomShadow#reactorRefusal}); an unreadable POM is an {@link
     * UncheckedIOException}.
     */
    public static Path materialize(Path dir) {
        Path module = dir.toAbsolutePath().normalize();
        Path pom = module.resolve(ManifestPaths.POM);
        Path shadow = ManifestPaths.shadowManifestPath(module);
        synchronized (GATES.computeIfAbsent(module, k -> new Object())) {
            try {
                byte[] pomBytes = Files.readAllBytes(pom);
                if (PomShadow.isCurrent(shadow, pomBytes)) return shadow;
                Cas cas = JkStores.storeCas();
                PomImporter importer = new PomImporter(RepoGroupBuilder.buildDefault(cas), cas);
                PomShadow.Rendered rendered = PomShadow.render(importer, pom, pomBytes);
                Files.createDirectories(Objects.requireNonNull(shadow.getParent(), "shadow dir"));
                AtomicWrites.replace(shadow, rendered.toml());
                PENDING_TIER3.put(module, rendered.tier3());
                Log.debug("shadow manifest rendered", shadow);
                return shadow;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * The Tier-3 rows of the most recent rendering of {@code dir}'s shadow that no build has
     * reported yet, each with the {@code jk import} remedy; empty after the first call.
     */
    public static List<String> drainTier3(Path dir) {
        List<String> rows = PENDING_TIER3.remove(dir.toAbsolutePath().normalize());
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream()
                .map(row -> row + " — not carried by the in-place build of " + ManifestPaths.POM + "; `jk import "
                        + ManifestPaths.POM + "` writes a jk.toml to edit")
                .toList();
    }
}
