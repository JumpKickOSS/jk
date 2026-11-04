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

    /**
     * The rest priced at the run's own throughput, or {@code -1} until {@link #MEASURED_AFTER}
     * artifacts have landed: what the bodies streaming now still owe, plus each file not started
     * yet at the size the files of known size averaged, over the bytes a second the run has moved. A
     * large jar in flight is priced at its declared length, so the slow tail of a sync is seen
     * before the bar gets there.
     */
    private long measured(TransferBoard.Snapshot d, int remaining, long now) {
        if (board.artifactsLanded() - artifactsBefore < MEASURED_AFTER) return -1;
        long elapsedMs = (now - planStartNanos) / 1_000_000L;
        long moved = d.bytes() - bytesAtStart;
        int finishedFiles = d.files() - filesAtStart;
        if (elapsedMs <= 0 || moved <= 0 || finishedFiles <= 0) return -1;
        TransferBoard.InFlight flight = board.inFlight();
        // A file not started yet is priced at the size of every file whose size is known: the ones
        // that landed and the ones in flight that declared a length. Small files land first, so the
        // landed ones alone would price the large jars still queued as small.
        int declared = d.inflight() - flight.undeclared();
        long known = moved + flight.owed();
        long perFile = Math.max(1, known / Math.max(1, finishedFiles + declared));
        int notStarted = Math.max(0, remaining - d.inflight());
        long owed = flight.owed() + (long) flight.undeclared() * perFile + (long) notStarted * perFile;
        double bytesPerMs = (double) moved / elapsedMs;
        return Math.round(owed / bytesPerMs);
    }
}
