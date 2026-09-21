// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a workspace install pins in {@link ShelfManifest} after its pass: the thin jar and the
 * published POM of every module the pass shelved, by plain {@code group:artifact:version} and
 * sha256, under the engine the home names.
 */
final class ShelfPinning {

    private ShelfPinning() {}

    /** What an install says instead of pinning when the home names no engine jar by sha256. */
    static final String SHELF_NOT_PINNED =
            "Shelf not pinned: the home names no engine jar by sha256, so its workers launch as the shelf holds them";

    /**
     * Record {@code jars} and their {@code poms} in the manifest at {@code shelfFile} as engine
     * {@code engine}'s shelf, installed from {@code source}; the line the install prints for it. A
     * home whose pointer names no engine jar by sha256 has nothing to pin the shelf to, and the
     * line says so.
     */
    static String record(
            Optional<String> engine, Path shelfFile, Path source, Map<String, String> jars, Map<String, String> poms)
            throws IOException {
        if (engine.isEmpty()) return SHELF_NOT_PINNED;
        ShelfManifest.record(shelfFile, engine.get(), source, jars, poms, Clock.SYSTEM);
        String sha = engine.get();
        String engineLabel = (sha.length() > 12 ? sha.substring(0, 12) : sha) + " from " + PathDisplay.of(source);
        if (jars.isEmpty()) return "Shelf pins unchanged for engine " + engineLabel;
        return "Pinned " + jars.size() + " shelf jar" + (jars.size() == 1 ? "" : "s") + " to engine " + engineLabel;
    }

    /**
     * The modules whose shelf slot this pass wrote: every one the engine ran to success. The
     * engine runs the selected cone only, and within it skips a module whose shelf jar already
     * holds the tree's bytes, so the pins the pass does not touch are the ones the manifest merge
     * keeps.
     */
    static List<Path> shelved(WorkspaceResult result) {
        List<Path> out = new ArrayList<>();
        for (var m : result.modules()) {
            if (m.success()) out.add(m.dir());
        }
        return out;
    }

    /**
     * {@code group:artifact:version} to sha256 of the thin jar {@code jk build} left for each
     * module — the bytes {@code cache-install} shelved. A module with no jar on disk contributes
     * nothing; a coordinator root publishes nothing.
     */
    static Map<String, String> shelfJars(List<Path> moduleDirs, Map<Path, ProjectInfo> infoByDir) throws IOException {
        Map<String, String> jars = new LinkedHashMap<>();
        for (Path mod : moduleDirs) {
            ProjectInfo info = infoByDir.get(mod);
            if (info == null || info.error() != null || info.coordinatorOnly()) continue;
            String jarPath = info.mainJarPath();
            if (jarPath == null || jarPath.isBlank()) continue;
            Path jar = Path.of(jarPath);
            if (!Files.isRegularFile(jar)) continue;
            jars.put(Coordinate.of(info.group(), info.name(), info.version()).toGav(), Hashing.sha256Hex(jar));
        }
        return jars;
    }

    /**
     * {@code group:artifact:version} to sha256 of the POM on {@code store}'s shelf for each of
     * {@code coordinates} — the POM the engine rendered and published beside the jar this pass,
     * which the worker launcher rebuilds the runtime closure from. A coordinate with no shelf POM
     * contributes nothing.
     */
    static Map<String, String> shelfPoms(Set<String> coordinates, Path store) throws IOException {
        Path shelf = store.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
        Map<String, String> poms = new LinkedHashMap<>();
        for (String gav : coordinates) {
            Path pom = shelf.resolve(MavenLayout.pomPath(Coordinate.parse(gav)));
            if (Files.isRegularFile(pom)) poms.put(gav, Hashing.sha256Hex(pom));
        }
        return poms;
    }
}
