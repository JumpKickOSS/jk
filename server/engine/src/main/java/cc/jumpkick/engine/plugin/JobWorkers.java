// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.Request;
import cc.jumpkick.host.Log;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.StepScope;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Starts worker {@link Process}es (plugin, test and compiler JVMs) under the engine's memory leases
 * and containment, and registers each with the {@link WorkerScope} of the request bound where it
 * starts, so that request's cancel or wall deadline can stop it. A request that carries no scope (a
 * synchronous read, a one-shot probe) registers nothing.
 */
public final class JobWorkers {

    /**
     * How long a force-kill waits for the process to actually be gone. Paid only on the kill path,
     * which is already the exceptional one; a killed JVM is normally gone in well under a second.
     */
    private static final long KILL_SETTLE_MS = 5_000;

    private JobWorkers() {}

    /**
     * Request id this thread's forks belong to, or {@code null} when no job scope is bound
     * (one-shot tests, probes, a synchronous read).
     */
    public static @Nullable Long currentRequestId() {
        Request r = Request.current();
        return r == null || r.id() == 0 ? null : r.id();
    }

    /**
     * True when the scope bound on this thread forks for {@code requestId} and has ended: a lease
     * this thread is still waiting for belongs to a request that is over. Read on the waiting thread.
     */
    static boolean ended(long requestId) {
        WorkerScope scope = WorkerScope.current();
        return scope != null && scope.id() == requestId && scope.ended();
    }

    /** Test seam: force the shared CPU pool's threads to exist under the caller's scope. */
    static void warmPoolForTest() throws Exception {
        int n = Math.max(2, Runtime.getRuntime().availableProcessors());
        List<Future<?>> pending = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            pending.add(JkThreads.cpu().submit(() -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        release.countDown();
        for (var f : pending) f.get(10, TimeUnit.SECONDS);
    }

    /** Track {@code process} for the current request's scope, if any; an ended scope kills it. */
    public static void register(Process process) {
        if (process == null) return;
        WorkerScope scope = WorkerScope.current();
        if (scope != null) scope.register(process);
    }

    /**
     * {@link ProcessBuilder#start()}, then {@link WorkerContainment#contain} and {@link #register}.
     * Register is a no-op when no request scope is open.
     *
     * <p>On Linux the child is moved into the workers cgroup just after it starts, so it can run
     * outside that group for the moment between start and the move.
     */
    public static Process start(ProcessBuilder pb) throws IOException {
        return start(pb, WorkerLeases.engine());
    }

    /** As {@link #start(ProcessBuilder)}, taking the lease from {@code leases}. */
    public static Process start(ProcessBuilder pb, WorkerLeases.Ledger leases) throws IOException {
        return start(pb, leases, LearnedHeaps.engine());
    }

    /** As {@link #start(ProcessBuilder, WorkerLeases.Ledger)}, recording peaks on {@code heaps}. */
    public static Process start(ProcessBuilder pb, WorkerLeases.Ledger leases, LearnedHeaps heaps) throws IOException {
        return launch(pb, true, null, leases, JvmOptions.HeapChoice.inspect(pb.command()), heaps);
    }

    /**
     * As {@link #start(ProcessBuilder)} with a heap choice measured before an argfile shorten hid
     * the flags.
     */
    public static Process start(ProcessBuilder pb, JvmOptions.HeapChoice choice) throws IOException {
        return start(pb, choice, LearnedHeaps.engine());
    }

    /** As {@link #start(ProcessBuilder, JvmOptions.HeapChoice)}, recording peaks on {@code heaps}. */
    public static Process start(ProcessBuilder pb, JvmOptions.HeapChoice choice, LearnedHeaps heaps)
            throws IOException {
        return launch(pb, true, null, WorkerLeases.engine(), choice, heaps);
    }

    /**
     * As {@link #start} but not registered for request cancel: the AOT trainer outlives the request
     * that spawned it. It is still contained.
     */
    public static Process startDetached(ProcessBuilder pb) throws IOException {
        return launch(
                pb,
                false,
                null,
                WorkerLeases.engine(),
                JvmOptions.HeapChoice.inspect(pb.command()),
                LearnedHeaps.engine());
    }

    /**
     * As {@link #startDetached} for a helper that stays up between requests (the build-script
     * host). Its lease takes no CPU slot, and {@code resident} is asked to exit when the head of the
     * lease queue needs the memory.
     */
    public static Process startResident(ProcessBuilder pb, WorkerLeases.Resident resident) throws IOException {
        return launch(
                pb,
                false,
                resident,
                WorkerLeases.engine(),
                JvmOptions.HeapChoice.inspect(pb.command()),
                LearnedHeaps.engine());
    }

    /**
     * As {@link #start} for a worker whose memory is measured while it runs rather than read off its
     * command: it is leased {@code bytes} to begin with, its command is never resized, and {@code
     * onGrant} receives the lease so the caller can move it to what the worker reports using. Its
     * cgroup cap is {@code capBytes}, chosen with its heap ceiling, or the shared group when that is
     * not positive. The lease takes a JVM slot, is charged to {@code leaseRequest} (null: the engine,
     * for a worker that outlives requests) under the name {@code what}, and is released when the
     * process is gone. {@code track} registers the process with the current request, so that
     * request's cancel stops it.
     */
    public static Process startMeasured(
            ProcessBuilder pb,
            long bytes,
            long capBytes,
            @Nullable Long leaseRequest,
            String what,
            boolean track,
            Consumer<WorkerLeases.Grant> onGrant)
            throws IOException {
        WorkerLeases.Ledger leases = WorkerLeases.engine();
        WorkerScope scope = WorkerScope.current();
        if (leaseRequest != null && scope != null) scope.awaitReleased();
        WorkerLeases.Grant grant;
        try {
            grant = leases.acquireBytes(bytes, true, leaseRequest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("cancelled while waiting for memory");
        } catch (WorkerLeases.LeaseRefused e) {
            Log.warn("jk engine: " + e.getMessage());
            throw new IOException(e.getMessage(), e);
        }
        Process process;
        try {
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            grant.close();
            throw e;
        }
        ExecutionSpeed.child(process);
        process.onExit().whenComplete((code, error) -> {
            grant.close();
            WorkerContainment.sweep();
        });
        WorkerContainment.contain(process, capBytes, what);
        grant.watch(process.pid(), what, leaseRequest == null ? null : StepScope.current());
        onGrant.accept(grant);
        if (track) register(process);
        return process;
    }

    private static Process launch(
            ProcessBuilder pb,
            boolean track,
            WorkerLeases.@Nullable Resident resident,
            WorkerLeases.Ledger leases,
            JvmOptions.HeapChoice choice,
            LearnedHeaps heaps)
            throws IOException {
        Long request = track ? currentRequestId() : null;
        WorkerScope scope = track ? WorkerScope.current() : null;
        if (scope != null) scope.awaitReleased();
        WorkerLeases.Grant grant;
        try {
            grant = resident != null
                    ? leases.acquireResident(pb.command(), choice, resident)
                    : leases.acquire(pb.command(), request, choice);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("cancelled while waiting for memory");
        } catch (WorkerLeases.LeaseRefused e) {
            Log.warn("jk engine: " + e.getMessage());
            throw new IOException(e.getMessage(), e);
        }
        List<String> sized = launchCommand(pb.command(), grant);
        if (sized != pb.command() && grant.clamped()) {
            Log.info("jk engine: lowered -Xmx to " + WorkerLeases.format(grant.xmxBytes()) + " so the worker fits the "
                    + WorkerLeases.format(leases.capacityBytes()) + " budget");
        } else if (grant.userPinned() && grant.overBudget()) {
            String pin = choice.label().isEmpty() ? "heap" : choice.label();
            Log.info("jk engine: leaving pinned " + pin + " unchanged; leasing the whole "
                    + WorkerLeases.format(leases.capacityBytes()) + " budget");
        }
        WorkerGc.Watch gc = WorkerGc.watch(sized, choice, heaps);
        List<String> command = gc.command();
        if (command != pb.command()) pb.command(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            grant.close();
            gc.finish();
            throw e;
        }
        ExecutionSpeed.child(process);
        // Released when the process is gone, including a crash or a kill. onExit runs if it has
        // already exited. The GC log is read after the process has flushed it.
        process.onExit().whenComplete((code, error) -> {
            grant.close();
            gc.finish();
            WorkerContainment.sweep();
        });
        String what = resident != null ? resident.name() : WorkerRss.describe(command);
        if (what.equals(WorkerRss.TEST_JVM)) TestJvmFlags.note(command);
        WorkerContainment.contain(process, WorkerRss.workerCapBytes(grant.bytes(), leases.capacityBytes()), what);
        grant.watch(process.pid(), what, StepScope.current());
        gc.observe(process);
        if (track) register(process);
        return process;
    }

    /**
     * The command {@code launch} execs. A planned heap that does not fit is lowered, and a
     * native-image builder that names no heap is given the one it was leased; a user pin is
     * returned unchanged.
     */
    static List<String> launchCommand(List<String> command, WorkerLeases.Grant grant) {
        if (grant.userPinned() || !grant.jvm()) return command;
        boolean unsizedBuilder = HeapFlags.nativeImageCommand(command) && HeapFlags.parseXmx(command) <= 0;
        if (grant.clamped() || unsizedBuilder) return HeapFlags.rewriteHeap(command, grant.xmxBytes());
        return command;
    }

    /** Stop tracking {@code process} (after it exits normally) in the current request's scope. */
    public static void unregister(Process process) {
        if (process == null) return;
        WorkerScope scope = WorkerScope.current();
        if (scope != null) scope.unregister(process);
    }

    /**
     * Force-kill {@code process} and every live descendant. Orphaned children can keep the parent's
     * stdout pipe open after the root PID dies, so a lone {@link Process#destroyForcibly()} leaves
     * readers blocked until those children exit.
     */
    public static void destroyTree(Process process) {
        if (process == null) return;
        forceKillAndAwait(process);
    }

    /**
     * Force-kill one tree and do not return until it is gone (or the budget runs out).
     *
     * <p>Every force-kill outside the batched cancel path goes through here. Returning while the
     * corpse still holds its handles is what makes a kill look successful and a later delete fail.
     */
    static void forceKillAndAwait(Process process) {
        // Snapshot first: once the root is gone its descendants() is empty, and the grandchildren
        // still to outlast have been reparented away from us.
        List<ProcessHandle> tree = descendantsOf(process);
        signalTree(process, true);
        awaitGone(List.of(process), Map.of(process, tree));
    }

    static List<ProcessHandle> descendantsOf(Process p) {
        try {
            return p.descendants().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Wait for force-killed processes to be gone, not merely told to go.
     *
     * <p>{@code destroyForcibly} starts the kill and returns — {@code TerminateProcess} on Windows,
     * {@code SIGKILL} on POSIX. Until termination completes the process still holds every handle it
     * had, its working directory among them, and Windows refuses to delete a directory any live
     * handle covers. So a caller that force-kills a worker and then removes the tree that worker was
     * building finds it undeletable for as long as the corpse takes to finish. How long that is
     * depends on machine load, which is why it presents as an intermittent failure somewhere else
     * rather than as a kill that did not work.
     *
     * <p>Bounded, and bounded is not the same as hanging: a process that will not die inside the
     * budget is left to the OS and the caller carries on.
     */
    static void awaitGone(Collection<Process> processes, Map<Process, List<ProcessHandle>> trees) {
        List<CompletableFuture<?>> exits = new ArrayList<>();
        for (Process p : processes) {
            try {
                exits.add(p.onExit());
                for (ProcessHandle h : trees.getOrDefault(p, List.of())) exits.add(h.onExit());
            } catch (RuntimeException e) {
                // A handle we may not observe: nothing to wait on, and not worth failing a kill for.
                Log.debug("awaitGone: A handle we may not observe", e);
            }
        }
        if (exits.isEmpty()) return;
        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new)).get(KILL_SETTLE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // Best-effort: the budget is a bound on our patience, not a promise about the OS.
            Log.debug("awaitGone: Best-effort", e);
        }
    }

    /** SIGTERM (or SIGKILL when {@code force}) the process and every live descendant. */
    static void signalTree(Process p, boolean force) {
        try {
            if (force) {
                p.descendants().forEach(h -> {
                    try {
                        h.destroyForcibly();
                    } catch (RuntimeException e) {
                        // best-effort
                        Log.debug("signalTree: best-effort", e);
                    }
                });
                if (p.isAlive()) p.destroyForcibly();
            } else {
                if (p.isAlive()) p.destroy();
                p.descendants().forEach(h -> {
                    try {
                        h.destroy();
                    } catch (RuntimeException e) {
                        // best-effort
                        Log.debug("signalTree: best-effort", e);
                    }
                });
            }
        } catch (RuntimeException e) {
            // best-effort
            Log.debug("signalTree: best-effort", e);
        }
    }
}
