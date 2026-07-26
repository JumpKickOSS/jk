// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Human-paced wire progress: coalesces high-frequency {@link #progress}, {@link #tickUpdate}, and
 * {@link #label} events to at most one emit per cadence (default {@value #DEFAULT_CADENCE_MS} ms).
 *
 * <p>Structural events ({@code pipelineStart/Finish}, {@code stepStart/Finish}, {@code output},
 * {@code warn}, {@code error}) flush pending progress immediately then pass through — humans need
 * those, and agents need them for correctness.
 *
 * <p>Applies to <em>all</em> engine-hosted pipelines (lock, build, test, plugins), not only resolve:
 * anything that hammers {@code ctx.progress(1)} benefits. Cadence is for eyeballs; sending faster
 * than a human can read is pure wire cost.
 *
 * <p>Env: {@code JK_WIRE_PROGRESS_MS} — milliseconds between progress/label flushes ({@code 0} =
 * unbatched passthrough for debugging).
 */
public final class CoalescingPipelineListener implements PipelineListener, AutoCloseable {

    /** Default human cadence — half a second is plenty for eyes; 1 s feels sluggish. */
    public static final long DEFAULT_CADENCE_MS = 500L;

    private final PipelineListener delegate;
    private final long cadenceMs;
    private final Object lock = new Object();

    private String progressStep;
    private int progressDelta;
    private PipelineView progressView;

    private String tickStep;
    private int tickDelta;
    private PipelineView tickView;

    private String labelStep;
    private String labelText;

    private long lastFlushNanos;
    private ScheduledFuture<?> scheduled;
    private final AtomicBoolean closed = new AtomicBoolean();

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jk-wire-progress");
        t.setDaemon(true);
        return t;
    });

    public CoalescingPipelineListener(PipelineListener delegate) {
        this(delegate, cadenceFromEnv());
    }

    public CoalescingPipelineListener(PipelineListener delegate, long cadenceMs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cadenceMs = Math.max(0L, cadenceMs);
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
    public void pipelineStart(PipelineView view) {
        flush();
        delegate.pipelineStart(view);
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        flush();
        delegate.stepStart(step, phase, ticks);
    }

    @Override
    public void progress(String step, int delta, PipelineView view) {
        if (cadenceMs == 0) {
            delegate.progress(step, delta, view);
            return;
        }
        synchronized (lock) {
            progressStep = step;
            progressDelta += delta;
            progressView = view;
            scheduleLocked();
        }
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView view) {
        if (cadenceMs == 0) {
            delegate.tickUpdate(step, delta, view);
            return;
        }
        synchronized (lock) {
            tickStep = step;
            tickDelta += delta;
            tickView = view;
            scheduleLocked();
        }
    }

    @Override
    public void label(String step, String label) {
        if (cadenceMs == 0) {
            delegate.label(step, label);
            return;
        }
        synchronized (lock) {
            labelStep = step;
            labelText = label;
            scheduleLocked();
        }
    }

    @Override
    public void output(String step, String line) {
        flush();
        delegate.output(step, line);
    }

    @Override
    public void warn(String step, String code, String message) {
        flush();
        delegate.warn(step, code, message);
    }

    @Override
    public void error(String step, String code, String message) {
        flush();
        delegate.error(step, code, message);
    }

    @Override
    public void error(String step, String code, String message, String test, String exceptionClass) {
        flush();
        delegate.error(step, code, message, test, exceptionClass);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        flush();
        delegate.stepFinish(step, phase, status, duration);
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        flush();
        delegate.pipelineFinish(result);
        close();
    }

    /**
     * Emit any pending progress/label now (also called before structural events). Take + emit are
     * under the same lock so a timer flush cannot reorder past a structural event.
     */
    public void flush() {
        if (closed.get()) return;
        synchronized (lock) {
            cancelScheduledLocked();
            emitPendingLocked();
            lastFlushNanos = System.nanoTime();
        }
    }

    /** Caller holds {@link #lock}. */
    private void emitPendingLocked() {
        String pStep = progressStep;
        int pDelta = progressDelta;
        PipelineView pView = progressView;
        progressStep = null;
        progressDelta = 0;
        progressView = null;

        String tStep = tickStep;
        int tDelta = tickDelta;
        PipelineView tView = tickView;
        tickStep = null;
        tickDelta = 0;
        tickView = null;

        String lStep = labelStep;
        String lText = labelText;
        labelStep = null;
        labelText = null;

        // Emit under lock so structural passthrough cannot race ahead of a concurrent timer flush.
        // Delegate is wire send only — must not re-enter this coalescer on the same instance.
        if (pStep != null && pView != null && pDelta != 0) {
            delegate.progress(pStep, pDelta, pView);
        }
        if (tStep != null && tView != null && tDelta != 0) {
            delegate.tickUpdate(tStep, tDelta, tView);
        }
        if (lStep != null && lText != null) {
            delegate.label(lStep, lText);
        }
    }

    /**
     * Caller holds {@link #lock}. Opens a cadence window on the first pending event (no immediate
     * emit) so storms coalesce; after the window elapses, emit on the next event or the timer.
     */
    private void scheduleLocked() {
        if (closed.get()) return;
        long now = System.nanoTime();
        if (lastFlushNanos == 0L) {
            // Start the human window; first sample lands at cadence (or on structural flush).
            lastFlushNanos = now;
            if (scheduled == null || scheduled.isDone()) {
                scheduled = SCHEDULER.schedule(this::flushSafe, cadenceMs, TimeUnit.MILLISECONDS);
            }
            return;
        }
        long elapsedMs = (now - lastFlushNanos) / 1_000_000L;
        if (elapsedMs >= cadenceMs) {
            emitPendingLocked();
            lastFlushNanos = now;
            return;
        }
        if (scheduled != null && !scheduled.isDone()) return;
        scheduled = SCHEDULER.schedule(this::flushSafe, cadenceMs - elapsedMs, TimeUnit.MILLISECONDS);
    }

    private void cancelScheduledLocked() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
    }

    private void flushSafe() {
        try {
            flush();
        } catch (RuntimeException ignored) {
            // never let scheduler die on a bad client write
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        flush();
        synchronized (lock) {
            cancelScheduledLocked();
        }
    }
}
