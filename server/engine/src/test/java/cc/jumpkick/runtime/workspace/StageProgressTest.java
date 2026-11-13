// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A stage moves by its own downloads, forward only; a build's header moves with the stage. */
class StageProgressTest {

    private final List<String> reports = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final WorkspaceBuildListener listener = new WorkspaceBuildListener() {
        @Override
        public void onPreflight(String stage, int done, int total, String label) {
            reports.add(stage + " " + done + "/" + total);
            labels.add(label);
        }
    };

    private static TransferBoard.Snapshot snap(int settled, int planned) {
        return new TransferBoard.Snapshot(settled, planned, 0, 0L, 0, List.of());
    }

    private void report(TransferBoard.Snapshot d, StageProgress.Reported r) {
        StageProgress.report(d, new TransferBoard.InFlight(0, 0, 0), listener, "sync", StageProgress.SYNC_LABEL, r);
    }

    @Test
    void the_stage_reports_its_own_downloads_once_per_change_and_leaves_the_end_to_the_phase() {
        var r = new StageProgress.Reported(snap(0, 0));
        report(snap(0, 0), r); // nothing declared yet
        report(snap(10, 183), r);
        report(snap(10, 183), r); // no change
        report(snap(90, 183), r);
        report(snap(183, 183), r); // done: the phase says so itself

        assertThat(reports).containsExactly("sync 10/183", "sync 90/183");
    }

    @Test
    void what_an_earlier_stage_downloaded_does_not_start_this_one_most_of_the_way_up() {
        var r = new StageProgress.Reported(snap(180, 180)); // the lock downloaded 180 jars
        report(snap(185, 200), r);

        assertThat(reports).containsExactly("sync 5/20");
    }

    @Test
    void a_set_opening_mid_stage_holds_the_stage_rather_than_moving_it_back() {
        var r = new StageProgress.Reported(snap(0, 0));
        report(snap(180, 183), r);
        report(snap(181, 243), r); // a toolchain's closure opened 60 more
        report(snap(240, 243), r);

        assertThat(reports).containsExactly("sync 180/183", "sync 240/243");
    }

    @Test
    void the_lock_and_sync_bands_rise_in_pipeline_order() {
        double lockHalf = WorkspaceProgressTracker.preflightFraction("lock", 50, 100);
        double lockDone = WorkspaceProgressTracker.preflightFraction("lock", 1, 1);
        double graphDone = WorkspaceProgressTracker.preflightFraction("graph", 1, 1);
        double syncHalf = WorkspaceProgressTracker.preflightFraction("sync", 50, 100);
        double syncDone = WorkspaceProgressTracker.preflightFraction("sync", 100, 100);
        double planStart = WorkspaceProgressTracker.preflightFraction("plan", 0, 10);

        assertThat(lockHalf)
                .isGreaterThan(WorkspaceProgressTracker.preflightFraction("lock", 0, 0))
                .isLessThan(lockDone);
        assertThat(syncHalf).isGreaterThan(graphDone).isLessThan(syncDone);
        assertThat(planStart).isGreaterThanOrEqualTo(syncDone);
    }

    private static final long MB = 1024L * 1024L;

    @Test
    void a_stage_no_declared_set_moves_moves_by_the_bytes_its_bodies_stream() {
        // JK-RYA7W: a tool sync fetched a 645 MB IDE zip with no declared set, and the band sat still.
        var r = new StageProgress.Reported(snap(0, 0));
        List<TransferBoard.Row> rows =
                List.of(new TransferBoard.Row("com.jetbrains.intellij.idea:ideaIC:262.1", 129 * MB, 645 * MB));
        StageProgress.report(
                new TransferBoard.Snapshot(0, 0, 0, 129 * MB, 1, rows),
                new TransferBoard.InFlight(516 * MB, 129 * MB, 0),
                listener,
                "sync",
                StageProgress.SYNC_LABEL,
                r);
        rows = List.of(new TransferBoard.Row("com.jetbrains.intellij.idea:ideaIC:262.1", 516 * MB, 645 * MB));
        StageProgress.report(
                new TransferBoard.Snapshot(0, 0, 0, 516 * MB, 1, rows),
                new TransferBoard.InFlight(129 * MB, 516 * MB, 0),
                listener,
                "sync",
                StageProgress.SYNC_LABEL,
                r);

        assertThat(reports).containsExactly("sync 200/1000", "sync 800/1000");
        assertThat(labels.getLast()).isEqualTo(StageProgress.SYNC_LABEL + " · ideaIC 262.1 516 / 645 MB");
    }

    @Test
    void a_warm_stage_that_moves_no_bytes_reports_nothing() {
        var r = new StageProgress.Reported(snap(0, 0));
        StageProgress.report(
                snap(0, 0), new TransferBoard.InFlight(0, 0, 0), listener, "sync", StageProgress.SYNC_LABEL, r);
        assertThat(reports).isEmpty();
        assertThat(StageProgress.downloading(List.of(new TransferBoard.Row("g:small:1", 10_000, 20_000))))
                .isEmpty();
    }
}
