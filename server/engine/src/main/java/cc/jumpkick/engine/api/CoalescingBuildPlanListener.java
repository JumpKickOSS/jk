// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Human-paced wire events: coalesces high-frequency {@link #progress}, {@link #tickUpdate},
 * {@link #label}, and {@link #output} to at most one emit per cadence (default
 * {@value #DEFAULT_CADENCE_MS} ms). Shared with aggregate {@code workspace-progress} on the engine
 * socket and dashboard SSE so CLI and web see the same sample rate.
 *
 * <p>Structural events ({@code planStart/Finish}, {@code stepStart/Finish}, {@code warn},
 * {@code error}) flush pending samples immediately then pass through — humans need those, and
 * agents need them for correctness. {@code progress}/{@code tickUpdate}/{@code label} are sampled
 * (latest wins). {@code output} is <em>queued</em>, not sampled: every line is delivered, batched
 * per cadence tick, because JSONL {@code output} is contractually "printed lines"
 * (docs/machine-output.md) — a test-failure report or native-image log emitted as a synchronous
 * burst must arrive complete. The queue is bounded ({@value #MAX_PENDING_OUTPUT_LINES}
 * lines); a pathological storm drops the oldest lines and announces the gap with a marker line.
 *
 * <p>Two locks, so a producer never waits on the delegate. {@link #state} guards the pending
 * samples and is held only to record or take them; {@link #emitting} serializes calls into the
 * delegate, so a timer flush cannot slip a stale progress sample past a structural event, and is
 * never held while a producer records. A worker thread reporting progress therefore contends only
 * with other recorders, never with a thread that is handing a batch to the wire.
 *
 * <p>Applies to <em>all</em> engine-hosted plans (lock, build, test, plugins), not only resolve:
 * anything that hammers {@code ctx.progress(1)} or dumps stdout benefits. Cadence is for eyeballs;
 * sending faster than a human can read is pure wire cost.
 *
 * <p>Env: {@code JK_WIRE_PROGRESS_MS} — milliseconds between hot flushes ({@code 0} = unbatched
 * passthrough for debugging).
 */
public final class CoalescingBuildPlanListener implements BuildPlanListener, AutoCloseable {

    /** Default human cadence — half a second is plenty for eyes; 1 s feels sluggish. */
    public static final long DEFAULT_CADENCE_MS = 500L;

    private final BuildPlanListener delegate;
    private final long cadenceMs;
    private final Clock clock;

    /** Guards the pending samples below and the timer handle; never held across a delegate call. */
    private final Object state = new Object();

    /** Serializes delegate calls: a flush's batch and a structural event pass through in order. */
    private final ReentrantLock emitting = new ReentrantLock();

    private @Nullable String progressStep;
    private int progressDelta;
    private @Nullable BuildPlanView progressView;

    private @Nullable String tickStep;
    private int tickDelta;
    private @Nullable BuildPlanView tickView;

    private @Nullable String labelStep;
    private @Nullable String labelText;

    /** Pending output lines in arrival order — bounded FIFO, never latest-wins. */
    private final ArrayDeque<PendingOutput> outputQueue = new ArrayDeque<>();

    private long droppedOutputLines;

    /**
     * Heap bound for a flush window's output backlog. Generous enough for any real failure report
     * (hundreds of stacks); a step that exceeds it is a firehose, and the overflow is announced
     * rather than silently truncated.
     */
    static final int MAX_PENDING_OUTPUT_LINES = 4096;

    private record PendingOutput(String step, String line) {}

    /** One flush's worth of samples, taken under {@link #state} and emitted outside it. */
    private record Batch(
            @Nullable String progressStep,
            int progressDelta,
            @Nullable BuildPlanView progressView,
            @Nullable String tickStep,
            int tickDelta,
            @Nullable BuildPlanView tickView,
            @Nullable String labelStep,
            @Nullable String labelText,
            long droppedOutputLines,
            List<PendingOutput> output) {}

    private long lastFlushNanos;
    private @Nullable ScheduledFuture<?> scheduled;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Timer only — every plan in the engine shares this one thread, so it never calls a delegate:
     * a delegate that stalls would silence every other plan's coalesced progress. The scheduled
     * task hands the flush to {@link #FLUSHERS}.
     */
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        // Shared timer across every plan; reads no session.
        Thread t = new Thread(r, "jk-wire-progress");
        t.setDaemon(true);
        return t;
    });

    /**
     * Where a timed flush runs: platform threads, kept while flushes keep coming, so a flush is
     * paced by nothing but the delegate — never by a virtual-thread scheduler whose carriers a
     * build's file walks are holding. Per-listener ordering is still {@link #emitting}'s.
     */
    // Flushers only hand a coalesced event to the delegate; reads no session.
    private static final ExecutorService FLUSHERS = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("jk-wire-flush-", 0).factory());

    public CoalescingBuildPlanListener(BuildPlanListener delegate) {
        this(delegate, cadenceFromEnv());
    }

    public CoalescingBuildPlanListener(BuildPlanListener delegate, long cadenceMs) {
        this(delegate, cadenceMs, Clock.SYSTEM);
    }

    /** With the clock the cadence window is measured on. */
    public CoalescingBuildPlanListener(BuildPlanListener delegate, long cadenceMs, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cadenceMs = Math.max(0L, cadenceMs);
        this.clock = clock;
        this.lastFlushNanos = 0L;
    }

    /** Resolve cadence: {@code JK_WIRE_PROGRESS_MS} or {@link #DEFAULT_CADENCE_MS}. */
    public static long cadenceFromEnv() {
        String raw = System.getenv("JK_WIRE_PROGRESS_MS");
        if (raw == null || raw.isBlank()) return DEFAULT_CADENCE_MS;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_CADENCE_MS;
        }
    }

    @Override
    public void planStart(BuildPlanView view) {
        structural(() -> delegate.planStart(view));
    }

    @Override
    public void stepStart(String step, @Nullable String group, int ticks) {
        structural(() -> delegate.stepStart(step, group, ticks));
    }

    @Override
    public void progress(String step, int delta, BuildPlanView view) {
        if (cadenceMs == 0) {
            delegate.progress(step, delta, view);
            return;
        }
        boolean due;
        synchronized (state) {
            progressStep = step;
            progressDelta += delta;
            progressView = view;
            due = scheduleLocked();
        }
        if (due) flushIfFree();
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView view) {
        if (cadenceMs == 0) {
            delegate.tickUpdate(step, delta, view);
            return;
        }
        boolean due;
        synchronized (state) {
            tickStep = step;
            tickDelta += delta;
            tickView = view;
            due = scheduleLocked();
        }
        if (due) flushIfFree();
    }

    @Override
    public void label(String step, String label) {
        if (cadenceMs == 0) {
            delegate.label(step, label);
            return;
        }
        boolean due;
        synchronized (state) {
            labelStep = step;
            labelText = label;
            due = scheduleLocked();
        }
        if (due) flushIfFree();
    }

    @Override
    public void output(String step, String line) {
        if (cadenceMs == 0) {
            delegate.output(step, line);
            return;
        }
        // Queue, don't sample: cadence bounds frame *rate* (lines batch into one window), but
        // every line must arrive — a test-failure stack emitted as one synchronous burst would
        // otherwise collapse to its final line.
        boolean due;
        synchronized (state) {
            if (outputQueue.size() >= MAX_PENDING_OUTPUT_LINES) {
                outputQueue.pollFirst();
                droppedOutputLines++;
            }
            outputQueue.addLast(new PendingOutput(step, line));
            due = scheduleLocked();
        }
        if (due) flushIfFree();
    }

    /** Journal-only and never batched: the record's tail wants every line, in order, as it is printed. */
    @Override
    public void forkOutput(String step, String line) {
        delegate.forkOutput(step, line);
    }

    @Override
    public void warn(String step, String code, String message) {
        structural(() -> delegate.warn(step, code, message));
    }

    @Override
    public void error(String step, String code, String message) {
        structural(() -> delegate.error(step, code, message));
    }

    @Override
    public void error(String step, String code, String message, String test, String exceptionClass) {
        structural(() -> delegate.error(step, code, message, test, exceptionClass));
    }

    @Override
    public void error(String step, String code, String message, @Nullable TestFailureInfo failure) {
        structural(() -> delegate.error(step, code, message, failure));
    }

    @Override
    public void stepFinish(String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
        structural(() -> delegate.stepFinish(step, group, status, duration, waited));
    }

    @Override
    public void planFinish(BuildPlanResult result) {
        structural(() -> delegate.planFinish(result));
        close();
    }

    /** Flush the pending samples, then pass {@code event} through, with nothing emitted between. */
    private void structural(Runnable event) {
        emitting.lock();
        try {
            flushEmitting();
            event.run();
        } finally {
            emitting.unlock();
        }
    }

    /** Emit any pending hot samples now (also called before structural events). */
    public void flush() {
        if (closed.get()) return;
        emitting.lock();
        try {
            flushEmitting();
        } finally {
            emitting.unlock();
        }
    }

    /**
     * A producer's own flush once the window has elapsed. Takes the emit lock only if it is free:
     * a producer never waits behind a thread that is handing a batch to the wire, and the batch
     * it could not emit goes out on the timer instead.
     */
    private void flushIfFree() {
        if (closed.get()) return;
        if (!emitting.tryLock()) {
            synchronized (state) {
                armTimerLocked(cadenceMs);
            }
            return;
        }
        try {
            flushEmitting();
        } finally {
            emitting.unlock();
        }
    }

    /** Caller holds {@link #emitting}. Take the batch under {@link #state}, then emit it outside. */
    private void flushEmitting() {
        if (closed.get()) return;
        Batch batch;
        synchronized (state) {
            cancelScheduledLocked();
            batch = takeLocked();
            lastFlushNanos = clock.nanos();
        }
        emit(batch);
    }

    /** Caller holds {@link #state}. */
    private Batch takeLocked() {
        Batch batch = new Batch(
                progressStep,
                progressDelta,
                progressView,
                tickStep,
                tickDelta,
                tickView,
                labelStep,
                labelText,
                droppedOutputLines,
                outputQueue.isEmpty() ? List.of() : new ArrayList<>(outputQueue));
        progressStep = null;
        progressDelta = 0;
        progressView = null;
        tickStep = null;
        tickDelta = 0;
        tickView = null;
        labelStep = null;
        labelText = null;
        droppedOutputLines = 0;
        outputQueue.clear();
        return batch;
    }

    /** Caller holds {@link #emitting}. Delegate is wire send only — must not re-enter this coalescer. */
    private void emit(Batch b) {
        if (b.progressStep() != null && b.progressView() != null && b.progressDelta() != 0) {
            delegate.progress(b.progressStep(), b.progressDelta(), b.progressView());
        }
        if (b.tickStep() != null && b.tickView() != null && b.tickDelta() != 0) {
            delegate.tickUpdate(b.tickStep(), b.tickDelta(), b.tickView());
        }
        if (b.labelStep() != null && b.labelText() != null) {
            delegate.label(b.labelStep(), b.labelText());
        }
        if (b.droppedOutputLines() > 0 && !b.output().isEmpty()) {
            long dropped = b.droppedOutputLines();
            delegate.output(
                    b.output().getFirst().step(),
                    "[jk: " + dropped + " earlier output line" + (dropped == 1 ? "" : "s") + " dropped]");
        }
        for (PendingOutput o : b.output()) {
            delegate.output(o.step(), o.line());
        }
    }

    /**
     * Caller holds {@link #state}. Opens a cadence window on the first pending event (no immediate
     * emit) so storms coalesce; after the window elapses, the caller flushes ({@code true}) or the
     * timer does.
     */
    private boolean scheduleLocked() {
        if (closed.get()) return false;
        long now = clock.nanos();
        if (lastFlushNanos == 0L) {
            // Start the human window; first sample lands at cadence (or on structural flush).
            lastFlushNanos = now;
            armTimerLocked(cadenceMs);
            return false;
        }
        long elapsedMs = (now - lastFlushNanos) / 1_000_000L;
        if (elapsedMs >= cadenceMs) return true;
        armTimerLocked(cadenceMs - elapsedMs);
        return false;
    }

    /** Caller holds {@link #state}. One timer at a time; an armed one is left alone. */
    private void armTimerLocked(long delayMs) {
        if (scheduled != null && !scheduled.isDone()) return;
        scheduled = SCHEDULER.schedule(this::dispatchFlush, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void cancelScheduledLocked() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    /** Timer callback: never flushes inline — see {@link #SCHEDULER}. */
    private void dispatchFlush() {
        if (closed.get()) return;
        try {
            FLUSHERS.execute(this::flushSafe);
        } catch (RejectedExecutionException shuttingDown) {
            // engine going down — dropping a coalesced progress frame is fine
        }
    }

    private void flushSafe() {
        try {
            flush();
        } catch (RuntimeException e) {
            // never let scheduler die on a bad client write
            Log.debug("flushSafe: never let scheduler die on a bad client write", e);
        }
    }

    @Override
    public void close() {
        // Flush BEFORE marking closed — flush no-ops once closed.
        flush();
        if (!closed.compareAndSet(false, true)) return;
        synchronized (state) {
            cancelScheduledLocked();
        }
    }
}
