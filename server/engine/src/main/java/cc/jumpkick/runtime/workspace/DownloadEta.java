// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.FetchTimings;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.HostRateLimiter;
import cc.jumpkick.repo.DownloadSlots;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Prices a run's downloads in time while its preflight locks and syncs, so the header bar moves
 * through a cold lock or sync instead of sitting at a few percent: the remaining files of the step
 * that declared its set, times what a file is taking. The header's clock strategy turns that into
 * percent and a countdown, and holds its peak, so a later estimate never moves it back.
 *
 * <p>No request is made to price anything: once a few artifacts have landed, the rest is priced in
 * bytes at the throughput the run has measured (see {@link #measured}); before that, a file costs
 * the host's recorded fetch time ({@link FetchTimings}) over the downloads that run at once. With no history and nothing landed yet there is no estimate, and the
 * one the lock seeded stands.
 */
final class DownloadEta {

    /** How often the board is read. */
    static final long PERIOD_MILLIS = 500L;

    /** Artifacts that must land before the run's own rate replaces the recorded one. */
    static final int MEASURED_AFTER = 3;

    /** A new estimate is sent when it moves by this much of the last one, or by {@link #MOVE_MILLIS}. */
    static final double MOVE_FRACTION = 0.20;

    /** The smallest move worth sending. */
    static final long MOVE_MILLIS = 500L;

    private final TransferBoard board;
    private final Clock clock;
    private final LongConsumer eta;
    private final long priorMsPerFile;
    private final long artifactsBefore;

    private final long planStartNanos;
    private final long bytesAtStart;
    private final int filesAtStart;
    private long lastSentMs = -1;

    DownloadEta(TransferBoard board, Clock clock, LongConsumer eta, long priorMsPerFile) {
        this.board = Objects.requireNonNull(board, "board");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eta = Objects.requireNonNull(eta, "eta");
        this.priorMsPerFile = Math.max(0, priorMsPerFile);
        this.artifactsBefore = board.artifactsLanded();
        // The run's rate is measured from here: the preflight's start, before its first download.
        TransferBoard.Snapshot at = board.snapshot();
        this.planStartNanos = clock.nanos();
        this.bytesAtStart = at.bytes();
        this.filesAtStart = at.files();
    }

    /** What one file costs before the run has measured its own: the recorded fetch time over the downloads at once. */
    static long priorMsPerFile() {
        long fetchMs = FetchTimings.trimmedMeanMs();
        int atOnce = Math.max(1, Math.min(DownloadSlots.width(), HostRateLimiter.MIRROR_PERMITS));
        return fetchMs <= 0 ? 0 : Math.max(1, fetchMs / atOnce);
    }

    /**
     * Price the run's downloads on a thread of its own every {@link #PERIOD_MILLIS} until closed,
     * sending each estimate to {@code listener} as the run's remaining time.
     */
    static AutoCloseable start(WorkspaceBuildListener listener) {
        TransferBoard board = SessionContext.current().io().transfers();
        DownloadEta eta = new DownloadEta(board, Clock.SYSTEM, listener::onRemainingEstimate, priorMsPerFile());
        CountDownLatch stop = new CountDownLatch(1);
        Thread sampler = SessionContext.startVirtual("jk-download-eta", () -> {
            try {
                do {
                    eta.sample();
                } while (!stop.await(PERIOD_MILLIS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // The estimate is advisory: a failure here must not touch the run.
                Log.debug("download eta: sampler failed", e);
            }
        });
        return () -> {
            stop.countDown();
            try {
                sampler.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Read the board once and send an estimate when it says something new. */
    void sample() {
        TransferBoard.Snapshot d = board.snapshot();
        int remaining = d.planned() - d.settled();
        if (d.planned() <= 0 || remaining <= 0) return;
        long now = clock.nanos();
        // A set settling files already on disk downloads nothing, and is priced at nothing: a warm
        // sync of 800 jars must not announce 800 downloads' worth of countdown.
        if (d.bytes() - bytesAtStart <= 0 && d.inflight() == 0) return;
        long remainingMs = measured(d, remaining, now);
        if (remainingMs < 0) remainingMs = priorMsPerFile > 0 ? remaining * priorMsPerFile : -1;
        if (remainingMs < 0) return;
        if (lastSentMs >= 0
                && Math.abs(remainingMs - lastSentMs) < Math.max(MOVE_MILLIS, (long) (lastSentMs * MOVE_FRACTION))) {
            return;
        }
        lastSentMs = remainingMs;
        eta.accept(remainingMs);
    }

    /** How far back the throughput is read: long enough to smooth a burst, short enough to follow a ramp. */
    static final long RATE_WINDOW_NANOS = 4_000_000_000L;

    /** The board's bytes at each sample within {@link #RATE_WINDOW_NANOS}, oldest first: {nanos, bytes}. */
    private final ArrayDeque<long[]> window = new ArrayDeque<>();

    /**
     * The rest priced at the run's own throughput, or {@code -1} until {@link #MEASURED_AFTER}
     * artifacts have landed: what the bodies streaming now still owe, plus each file not started
     * yet at a typical file's size, over the bytes a second moved in the last {@link
     * #RATE_WINDOW_NANOS}. A large body in flight is priced at its declared length, so the slow tail
     * of a sync is seen before the bar gets there.
     *
     * <p>The throughput is the recent one, not the average since the preflight began: that counted
     * the lock's seconds before the first byte and the connections' ramp, and a fresh sync of jk's
     * tree read 49 s left at 2.5 s of 12. A file not started is priced at the median of the files
     * whose size is known (the landed ones at their mean, each body in flight at its declared
     * length), not their mean, so the 645 MB IDE zip in flight does not price each of 200 small jars
     * queued behind it as a twentieth of itself (JK-RYA7W).
     */
    private long measured(TransferBoard.Snapshot d, int remaining, long now) {
        window.addLast(new long[] {now, d.bytes()});
        while (window.size() > 2 && now - window.peekFirst()[0] > RATE_WINDOW_NANOS) window.removeFirst();
        if (board.artifactsLanded() - artifactsBefore < MEASURED_AFTER) return -1;
        long moved = d.bytes() - bytesAtStart;
        int finishedFiles = d.files() - filesAtStart;
        if (moved <= 0 || finishedFiles <= 0) return -1;
        long[] oldest = window.peekFirst();
        long spanNanos = now - oldest[0];
        long spanBytes = d.bytes() - oldest[1];
        double bytesPerMs = spanNanos > 0 && spanBytes > 0
                ? spanBytes / (spanNanos / 1_000_000.0)
                : moved / Math.max(1.0, (now - planStartNanos) / 1_000_000.0);
        TransferBoard.InFlight flight = board.inFlight();
        long landedBytes = Math.max(0, moved - flight.read());
        long perFile = typicalFile(landedBytes / finishedFiles, finishedFiles, d.rows());
        int notStarted = Math.max(0, remaining - d.inflight());
        long owed = flight.owed() + (long) flight.undeclared() * perFile + (long) notStarted * perFile;
        return Math.round(owed / bytesPerMs);
    }

    /**
     * The median size over {@code landed} files of {@code landedMean} bytes each and the declared
     * lengths of the bodies in flight.
     */
    static long typicalFile(long landedMean, int landed, List<TransferBoard.Row> inFlight) {
        List<Long> declared = new ArrayList<>();
        for (TransferBoard.Row r : inFlight) if (r.total() > 0) declared.add(r.total());
        declared.sort(null);
        int n = landed + declared.size();
        int mid = n / 2;
        // The landed files sit together at landedMean; the declared lengths around them in order.
        int below = 0;
        while (below < declared.size() && declared.get(below) < landedMean) below++;
        long at;
        if (mid < below) at = declared.get(mid);
        else if (mid < below + landed) at = landedMean;
        else at = declared.get(mid - landed);
        return Math.max(1, at);
    }
}
