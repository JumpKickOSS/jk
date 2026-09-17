// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.DownloadSlots;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Download every resolved module on the io pool, {@link DownloadSlots#width()} of them at once, and
 * hand back lock rows in declaration order, while progress ticks in completion order. First failure
 * wins: tasks still waiting skip their download instead of hammering the host for a lock that is
 * already dead, every sibling is settled before the failure propagates, abort noise loses to the
 * root cause, and the cause keeps its exception identity on the way out.
 */
final class ArtifactMaterializer {

    /** Builds one lock row for a resolved module; {@code abort} turns true once a sibling has failed. */
    interface RowAssembler {
        Lockfile.Artifact toArtifact(Resolution.ResolvedModule mod, EnumSet<Scope> tags, BooleanSupplier abort)
                throws IOException, InterruptedException;
    }

    private final RowAssembler rows;
    private final LockProgress progress;
    private final Executor executor;

    ArtifactMaterializer(RowAssembler rows, LockProgress progress) {
        this(rows, progress, JkThreads.io());
    }

    /** Test seam: the pool the row tasks run on. */
    ArtifactMaterializer(RowAssembler rows, LockProgress progress, Executor executor) {
        this.rows = rows;
        this.progress = progress;
        this.executor = executor;
    }

    /**
     * Rows for {@code ordered}, in that order. Progress ticks on completion order via a queue drained
     * on this thread so the wedge and UI stay single-threaded.
     *
     * <p>Rows are submitted through a window of {@link DownloadSlots#width()}: a row task exists only
     * once an earlier one has finished, so the population of tasks stays near the width instead of
     * one parked thread per row of the lock. Every task then takes a {@link DownloadSlots} slot
     * before it assembles its row and holds it for the row's legs — the per-repository probes, the
     * download, its sidecar reads — so a lock of several hundred rows keeps a bounded number of
     * connections, copy buffers and parked legs in the engine at once, even beside another job's
     * downloads. Warm re-locks serve immutable GAVs from the local mirror, so a slot is cheap for
     * them; the per-host cap around the network leg alone ({@code HostRateLimiter}, six per host) is
     * what keeps a cold lock polite, and wrapping the whole row in it once turned a one-second store
     * walk into minutes.
     */
    List<Lockfile.Artifact> materialize(
            List<Map.Entry<String, Resolution.ResolvedModule>> ordered, Map<String, EnumSet<Scope>> tagsByKey)
            throws IOException, InterruptedException {
        int n = ordered.size();
        Lockfile.Artifact[] arts = new Lockfile.Artifact[n];
        BlockingQueue<MaterializeDone> doneQ = new LinkedBlockingQueue<>();
        // First failure wins: tasks still waiting on a permit/queue skip their download instead
        // of hammering the host for a lock that is already dead.
        AtomicBoolean failed = new AtomicBoolean();
        List<CompletableFuture<?>> inFlight = new ArrayList<>(n);
        // The submission window: a permit per task alive, given back before its done event is
        // queued, so the drain loop below always finds room for another row when it wakes.
        Semaphore window = new Semaphore(DownloadSlots.width());
        int submitted = 0;
        int received = 0;
        while (received < n) {
            while (submitted < n && !failed.get() && window.tryAcquire()) {
                inFlight.add(submit(ordered.get(submitted), submitted, tagsByKey, failed, window, doneQ));
                submitted++;
            }
            MaterializeDone d;
            try {
                d = doneQ.take();
            } catch (InterruptedException ie) {
                failed.set(true);
                settle(inFlight);
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (d.error != null) {
                // Siblings are still on the io pool writing into the CAS. Let them wind down
                // before the failure propagates — `failed` makes unstarted tasks return at once
                // and in-flight ones abort at their next leg boundary, so this is
                // bounded by whatever is mid-download. Escaping here leaves threads mutating a
                // store the caller believes it has finished with.
                failed.set(true);
                settle(inFlight);
                Throwable c = unwrapMaterialize(d.error);
                // Abort noise can beat the root cause into the queue (a sibling parked at a leg
                // boundary observes `failed` between the failing task's set and offer). Every
                // task has settled by now, so the real failure is in the queue — prefer it.
                if (c instanceof MavenRepo.FetchAbortedException) {
                    MaterializeDone later;
                    while ((later = doneQ.poll()) != null) {
                        if (later.error == null) continue;
                        Throwable candidate = unwrapMaterialize(later.error);
                        if (!(candidate instanceof MavenRepo.FetchAbortedException)) {
                            c = candidate;
                            break;
                        }
                    }
                }
                if (c instanceof IOException io) throw io;
                if (c instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (c instanceof RuntimeException re) throw re;
                if (c instanceof Error err) throw err;
                throw new IOException(c);
            }
            // not a failure event, so the row is complete
            arts[d.index] = Objects.requireNonNull(d.artifact);
            progress.materialized(Objects.requireNonNull(d.module), Objects.requireNonNull(d.version));
            received++;
        }
        return List.of(arts);
    }

    /** One row's task: a slot, the row, then the done event; the window permit goes back first. */
    private CompletableFuture<?> submit(
            Map.Entry<String, Resolution.ResolvedModule> e,
            int idx,
            Map<String, EnumSet<Scope>> tagsByKey,
            AtomicBoolean failed,
            Semaphore window,
            BlockingQueue<MaterializeDone> doneQ) {
        EnumSet<Scope> tags = Objects.requireNonNull(tagsByKey.get(e.getKey()), "every module has its scope tags");
        return CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                DownloadSlots.acquire();
                                try {
                                    if (failed.get()) {
                                        throw new CompletionException(
                                                new MavenRepo.FetchAbortedException("lock already failed — skipped"));
                                    }
                                    return rows.toArtifact(e.getValue(), tags, failed::get);
                                } finally {
                                    DownloadSlots.release();
                                }
                            } catch (IOException | InterruptedException ex) {
                                throw new CompletionException(ex);
                            }
                        },
                        executor)
                .whenComplete((art, ex) -> {
                    window.release();
                    if (ex != null) {
                        failed.set(true);
                        doneQ.offer(MaterializeDone.fail(ex));
                    } else {
                        var mod = e.getValue();
                        doneQ.offer(
                                MaterializeDone.ok(idx, art, LockProgress.displayModule(mod.module()), mod.version()));
                    }
                });
    }

    /** Unwrap the layered CompletionExceptions around a materialize failure. */
    private static Throwable unwrapMaterialize(Throwable error) {
        Throwable c = error.getCause() != null ? error.getCause() : error;
        if (c instanceof CompletionException ce && ce.getCause() != null) c = ce.getCause();
        return c;
    }

    /**
     * Wait for every materialize task to finish, discarding outcomes. Called when the lock is
     * already lost, so the only thing that matters is that no task is still touching the CAS when
     * this returns. Bounded: unstarted tasks skip at their gate and in-flight ones abort at
     * their next leg boundary — only legs already in progress run to completion.
     */
    private static void settle(List<CompletableFuture<?>> inFlight) {
        for (CompletableFuture<?> f : inFlight) {
            try {
                f.join();
            } catch (CompletionException | CancellationException ignored) {
                // the failure that got us here, or a sibling's — already reported
            }
        }
    }

    /** Completion event for parallel jar materialize (progress on complete, rows ordered). */
    private record MaterializeDone(
            int index,
            Lockfile.@Nullable Artifact artifact,
            @Nullable String module,
            @Nullable String version,
            @Nullable Throwable error) {
        static MaterializeDone ok(int index, Lockfile.Artifact art, String module, String version) {
            return new MaterializeDone(index, art, module, version, null);
        }

        static MaterializeDone fail(Throwable error) {
            return new MaterializeDone(-1, null, null, null, error);
        }
    }
}
