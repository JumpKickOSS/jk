// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.task.TransferBoard;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Samples one job's {@link TransferBoard} onto its stream while the job runs: a frame every {@link
 * #PERIOD_MILLIS} that differs from the last, none at all for a job that downloads nothing. The cost
 * is the sample rate, never a per-chunk event, and the download threads never wait on it.
 *
 * <p>The sampler is stopped by a latch and never interrupted. Its sink hands a line to the job's
 * {@code WireWriter} queue and returns; nothing it does should be cut off halfway.
 */
final class DownloadsPublisher implements AutoCloseable {

    /** How often the board is sampled. */
    static final long PERIOD_MILLIS = 125L;

    private final CountDownLatch stop = new CountDownLatch(1);
    private final Thread sampler;
    private final TransferBoard board;
    private final Consumer<TransferBoard.Snapshot> sink;

    /** The last frame sent; {@link TransferBoard.Snapshot#EMPTY} until one is. */
    private volatile TransferBoard.Snapshot last = TransferBoard.Snapshot.EMPTY;

    private DownloadsPublisher(TransferBoard board, Consumer<TransferBoard.Snapshot> sink, String name) {
        this.board = Objects.requireNonNull(board, "board");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.sampler = SessionContext.startVirtual(name, () -> sample(board, sink));
    }

    /** Start sampling {@code board} into {@code sink}; close the publisher when the job's body is done. */
    static DownloadsPublisher start(TransferBoard board, Consumer<TransferBoard.Snapshot> sink, long jid) {
        return new DownloadsPublisher(board, sink, "jk-downloads-" + jid);
    }

    private void sample(TransferBoard board, Consumer<TransferBoard.Snapshot> sink) {
        do {
            sendIfChanged(board, sink);
        } while (!awaitStop());
    }

    /** Send the board's state when it differs from the last frame sent. */
    private void sendIfChanged(TransferBoard board, Consumer<TransferBoard.Snapshot> sink) {
        TransferBoard.Snapshot now = board.snapshot();
        if (now.equals(last)) return;
        try {
            sink.accept(now);
        } catch (RuntimeException e) {
            // The display is advisory: a sink that fails must not touch the job.
            Log.debug("downloads: sink failed", e);
        }
        last = now;
    }

    /** True once the publisher is closed, waiting at most one period; an interrupt counts as stop. */
    private boolean awaitStop() {
        try {
            return stop.await(PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /**
     * Stop sampling, wait out a sample in progress, then send the board's final state if the job
     * downloaded anything and its last frame is not already that state — including a download too
     * short for any sample to have caught. A body that ends within a
     * period of its last download would otherwise leave the client with a frame that still shows
     * transfers in flight, and a reader of the summary with no end to the burst.
     */
    @Override
    public void close() {
        stop.countDown();
        try {
            if (!sampler.join(Duration.ofSeconds(5))) Log.debug("downloads: sampler did not stop in 5s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // A board nothing touched equals EMPTY, the "nothing sent" state, so this sends nothing.
        sendIfChanged(board, sink);
    }
}
