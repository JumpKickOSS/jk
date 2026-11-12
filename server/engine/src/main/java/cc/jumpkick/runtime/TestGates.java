// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.runtime.base.LiveUnits;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The serial test gate of each checkout: without {@code --parallel-tests} one suite of a checkout
 * runs at a time, because its suites share ports, lock files and fixtures. Two checkouts share none
 * of that, so each has its own gate, keyed by its workspace root as {@link
 * BuildJobFingerprint#canonicalDir} spells it (a symlinked path and its target are one checkout,
 * two worktrees are two). A caller with no workspace root shares one fallback gate. A gate exists
 * only while a suite holds it or waits for it.
 */
final class TestGates {

    private static final long POLL_MS = 200;

    private static final ConcurrentHashMap<String, Gate> GATES = new ConcurrentHashMap<>();

    /** One checkout's gate and how many suites hold it or wait for it; both guarded by the map. */
    private static final class Gate {
        final Semaphore permit = new Semaphore(1);
        int users;
    }

    /** A held gate; closing releases it. */
    interface Hold extends AutoCloseable {
        @Override
        void close();
    }

    private TestGates() {}

    /**
     * Take the gate of the checkout at {@code workspaceRoot}, giving up with a {@link
     * CancellationException} once {@code cancelled} answers true. A suite parked here is not counted
     * as a running unit: it shares the machine with nobody yet.
     */
    static Hold acquire(@Nullable Path workspaceRoot, BooleanSupplier cancelled) {
        String key = workspaceRoot == null ? "" : BuildJobFingerprint.canonicalDir(workspaceRoot.toString());
        Gate gate = GATES.compute(key, (k, g) -> {
            Gate held = g == null ? new Gate() : g;
            held.users++;
            return held;
        });
        try {
            await(gate.permit, cancelled);
        } catch (RuntimeException e) {
            leave(key, gate);
            throw e;
        }
        return () -> {
            gate.permit.release();
            leave(key, gate);
        };
    }

    /** Gates held or waited for right now. */
    static int live() {
        return GATES.size();
    }

    private static void leave(String key, Gate gate) {
        GATES.computeIfPresent(key, (k, g) -> g == gate && --g.users == 0 ? null : g);
    }

    /**
     * Take {@code permit}, giving up with a {@link CancellationException} once {@code cancelled}
     * answers true: a suite parked behind another suite stays cancellable.
     */
    static void await(Semaphore permit, BooleanSupplier cancelled) {
        try (LiveUnits.Lease parked = LiveUnits.stepOut()) {
            while (!permit.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new CancellationException("test run cancelled");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("test run interrupted");
        }
    }
}
