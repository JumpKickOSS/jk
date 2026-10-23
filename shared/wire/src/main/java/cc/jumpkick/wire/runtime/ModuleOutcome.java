// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
 * @param stopped another module's failure stopped this one before it could be judged (see
 * {@link #stoppedByFailure}); {@code success} is then {@code false}.
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
        boolean stopped,
        @Nullable Image image,
        @Nullable Shelved shelved,
        @Nullable Published published) {

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

    /**
     * What a publish pass sent: where to, how many files (none on a dry run), the files it wrote
     * under {@code target/}, a Central bundle's entries, and the Portal deployment it ended on.
     */
    public record Published(
            String destination,
            boolean dryRun,
            int files,
            List<String> written,
            List<String> bundle,
            @Nullable String deploymentId,
            @Nullable String deploymentState,
            List<String> deploymentErrors) {
        public Published {
            written = written == null ? List.of() : List.copyOf(written);
            bundle = bundle == null ? List.of() : List.copyOf(bundle);
            deploymentErrors = deploymentErrors == null ? List.of() : List.copyOf(deploymentErrors);
        }
    }

    public ModuleOutcome(
            String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork, boolean cancelled) {
        this(coord, dir, success, exitCode, millis, didWork, cancelled, false, null, null, null);
    }

    /** Assume work was done when the caller does not know (fail-open for "built"). */
    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis) {
        this(coord, dir, success, exitCode, millis, true, false, false, null, null, null);
    }

    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork) {
        this(coord, dir, success, exitCode, millis, didWork, false, false, null, null, null);
    }

    public ModuleOutcome withStopped(boolean stop) {
        return new ModuleOutcome(
                coord, dir, success, exitCode, millis, didWork, cancelled, stop, image, shelved, published);
    }

    /**
     * Whether a module was stopped by a failure elsewhere rather than failed: it did not succeed,
     * the run was not cancelled, it recorded steps and none of them failed ({@code CANCELLED} is not
     * a failure), and something else in the run failed. The live module-finish and the results
     * journal both decide with this rule.
     *
     * @param stepStatuses the module's step terminals by name ({@code FAIL}, {@code CANCELLED}, …)
     */
    public static boolean stoppedByFailure(
            boolean success, boolean runCancelled, Collection<String> stepStatuses, boolean failedElsewhere) {
        if (success || runCancelled || stepStatuses.isEmpty() || !failedElsewhere) return false;
        for (String status : stepStatuses) {
            if (failedStatus(status)) return false;
        }
        return true;
    }

    private static boolean failedStatus(@Nullable String status) {
        if (status == null) return false;
        String u = status.trim().toUpperCase(Locale.ROOT);
        return "FAIL".equals(u) || "FAILED".equals(u);
    }

    public ModuleOutcome withImage(Image img) {
        return new ModuleOutcome(
                coord, dir, success, exitCode, millis, didWork, cancelled, stopped, img, shelved, published);
    }

    public ModuleOutcome withShelved(Shelved shelf) {
        return new ModuleOutcome(
                coord, dir, success, exitCode, millis, didWork, cancelled, stopped, image, shelf, published);
    }

    public ModuleOutcome withPublished(Published pub) {
        return new ModuleOutcome(
                coord, dir, success, exitCode, millis, didWork, cancelled, stopped, image, shelved, pub);
    }
}
