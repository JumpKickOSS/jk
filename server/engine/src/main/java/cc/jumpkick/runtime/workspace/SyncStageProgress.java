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
 * The sync stage's progress while it runs: the files its declared sets have settled, of how many,
 * reported as the stage's {@code done/total} every {@link #PERIOD_MILLIS} that it changes. The
 * header's preflight band moves with it, the way the lock and graph stages move theirs.
 */
final class SyncStageProgress implements AutoCloseable {

    /** How often the board is read. */
    static final long PERIOD_MILLIS = 500L;

    /** The stage's label while it runs; WorkspaceSyncPhase announces the same one. */
    static final String LABEL = "Syncing dependencies…";

    private final CountDownLatch stop = new CountDownLatch(1);
    private final Thread sampler;

    private SyncStageProgress(TransferBoard board, WorkspaceBuildListener listener) {
        this.sampler = SessionContext.startVirtual("jk-sync-progress", () -> {
            int[] last = {-1, -1};
            try {
                do {
                    report(board.snapshot(), listener, last);
                } while (!stop.await(PERIOD_MILLIS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // Progress is advisory: a failure here must not touch the sync.
                Log.debug("sync progress: sampler failed", e);
            }
        });
    }

    /** Sample the run's board into the sync stage until closed. */
    static SyncStageProgress start(WorkspaceBuildListener listener) {
        return new SyncStageProgress(SessionContext.current().io().transfers(), listener);
    }

    /**
     * One report when the declared sets moved since the last, and only while files remain: a
     * stage reported complete leaves the live chain, and the stage's own last word ends it.
     */
    static void report(TransferBoard.Snapshot d, WorkspaceBuildListener listener, int[] last) {
        if (d.planned() <= 0 || d.settled() >= d.planned()) return;
        int settled = d.settled();
        if (settled == last[0] && d.planned() == last[1]) return;
        last[0] = settled;
        last[1] = d.planned();
        listener.onPreflight("sync", settled, d.planned(), LABEL);
    }

    @Override
    public void close() {
        stop.countDown();
        try {
            sampler.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
