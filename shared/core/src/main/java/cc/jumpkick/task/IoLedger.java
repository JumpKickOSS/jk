// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/**
 * Per-run byte accounting: how much this invocation moved over the network ({@code remote}) and
 * through the local build cache ({@code local}), split by direction.
 *
 * <p><strong>Direction.</strong> {@code up} is bytes leaving the workspace — published to a remote
 * repository, or stored into the local cache. {@code down} is bytes arriving — downloaded from a
 * remote, or restored out of the local cache. So a cold build reads mostly {@code remote down}; a
 * warm one reads mostly {@code local down}; a publish adds {@code remote up}.
 *
 * <p><strong>Files at rest, never streams.</strong> Every call site adds the size of a file that is
 * already on disk (or the length of a body it just handed to a transport). Nothing wraps a stream to
 * count bytes as they flow, so metering can never slow a transfer or truncate one on error. That
 * also means transport framing/compression is not counted — these are payload bytes.
 *
 * <p><strong>One per request.</strong> The engine makes one ledger per request and the request
 * carries it ({@code cc.jumpkick.config.Request#io}); {@code Session.defaults()} adopts the bound
 * request's, so every session derived inside that request — including work handed to the shared
 * {@code JkThreads} pools, which carry the request — accumulates into the same ledger. Outside a
 * request a session gets its own detached ledger and the numbers are simply discarded. Deep code meters through the
 * session it already has: {@code SessionContext.current().io().remoteDown(size)}.
 *
 * <p>Thread-safe: four {@link LongAdder}s under concurrent module builds and parallel fetches.
 * Best-effort by design — a failed {@code stat} contributes 0 rather than failing the build.
 *
 * <p>Not metered: git-source clones (the {@code git} subprocess owns that transfer) and the engine's
 * own housekeeping (journal, metrics, log GC).
 */
public final class IoLedger {

    private final LongAdder remoteUp = new LongAdder();
    private final LongAdder remoteDown = new LongAdder();
    private final LongAdder localUp = new LongAdder();
    private final LongAdder localDown = new LongAdder();

    /** This run's downloads as they stream, for a live display; see {@link TransferBoard}. */
    private final TransferBoard transfers;

    /** What this run has said once and where its notes go ({@link RunNotices}). */
    private final RunNotices.Run notices = new RunNotices.Run();

    public IoLedger() {
        this(Clock.SYSTEM);
    }

    /** A ledger whose download board reads {@code clock}: a test moves time past a row's dwell. */
    public IoLedger(Clock clock) {
        this.transfers = new TransferBoard(clock);
    }

    /** One run's totals, in bytes. */
    public record Totals(long remoteUp, long remoteDown, long localUp, long localDown) {

        public static final Totals ZERO = new Totals(0, 0, 0, 0);

        /** True when the run moved no bytes at all — nothing worth showing. */
        public boolean isEmpty() {
            return remoteUp == 0 && remoteDown == 0 && localUp == 0 && localDown == 0;
        }
    }

    /** Bytes uploaded to a remote (published artifacts, checksums, signatures). */
    public void remoteUp(long bytes) {
        if (bytes > 0) remoteUp.add(bytes);
    }

    /** Bytes downloaded from a remote (artifacts, POMs, metadata, JDK/tool archives). */
    public void remoteDown(long bytes) {
        if (bytes > 0) remoteDown.add(bytes);
    }

    /** As {@link #remoteDown(long)}, taking the size of a file already on disk. */
    public void remoteDown(Path file) {
        remoteDown(sizeOf(file));
    }

    /** Bytes written into the local build cache (this run's action outputs). */
    public void localUp(long bytes) {
        if (bytes > 0) localUp.add(bytes);
    }

    /** Bytes restored out of the local build cache (cache-hit outputs copied back). */
    public void localDown(long bytes) {
        if (bytes > 0) localDown.add(bytes);
    }

    /** As {@link #localDown(long)}, taking the size of a file already on disk. */
    public void localDown(@Nullable Path file) {
        localDown(sizeOf(file));
    }

    /**
     * This run's downloads in flight. Code that streams a body to disk reports here, so a run's
     * display shows every download whichever step or caller asked for it.
     */
    RunNotices.Run notices() {
        return notices;
    }

    public TransferBoard transfers() {
        return transfers;
    }

    /** A snapshot of the totals so far; consistent enough for reporting, not an atomic read. */
    public Totals totals() {
        return new Totals(remoteUp.sum(), remoteDown.sum(), localUp.sum(), localDown.sum());
    }

    /** A file's size on disk, or {@code 0} when it can't be stat'ed (vanished, unreadable, null). */
    public static long sizeOf(@Nullable Path file) {
        if (file == null) return 0L;
        try {
            return Files.size(file);
        } catch (IOException | RuntimeException e) {
            return 0L; // advisory accounting — never fail a build over a stat
        }
    }
}
