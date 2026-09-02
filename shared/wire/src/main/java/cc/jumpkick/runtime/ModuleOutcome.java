// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;

/**
 * The result of one module's build (see {@link WorkspaceBuildListener#onModuleFinish}).
 *
 * @param didWork {@code true} when at least one productive step (compile / test / package / …) did
 * real work rather than a cache hit or no-op. Used so the CLI can say "checked N modules, all
 * up to date" instead of "built N modules" when every re-entered module was fully cached
 * . Failures count as did-work (the module was not a pure check).
 *
 * @param cancelled session/user cancel (Ctrl-C, {@code jk cancel}, deadline) ended this module —
 * not a compile/test failure. Additive; older callers omit it.
 */
public record ModuleOutcome(
        String coord,
        Path dir,
        boolean success,
        int exitCode,
        long millis,
        boolean didWork,
        boolean cancelled,
        Image image) {

    /**
     * Image-terminal outcome for a {@code jk image} workspace module — what the terminal step
     * actually did (push / daemon load / tarball write), so the CLI can print the same
     * "Pushed &lt;ref&gt;" / "Wrote OCI tarball" / "Loaded … into docker" tail the single-project
     * path shows. {@code null} for non-image modules; all fields nullable.
     */
    public record Image(String ref, String tarball, String name, String version, String daemonExe) {}

    public ModuleOutcome(
            String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork, boolean cancelled) {
        this(coord, dir, success, exitCode, millis, didWork, cancelled, null);
    }

    /** Back-compat: assume work was done when the caller does not know (fail-open for "built"). */
    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis) {
        this(coord, dir, success, exitCode, millis, true, false, null);
    }

    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork) {
        this(coord, dir, success, exitCode, millis, didWork, false, null);
    }

    public ModuleOutcome withImage(Image img) {
        return new ModuleOutcome(coord, dir, success, exitCode, millis, didWork, cancelled, img);
    }
}
