// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.runtime.RemainingWork;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import cc.jumpkick.runtime.progress.ProgressBarMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * One request-scoped row. Teardown is {@link #retire()}: late {@code computeIfAbsent} must not
 * resurrect maps.
 */
public final class JobSession {
    private final long id;
    private final AtomicBoolean retired = new AtomicBoolean();

    private volatile @Nullable ProgressBarMode mode;
    private volatile @Nullable WorkspaceProgressTracker tracker;
    private volatile @Nullable RemainingWork remaining;
    private volatile @Nullable String progressRoot;
    private volatile @Nullable Double lastProgress;
    private volatile @Nullable Long lastProgressDen;
    private volatile @Nullable BuildAccumulator accumulator;
    private final ConcurrentHashMap<String, Long> weights = new ConcurrentHashMap<>();
    private final Object emitLock = new Object();
    private volatile long @Nullable [] emitState;

    public JobSession(long id) {
        this.id = id;
    }

    public long id() {
        return id;
    }

    public boolean retired() {
        return retired.get();
    }

    /** Mark retired. Further {@link #tracker()} calls return a detached instance. */
    public void retire() {
        retired.set(true);
        tracker = null;
        remaining = null;
        progressRoot = null;
        lastProgress = null;
        lastProgressDen = null;
        weights.clear();
        emitState = null;
        // accumulator is removed by the journal writer, not here
    }

    public void mode(ProgressBarMode mode) {
        this.mode = mode;
    }

    public @Nullable ProgressBarMode mode() {
        return mode;
    }

    /**
     * Workspace tracker. After {@link #retire()}, a fresh detached tracker (never stored) so
     * callers skip null checks and the update goes nowhere.
     */
    public @Nullable WorkspaceProgressTracker existingTracker() {
        return tracker;
    }

    public WorkspaceProgressTracker tracker() {
        if (retired.get()) return new WorkspaceProgressTracker(mode);
        WorkspaceProgressTracker t = tracker;
        if (t != null) return t;
        synchronized (this) {
            if (retired.get()) return new WorkspaceProgressTracker(mode);
            t = tracker;
            if (t == null) {
                t = new WorkspaceProgressTracker(mode);
                tracker = t;
            }
            return t;
        }
    }

    public void remaining(@Nullable RemainingWork remaining) {
        this.remaining = remaining;
    }

    public @Nullable RemainingWork remaining() {
        return remaining;
    }

    public void progressRoot(@Nullable String root) {
        this.progressRoot = root;
    }

    public @Nullable String progressRoot() {
        return progressRoot;
    }

    public void lastProgress(@Nullable Double p) {
        this.lastProgress = p;
    }

    public @Nullable Double lastProgress() {
        return lastProgress;
    }

    public void lastProgressDen(@Nullable Long den) {
        this.lastProgressDen = den;
    }

    public @Nullable Long lastProgressDen() {
        return lastProgressDen;
    }

    public void accumulator(@Nullable BuildAccumulator acc) {
        this.accumulator = acc;
    }

    public @Nullable BuildAccumulator accumulator() {
        return accumulator;
    }

    public ConcurrentHashMap<String, Long> weights() {
        return weights;
    }

    public Object emitLock() {
        return emitLock;
    }

    public long @Nullable [] emitState() {
        return emitState;
    }

    public void emitState(long @Nullable [] state) {
        this.emitState = state;
    }
}
