// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

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
 * <p><strong>Ambient scope.</strong> The engine opens one ledger per request on the runner thread
 * ({@link #open}/{@link #close}); {@link cc.jumpkick.config.Session#defaults()} adopts it, so every
 * session derived inside that request — including work handed to the shared {@code JkThreads} pools,
 * which re-bind the session — accumulates into the same ledger. Outside a run (CLI, tests) a session
 * gets its own detached ledger and the numbers are simply discarded. Deep code meters through the
 * session it already has: {@code SessionContext.current().io().remoteDown(size)}.
 *
 * <p>Thread-safe: four {@link LongAdder}s under concurrent module builds and parallel fetches.
 * Best-effort by design — a failed {@code stat} contributes 0 rather than failing the build.
 *
 * <p>Not metered: git-source clones (the {@code git} subprocess owns that transfer) and the engine's
 * own housekeeping (journal, metrics, log GC).
 */
public final class IoLedger {

    /**
     * Inheritable so a request's runner thread — and any thread it forks — sees the run's ledger.
     *
     * <p>Shared {@code JkThreads} pools must not rely on that inheritance. {@code JkThreads.cpu()}
     * is a lazily grown {@code ForkJoinPool}: workers are born inside whichever request first needed
     * them and would keep that request's ledger for the engine's life. {@link
     * cc.jumpkick.config.RequestScope} keys on the ledger, so a stale binding would serve one
     * request's memoized scans to later requests.
     *
     * <p>{@code SessionContext}'s {@code ContextPropagator} captures the ambient ledger on the
     * submitting thread and binds it around the task, clearing it when the submitter had none. A
     * value read on a pool thread comes from that propagation, never from inheritance.
     */
    private static final InheritableThreadLocal<IoLedger> AMBIENT = new InheritableThreadLocal<>();

    private final LongAdder remoteUp = new LongAdder();
    private final LongAdder remoteDown = new LongAdder();
    private final LongAdder localUp = new LongAdder();
    private final LongAdder localDown = new LongAdder();

    /** One run's totals, in bytes. */
    public record Totals(long remoteUp, long remoteDown, long localUp, long localDown) {

        public static final Totals ZERO = new Totals(0, 0, 0, 0);

        /** True when the run moved no bytes at all — nothing worth showing. */
        public boolean isEmpty() {
            return remoteUp == 0 && remoteDown == 0 && localUp == 0 && localDown == 0;
        }
    }

    /**
     * Bind {@code ledger} as the ambient run ledger for this thread (and threads it forks); a
     * {@code null} unbinds, which is how a pool task restores a worker that had no request.
     */
    public static void open(@Nullable IoLedger ledger) {
        if (ledger == null) AMBIENT.remove();
        else AMBIENT.set(ledger);
    }

    /** Drop this thread's ambient binding. */
    public static void close() {
        AMBIENT.remove();
    }

    /**
     * The ambient run ledger for this thread, or a fresh detached one when no run is open — what
     * {@code Session.defaults()} uses so a session built inside a request joins that request's
     * accounting and one built anywhere else meters harmlessly into the void.
     */
    /**
     * The ledger this thread's request opened, or {@code null} off a request.
     *
     * <p>Distinct from {@link #currentOrNew()}, which mints one rather than answer "none" — useful
     * for accounting, useless as a request discriminator. {@link cc.jumpkick.config.RequestScope} needs the honest
     * answer: it caches facts for the length of a request, so it must be able to tell that there is
     * no request rather than cache into a ledger nobody opened.
     */
    public static @Nullable IoLedger ambient() {
        return AMBIENT.get();
    }

    public static IoLedger currentOrNew() {
        IoLedger ambient = AMBIENT.get();
        return ambient != null ? ambient : new IoLedger();
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
    public void localDown(Path file) {
        localDown(sizeOf(file));
    }

    /** A snapshot of the totals so far; consistent enough for reporting, not an atomic read. */
    public Totals totals() {
        return new Totals(remoteUp.sum(), remoteDown.sum(), localUp.sum(), localDown.sum());
    }

    /** A file's size on disk, or {@code 0} when it can't be stat'ed (vanished, unreadable, null). */
    public static long sizeOf(Path file) {
        if (file == null) return 0L;
        try {
            return Files.size(file);
        } catch (IOException | RuntimeException e) {
            return 0L; // advisory accounting — never fail a build over a stat
        }
    }
}
