// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The whole workspace build result.
 *
 * @param errors run-level errors no single module owns: graph-resolution errors (composite deps),
 * in which case {@code modules} is empty and nothing was built, or a verdict over the finished
 * modules such as a {@code --class} selection that matched nothing anywhere
 * @param cancelled user/deadline cancel — distinct from a plain failure so clients can
 * render "cancelled" rather than "disconnected" / generic fail
 * @param conflict the conflict of the workspace lock's resolve with no solution, as one JSON
 * object; {@code ""} for any other result
 * @param release what a publish run's release lane did; {@code null} for every run that has none
 * @param images what a publish run's image lane pushed; empty for every run that has none
 */
public record WorkspaceResult(
        boolean success,
        int exitCode,
        List<ModuleOutcome> modules,
        List<String> errors,
        boolean cancelled,
        @Nullable SyncCounts sync,
        String conflict,
        @Nullable ReleaseOutcome release,
        List<ImagePush> images) {

    /** What a {@code jk sync} run settled: artifacts downloaded, and artifacts already on disk. */
    public record SyncCounts(int fetched, int upToDate) {}

    /** No sync counts and no conflict: every goal but {@code jk sync}, until a phase adds them. */
    public WorkspaceResult(
            boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors, boolean cancelled) {
        this(success, exitCode, modules, errors, cancelled, null, "", null, List.of());
    }

    /** Compatibility constructor — not cancelled. */
    public WorkspaceResult(boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors) {
        this(success, exitCode, modules, errors, false);
    }

    /**
     * Whether the run was up to date: it succeeded and no module did productive work ({@link
     * ModuleOutcome#didWork}), a run that entered no module included. The one test every output
     * mode reads to say so: the settle line, {@code workspace-finish}'s {@code upToDate} and the
     * agent report.
     */
    public boolean upToDate() {
        if (!success || cancelled || !errors.isEmpty()) return false;
        for (ModuleOutcome m : modules) {
            if (!m.success() || m.didWork()) return false;
        }
        return true;
    }

    /** This result with what the sync settled. */
    public WorkspaceResult withSync(SyncCounts counts) {
        return new WorkspaceResult(success, exitCode, modules, errors, cancelled, counts, conflict, release, images);
    }

    /** This result with the conflict of the lock's resolve with no solution. */
    public WorkspaceResult withConflict(String conflict) {
        return new WorkspaceResult(success, exitCode, modules, errors, cancelled, sync, conflict, release, images);
    }

    /** This result with what the release lane did. */
    public WorkspaceResult withRelease(ReleaseOutcome outcome) {
        return new WorkspaceResult(success, exitCode, modules, errors, cancelled, sync, conflict, outcome, images);
    }

    /** This result with what the image lane pushed. */
    public WorkspaceResult withImages(List<ImagePush> pushed) {
        return new WorkspaceResult(
                success, exitCode, modules, errors, cancelled, sync, conflict, release, List.copyOf(pushed));
    }

    /** This result failed with {@code error}, as a release lane that stopped leaves it. */
    public WorkspaceResult failed(int exit, String error, @Nullable ReleaseOutcome outcome) {
        List<String> all = new ArrayList<>(errors);
        all.add(error);
        return new WorkspaceResult(false, exit, modules, all, cancelled, sync, conflict, outcome, images);
    }
}
