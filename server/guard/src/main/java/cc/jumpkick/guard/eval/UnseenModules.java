// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one answer every guard lane gives to "which modules has this run not seen": a module is unseen
 * while a main source file of it has no class in its own index, or while it has packaged nothing
 * (no jar, POM or native binary). A full build compiles and packages every module, so it sees them
 * all; a build scoped with {@code -m}, or one not run yet, leaves the rest unseen. Each lane decides
 * from its own view whether to leave the baseline alone; how many modules it was missing is counted
 * here, so the number a run reports means the same whichever lane withheld.
 */
public final class UnseenModules {

    private UnseenModules() {}

    /**
     * The modules of {@code modules} (the root alone when it lists none) this run has not seen, by
     * label ({@code root} for the workspace root), in the order given. A directory with no manifest
     * is judged by its sources alone; one whose artefacts cannot be listed is unseen.
     */
    public static List<String> of(Path root, List<Path> modules) {
        List<Path> dirs = modules.isEmpty() ? List.of(root) : modules;
        Map<Path, Boolean> packaged = new HashMap<>();
        try {
            for (OutputArtifacts.Module m : OutputArtifacts.of(root, dirs, null)) {
                packaged.put(m.dir().toAbsolutePath().normalize(), m.any());
            }
        } catch (IOException unreadable) {
            // Nothing listed: every module with a manifest reads as packaging nothing below.
        }
        List<String> out = new ArrayList<>();
        for (Path dir : dirs) {
            Path key = dir.toAbsolutePath().normalize();
            boolean hasManifest = Files.isRegularFile(ManifestPaths.manifestIn(dir));
            boolean unpackaged = hasManifest && !packaged.getOrDefault(key, false);
            if (unpackaged || !uncompiled(root, dir).isEmpty()) {
                String rel = WorkspaceModel.rel(root, dir);
                out.add(rel.isEmpty() ? "root" : rel);
            }
        }
        return out;
    }

    private static List<String> uncompiled(Path root, Path module) {
        return ClassesView.uncompiled(root, module, ClassesView.compiledTails(WorkspaceFacts.module(root, module)));
    }
}
