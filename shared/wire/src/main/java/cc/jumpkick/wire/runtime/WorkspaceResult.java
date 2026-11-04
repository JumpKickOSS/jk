// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

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
 */
public record WorkspaceResult(
        boolean success,
        int exitCode,
        List<ModuleOutcome> modules,
        List<String> errors,
        boolean cancelled,
        @Nullable SyncCounts sync) {

    /** What a {@code jk sync} run settled: artifacts downloaded, and artifacts already on disk. */
    public record SyncCounts(int fetched, int upToDate) {}

    /** No sync counts: every goal but {@code jk sync}. */
    public WorkspaceResult(
            boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors, boolean cancelled) {
        this(success, exitCode, modules, errors, cancelled, null);
    }

    /** Compatibility constructor — not cancelled. */
    public WorkspaceResult(boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors) {
        this(success, exitCode, modules, errors, false);
    }

    /** This result with what the sync settled. */
    public WorkspaceResult withSync(SyncCounts counts) {
        return new WorkspaceResult(success, exitCode, modules, errors, cancelled, counts);
    }
}
