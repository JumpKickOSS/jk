// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The result of one module's build (see {@link WorkspaceBuildListener#onModuleFinish}).
 *
 * @param didWork {@code true} when at least one productive step (compile / test / package / …) did
 * real work rather than a cache hit or no-op. Used so the CLI can say "checked N modules, all
 * up to date" instead of "built N modules" when every re-entered module was fully cached
 * . Failures count as did-work (the module was not a pure check).
 *
 * @param cancelled session/user cancel (Ctrl-C, {@code jk cancel}, deadline) ended this module —
 * not a compile/test failure. The shorter overloads leave this {@code false}.
 *
 * @param shelved what an install pass published to the shelf for this module; {@code null} when
 * the plan carried no {@code cache-install} step, or never reached it.
 */
public record ModuleOutcome(
        String coord,
        Path dir,
        boolean success,
        int exitCode,
        long millis,
        boolean didWork,
        boolean cancelled,
        @Nullable Image image,
        @Nullable Shelved shelved) {

    /**
     * Image-terminal outcome for a {@code jk image} workspace module — what the terminal step
     * actually did (push / daemon load / tarball write), so the CLI can print the same
     * "Pushed &lt;ref&gt;" / "Wrote OCI tarball" / "Loaded … into docker" tail the single-project
     * path shows. {@code null} for non-image modules; all fields nullable.
     */
    public record Image(
            @Nullable String ref,
            @Nullable String tarball,
            @Nullable String name,
            @Nullable String version,
            @Nullable String daemonExe) {}

    /**
     * The shelf slot an install pass wrote for a module: its plain {@code group:artifact:version}
     * and the sha256 (lower-case hex) of the thin jar and of the POM the engine published — or
     * found already on the shelf at those bytes. The client pins the shelf from these, never from a
     * re-read of the shelf, so a concurrent install landing after the pass cannot be mistaken for it.
     */
    public record Shelved(String coordinate, String jarSha256, String pomSha256) {}

    public ModuleOutcome(
            String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork, boolean cancelled) {
        this(coord, dir, success, exitCode, millis, didWork, cancelled, null, null);
    }

    /** Assume work was done when the caller does not know (fail-open for "built"). */
    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis) {
        this(coord, dir, success, exitCode, millis, true, false, null, null);
    }

    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork) {
        this(coord, dir, success, exitCode, millis, didWork, false, null, null);
    }

    public ModuleOutcome withImage(Image img) {
        return new ModuleOutcome(coord, dir, success, exitCode, millis, didWork, cancelled, img, shelved);
    }

    public ModuleOutcome withShelved(Shelved shelf) {
        return new ModuleOutcome(coord, dir, success, exitCode, millis, didWork, cancelled, image, shelf);
    }
}
