// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.ProjectBuilds;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Durable monotonic per-project build numbers allocated at <em>request-start</em>.
 *
 * <p>Numbers live in {@code ~/.local/state/jk/builds/projects/&lt;key&gt;/run-number.txt}. Finish-time
 * metrics harvest must <strong>not</strong> mint a second number.
 */
public final class BuildNumberAllocator {

    private BuildNumberAllocator() {}

    /** @deprecated per-project {@code run-number.txt}; kept so call sites compile. */
    @Deprecated
    public static Path defaultFile() {
        return ProjectBuilds.buildsRoot().resolve("run-number.txt");
    }

    /**
     * Next build number for {@code projectDir} (≥ 1). {@code coord} may be null (falls back to
     * unknown). {@code countersFile}/{@code metricsFile} are ignored (API compat for call sites).
     */
    public static long allocate(Path countersFile, Path metricsFile, String projectDir) {
        return allocate(projectDir, null);
    }

    /** Next build number for {@code projectDir} + optional {@code coord}. */
    public static long allocate(String projectDir, String coord) {
        if (projectDir == null || projectDir.isBlank()) return 0;
        try {
            Path dir = Path.of(projectDir);
            Path home = ProjectBuilds.projectHome(coord, dir);
            java.nio.file.Files.createDirectories(home);
            ProjectBuilds.writeIdentity(home, coord, dir);
            return ProjectBuilds.allocateRunNumber(home);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
