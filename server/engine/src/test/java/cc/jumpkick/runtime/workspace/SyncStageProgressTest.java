// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A build's sync moves its own stage by the files it settles, never the whole run's estimate. */
class SyncStageProgressTest {

    private final List<String> reports = new ArrayList<>();
    private final WorkspaceBuildListener listener = new WorkspaceBuildListener() {
        @Override
        public void onPreflight(String stage, int done, int total, String label) {
            reports.add(stage + " " + done + "/" + total);
        }
    };
    private final int[] last = {-1, -1};

    private static TransferBoard.Snapshot snap(int settled, int planned) {
        return new TransferBoard.Snapshot(settled, planned, 0, 0L, 0, List.of());
    }

    @Test
    void the_stage_reports_settled_files_once_per_change_and_leaves_the_end_to_the_phase() {
        SyncStageProgress.report(snap(0, 0), listener, last); // no declared set
        SyncStageProgress.report(snap(10, 183), listener, last);
        SyncStageProgress.report(snap(10, 183), listener, last); // no change
        SyncStageProgress.report(snap(90, 183), listener, last);
        SyncStageProgress.report(snap(183, 183), listener, last); // done: the phase says so itself

        assertThat(reports).containsExactly("sync 10/183", "sync 90/183");
    }

    @Test
    void the_sync_band_sits_between_the_graph_and_the_plan_and_moves_with_it() {
        double graphDone = WorkspaceProgressTracker.preflightFraction("graph", 1, 1);
        double syncHalf = WorkspaceProgressTracker.preflightFraction("sync", 50, 100);
        double syncDone = WorkspaceProgressTracker.preflightFraction("sync", 100, 100);
        double planStart = WorkspaceProgressTracker.preflightFraction("plan", 0, 10);

        assertThat(syncHalf).isGreaterThan(graphDone).isLessThan(syncDone);
        assertThat(planStart).isGreaterThanOrEqualTo(syncDone);
    }
}
