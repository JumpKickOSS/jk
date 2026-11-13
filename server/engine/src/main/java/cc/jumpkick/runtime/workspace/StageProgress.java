// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.time.Duration;
import java.util.List;
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
        final long bytesAtStart;
        double fraction = -1;
        int done = -1;
        int total = -1;
        String label = "";

        Reported(TransferBoard.Snapshot atStart) {
            this.settledAtStart = atStart.settled();
            this.plannedAtStart = atStart.planned();
            this.bytesAtStart = atStart.bytes();
        }
    }

    /** The units a stage moved by bytes reports its fraction in: {@code done} of this many. */
    static final int BYTE_UNITS = 1000;

    private StageProgress(TransferBoard board, WorkspaceBuildListener listener, String stage, String label) {
        Reported reported = new Reported(board.snapshot());
        this.sampler = SessionContext.startVirtual("jk-stage-progress-" + stage, () -> {
            try {
                do {
                    // Under the lock close() takes: once the stage is closed, its last word stands.
                    synchronized (this) {
                        if (closed) break;
                        report(board.snapshot(), board.inFlight(), listener, stage, label, reported);
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
            TransferBoard.Snapshot d,
            TransferBoard.InFlight flight,
            WorkspaceBuildListener listener,
            String stage,
            String label,
            Reported r) {
        int total = d.planned() - r.plannedAtStart;
        int done = d.settled() - r.settledAtStart;
        String said = label + downloading(d.rows());
        if (total <= 0 || done < 0 || done >= total) {
            reportBytes(d, flight, listener, stage, said, r);
            return;
        }
        double fraction = (double) done / total;
        if (fraction <= r.fraction || (done == r.done && total == r.total && said.equals(r.label))) return;
        r.fraction = Math.max(r.fraction, fraction);
        r.done = done;
        r.total = total;
        r.label = said;
        listener.onPreflight(stage, done, total, said);
    }

    /**
     * A stage no declared set moves, that downloads anyway: a tool or plugin sync fetches its files
     * one by one, a 645 MB IDE distribution among them for jk's own tree, and the band sat still for
     * the 55 s it took (JK-RYA7W). Its fraction is the bytes this stage moved of those plus what the
     * bodies streaming now still owe, reported in {@link #BYTE_UNITS}; a body of unknown length owes
     * nothing known, so the fraction rides the ones that declared theirs.
     */
    private static void reportBytes(
            TransferBoard.Snapshot d,
            TransferBoard.InFlight flight,
            WorkspaceBuildListener listener,
            String stage,
            String said,
            Reported r) {
        long moved = d.bytes() - r.bytesAtStart;
        if (moved <= 0 || d.inflight() == 0 || flight.owed() <= 0) return;
        double fraction = (double) moved / (moved + flight.owed());
        int done = (int) Math.min(BYTE_UNITS - 1, Math.round(fraction * BYTE_UNITS));
        // The band never goes back: a new body opening adds what it owes, and the band holds.
        if (done <= r.done && r.total == BYTE_UNITS && said.equals(r.label)) return;
        done = Math.max(done, r.total == BYTE_UNITS ? r.done : 0);
        r.done = done;
        r.total = BYTE_UNITS;
        r.label = said;
        listener.onPreflight(stage, done, BYTE_UNITS, said);
    }

    /**
     * The largest body streaming now, as the label's tail: {@code  · ideaIC 312 / 645 MB}. Empty
     * when nothing past the dwell streams, or nothing large enough to name (under a megabyte).
     */
    static String downloading(List<TransferBoard.Row> rows) {
        TransferBoard.Row largest = null;
        for (TransferBoard.Row row : rows) {
            long size = Math.max(row.total(), row.read());
            if (largest == null || size > Math.max(largest.total(), largest.read())) largest = row;
        }
        if (largest == null || Math.max(largest.total(), largest.read()) < MIB) return "";
        String name = shortName(largest.label());
        long readMb = largest.read() / MIB;
        return largest.total() > 0
                ? " · " + name + " " + readMb + " / " + largest.total() / MIB + " MB"
                : " · " + name + " " + readMb + " MB";
    }

    private static final long MIB = 1024L * 1024L;

    /** {@code com.jetbrains.intellij.idea:ideaIC:262.10968.63} reads as {@code ideaIC 262.10968.63}. */
    static String shortName(String label) {
        int bang = label.indexOf('!');
        if (bang > 0) label = label.substring(0, bang);
        String[] gav = label.split(":");
        if (gav.length >= 3) return gav[1] + " " + gav[2];
        int slash = label.lastIndexOf('/');
        return slash >= 0 ? label.substring(slash + 1) : label;
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
