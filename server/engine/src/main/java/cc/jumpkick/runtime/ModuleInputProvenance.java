// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Which inputs the outputs sitting in a module's target dir were built from.
 *
 * <p>Preflight asks two different questions and used to answer both with one: "are this module's
 * inputs unchanged?" (the fingerprint memo) and "are its outputs current?" — the second answered by
 * {@link ModuleOutputRestore#packageOutputsMissing}, which tests only that the files <em>exist</em>.
 * A jar built from other sources exists, so a module could be skipped with a stale artifact on disk
 * and the build would report {@code all modules up to date}.
 *
 * <p>That is reachable whenever content returns to a state the action cache has already seen: the
 * forecast finds every step cached (the cache genuinely holds those outputs), nothing is missing so
 * nothing is restored, and the outputs actually on disk are the previous run's. Editing a file back
 * and forth — a revert, a rebase, a stash pop, a branch switch — is enough.
 *
 * <p>So the fact is recorded where the outputs are: one line under the module's target dir naming
 * the input fingerprint that produced them, written only after a build that succeeded. Preflight
 * then requires it to match before it may call a module clean.
 *
 * <p>A missing record reads as clean, not dirty. It means "built by a jk that did not write one" —
 * true of every existing target dir the first time this runs — and treating that as a rebuild would
 * make one upgrade recompile the world. The record appears after the next successful build, and the
 * mismatch case, which is the actual defect, is caught from then on.
 */
public final class ModuleInputProvenance {

    private ModuleInputProvenance() {}

    /** {@code target/<module>/.jk/inputs} — beside the preflight memo's own {@code .jk} dir. */
    private static Path recordFile(Path workspaceRoot, Path moduleDir, JkBuild build) {
        return BuildLayout.of(workspaceRoot, moduleDir, build)
                .moduleTargetDir()
                .resolve(".jk")
                .resolve("inputs");
    }

    /**
     * True when this module's outputs are known to have come from other inputs.
     *
     * <p>Deliberately not the negation of "matches": absent and unreadable both answer false. The
     * caller is deciding whether to force work, and only a record that positively disagrees is
     * evidence of a stale artifact.
     */
    static boolean outputsFromOtherInputs(Path workspaceRoot, Path moduleDir, JkBuild build, String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return false;
        try {
            Path file = recordFile(workspaceRoot, moduleDir, build);
            if (!Files.isRegularFile(file)) return false;
            String recorded = Files.readString(file, StandardCharsets.UTF_8).strip();
            return !recorded.isEmpty() && !recorded.equals(fingerprint);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** Record the inputs each built module's outputs came from. Best-effort: never fails a build. */
    public static void record(Path workspaceRoot, BuildGraph.Result graph, Map<Path, String> fingerprints) {
        if (graph == null || fingerprints == null || fingerprints.isEmpty()) return;
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            Path dir = unit.dir().toAbsolutePath().normalize();
            String fp = fingerprints.get(dir);
            if (fp == null || fp.isBlank()) continue;
            try {
                Path file = recordFile(workspaceRoot, dir, unit.manifest());
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(file, fp + "\n", StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unwritable) {
                // A target dir that cannot be written is the build's problem to report, not this
                // one's: the next preflight simply finds no record and treats the module as clean.
            }
        }
    }
}
