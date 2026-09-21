// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a workspace install pins in {@link ShelfManifest} after its pass: the thin jar and the
 * published POM of every module the pass shelved, by plain {@code group:artifact:version} and
 * sha256, under the engine the home names. The shas are the ones the engine reported on each
 * module's outcome — what its own {@code cache-install} published — so a concurrent install from
 * another checkout landing after the pass cannot lend this manifest its bytes.
 */
final class ShelfPinning {

    private ShelfPinning() {}

    /** What an install says instead of pinning when the home names no engine jar by sha256. */
    static final String SHELF_NOT_PINNED =
            "Shelf not pinned: the home names no engine jar by sha256, so its workers launch as the shelf holds them";

    /** The pins one pass contributes: coordinate to sha256 of the jar, and of the POM beside it. */
    record Pins(Map<String, String> jars, Map<String, String> poms) {}

    /**
     * Record {@code pins} in the manifest at {@code shelfFile} as engine {@code engine}'s shelf,
     * installed from {@code source}; the line the install prints for it. A home whose pointer
     * names no engine jar by sha256 has nothing to pin the shelf to, and the line says so.
     */
    static String record(Optional<String> engine, Path shelfFile, Path source, Pins pins) throws IOException {
        if (engine.isEmpty()) return SHELF_NOT_PINNED;
        ShelfManifest.record(shelfFile, engine.get(), source, pins.jars(), pins.poms(), Clock.SYSTEM);
        String sha = engine.get();
        int jars = pins.jars().size();
        String engineLabel = (sha.length() > 12 ? sha.substring(0, 12) : sha) + " from " + PathDisplay.of(source);
        if (jars == 0) return "Shelf pins unchanged for engine " + engineLabel;
        return "Pinned " + jars + " shelf jar" + (jars == 1 ? "" : "s") + " to engine " + engineLabel;
    }

    /**
     * The pins of every module the pass shelved: each successful outcome that carries what its
     * {@code cache-install} step published (or found on the shelf at the tree's bytes). The engine
     * runs the selected cone only, so the pins the pass does not touch are the ones the manifest
     * merge keeps; a failed module, or one with no install step (a coordinator root), contributes
     * nothing.
     */
    static Pins shelved(WorkspaceResult result) {
        Map<String, String> jars = new LinkedHashMap<>();
        Map<String, String> poms = new LinkedHashMap<>();
        for (ModuleOutcome m : result.modules()) {
            ModuleOutcome.Shelved shelf = m.shelved();
            if (!m.success() || shelf == null) continue;
            jars.put(shelf.coordinate(), shelf.jarSha256());
            poms.put(shelf.coordinate(), shelf.pomSha256());
        }
        return new Pins(jars, poms);
    }
}
