// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A preflight stage's progress while it downloads: the files its declared sets have downloaded,
 * of how many, reported as the stage's {@code done/total} every {@link #PERIOD_MILLIS} that it
 * moves forward. The header's preflight band for the stage moves with it — the lock's for a first
 * lock, which is where a cold project's jars come down, and the sync's after it.
 *
 * <p>Only the stage's own work counts: the board's totals when it started are subtracted, so a
 * set an earlier stage downloaded does not start this one most of the way up. A report never goes
 * below the last one: a set opening mid-stage (a closure's depth) adds files, and the band holds
 * rather than slipping back.
 */
final class StageProgress implements AutoCloseable {

    /** How often the board is read. */
    static final long PERIOD_MILLIS = 500L;

    /** The sync stage's label while it runs; WorkspaceSyncPhase announces the same one. */
    static final String SYNC_LABEL = "Syncing dependencies…";

    private final CountDownLatch stop = new CountDownLatch(1);
    private boolean closed;
    private final Thread sampler;

    /** What one stage has reported, so the next report moves only forward. */
    static final class Reported {
        final int settledAtStart;
        final int plannedAtStart;
        double fraction = -1;
        int done = -1;
        int total = -1;

        Reported(TransferBoard.Snapshot atStart) {
            this.settledAtStart = atStart.settled();
            this.plannedAtStart = atStart.planned();
        }
    }

    private StageProgress(TransferBoard board, WorkspaceBuildListener listener, String stage, String label) {
        Reported reported = new Reported(board.snapshot());
        this.sampler = SessionContext.startVirtual("jk-stage-progress-" + stage, () -> {
            try {
                do {
                    // Under the lock close() takes: once the stage is closed, its last word stands.
                    synchronized (this) {
                        if (closed) break;
                        report(board.snapshot(), listener, stage, label, reported);
                    }
                } while (!stop.await(PERIOD_MILLIS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // Progress is advisory: a failure here must not touch the stage.
                Log.debug("stage progress: sampler failed", e);
            }
        });
    }

    /** Sample the run's board into {@code stage} until closed. */
    static StageProgress start(WorkspaceBuildListener listener, String stage, String label) {
        return new StageProgress(SessionContext.current().io().transfers(), listener, stage, label);
    }

    /**
     * One report when the stage's own downloads moved forward, and only while files remain: a
     * stage reported complete leaves the live chain, and the stage's own last word ends it.
     */
    static void report(
            TransferBoard.Snapshot d, WorkspaceBuildListener listener, String stage, String label, Reported r) {
        int total = d.planned() - r.plannedAtStart;
        int done = d.settled() - r.settledAtStart;
        if (total <= 0 || done < 0 || done >= total) return;
        double fraction = (double) done / total;
        if (fraction <= r.fraction || (done == r.done && total == r.total)) return;
        r.fraction = fraction;
        r.done = done;
        r.total = total;
        listener.onPreflight(stage, done, total, label);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true; // a report in progress finishes first; none starts after
        }
        stop.countDown();
        try {
            sampler.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
