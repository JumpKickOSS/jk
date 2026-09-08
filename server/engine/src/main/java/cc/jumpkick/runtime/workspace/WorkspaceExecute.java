// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import org.jspecify.annotations.NullMarked;

/** Sole composer for the typed workspace build lifecycle. */
@NullMarked
public final class WorkspaceExecute {

    private WorkspaceExecute() {}

    /**
     * Build a workspace through preflight, resource seeding, preparation, scheduling, and final
     * aggregation. Every path settles through {@link #finish}.
     */
    public static WorkspaceResult buildWorkspace(WorkspaceRequest request, WorkspaceBuildListener listener) {
        WorkspacePreflightPhase.Outcome preflight = WorkspacePreflightPhase.run(request, listener);
        WorkspaceResult result;
        if (preflight instanceof WorkspacePreflightPhase.Completed completed) {
            result = completed.result();
        } else {
            WorkspaceResourcePhase.Resources resources =
                    WorkspaceResourcePhase.seed(((WorkspacePreflightPhase.Ready) preflight).context(), listener);
            WorkspacePreparePhase.Outcome prepare = WorkspacePreparePhase.prepare(resources, listener);
            if (prepare instanceof WorkspacePreparePhase.Completed completed) {
                result = completed.result();
            } else {
                WorkspaceRunPhase.Run run =
                        WorkspaceRunPhase.run(((WorkspacePreparePhase.Ready) prepare).prepared(), listener);
                result = WorkspaceFinalPhase.complete(run);
            }
        }
        return finish(listener, result);
    }

    /** The only runtime seam that emits workspace completion. */
    static WorkspaceResult finish(WorkspaceBuildListener listener, WorkspaceResult result) {
        listener.onWorkspaceFinish(result);
        return result;
    }
}
