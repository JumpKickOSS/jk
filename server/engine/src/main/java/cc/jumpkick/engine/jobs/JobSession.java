// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.wire.runtime.RemainingWork;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** The attached client's socket, for events the build's listeners do not carry (a hold); null when detached. */
    private volatile @Nullable BufferedWriter writer;

    /** Wall clock of the newest progress/remaining signal; 0 until the job emits one. */
    private volatile long lastEventAt;

    private final ConcurrentHashMap<String, Long> weights = new ConcurrentHashMap<>();

    /**
     * Modules that finished, as their completion named them. The work model can arrive after the
     * first modules finish (the build does not wait for its ETA), so a model set late drains
     * these before anyone reads its residual.
     */
    private final Set<String> completed = ConcurrentHashMap.newKeySet();

    /** A step that started or finished, as its event named it, at {@code atMillis} (wall clock). */
    public record StepMark(String dir, String step, boolean started, long atMillis) {}

    /** Marks kept at most: several times the steps of jk's own forced build. */
    private static final int MAX_STEP_MARKS = 20_000;

    /**
     * Steps that started or finished, kept like {@link #completed}: the build's first steps run
     * while its work model is still being priced, and a model set late replays them, so a long step
     * that began before it is counted down from its start instead of priced as never started (a
     * 15 s guard read "16 s left" for its whole run).
     */
    private final Queue<StepMark> stepMarks = new ConcurrentLinkedQueue<>();

    private final AtomicInteger stepMarkCount = new AtomicInteger();

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
        completed.clear();
        stepMarks.clear();
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

    /**
     * Set the residual schedule, then drain every module already reported complete. Paired with
     * {@link #moduleCompleted}, which records before it reads: whichever runs second sees the
     * other's write, so no completion is lost to the race and a repeat is harmless.
     */
    public void remaining(@Nullable RemainingWork remaining) {
        this.remaining = remaining;
        this.lastEventAt = System.currentTimeMillis();
        if (remaining == null) return;
        synchronized (remaining) {
            for (StepMark m : stepMarks) apply(remaining, m);
            for (String dir : completed) remaining.moduleComplete(Path.of(dir));
        }
    }

    /** Record that the module in {@code dir} finished. */
    public void moduleCompleted(String dir) {
        if (!retired.get()) completed.add(dir);
    }

    /**
     * Record a step's start or finish, then return the model to apply it to now; null when none is
     * set yet ({@link #remaining(RemainingWork)} replays it). Recorded before it reads, as {@link
     * #moduleCompleted} is, so no mark is lost to the race; applying one twice is harmless.
     */
    public @Nullable RemainingWork stepMarked(StepMark mark) {
        if (retired.get()) return null;
        if (stepMarkCount.incrementAndGet() <= MAX_STEP_MARKS) stepMarks.add(mark);
        return remaining;
    }

    /** Apply {@code m} to {@code rw}; the caller holds {@code rw}'s lock. */
    public static void apply(RemainingWork rw, StepMark m) {
        Path p = Path.of(m.dir());
        if (!rw.steps(p)) return;
        if (m.started()) rw.stepStart(p, m.step(), m.atMillis());
        else rw.stepFinish(p, m.step());
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
        this.lastEventAt = System.currentTimeMillis();
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

    public long lastEventAt() {
        return lastEventAt;
    }

    public void accumulator(@Nullable BuildAccumulator acc) {
        this.accumulator = acc;
    }

    public @Nullable BuildAccumulator accumulator() {
        return accumulator;
    }

    public void writer(@Nullable BufferedWriter w) {
        this.writer = w;
    }

    public @Nullable BufferedWriter writer() {
        return writer;
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
