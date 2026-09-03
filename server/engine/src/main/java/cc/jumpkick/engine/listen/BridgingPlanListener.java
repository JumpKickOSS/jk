// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import java.time.Duration;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One plan listener: redact, emit to the sink, then run engine side-effects (SSE / acc /
 * workspace tracker). CLI and HTTP differ only by which {@link EventSink} they pass.
 */
@RequiredArgsConstructor
public final class BridgingPlanListener implements BuildPlanListener {

    /**
     * Engine-owned folds a wire event cannot carry: workspace progress tracking, journal
     * accumulation, timeline/slot teardown, and the capped diagnostics publication (needs the
     * whole error list). Event frames themselves ride the {@link EventSink}.
     */
    public interface Hooks {
        default void planProgress(String dir, BuildPlanView view) {}

        default void stepFinished(String dir, String step, String phase, String status, long millis, long waitMillis) {}

        default void planFinished(String dir, BuildPlanResult result) {}

        default void planDiagnostics(String dir, BuildPlanResult result) {}
    }

    private final String dir;
    private final EventSink sink;
    private final Hooks hooks;
    private final @Nullable Function<BuildPlanResult, String> finishEncoder;

    public BridgingPlanListener(String dir, EventSink sink, Hooks hooks) {
        this(dir, sink, hooks, null);
    }

    /**
     * One redactor per plan: building it re-derives the env lookup (workspace-root walk +
     * {@code .env} parse), which is too heavy per output line. The {@code .env} set is frozen
     * for the plan's life. Redaction fail-open: a failed lookup does not fail the build.
     */
    private volatile @Nullable SecretRedactor redactor;

    private SecretRedactor redactor() {
        var r = redactor;
        if (r == null) {
            r = EventRedaction.redactorFor(dir);
            redactor = r;
        }
        return r;
    }

    private @Nullable String redact(@Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return redactor().redact(text);
        } catch (RuntimeException e) {
            EventRedaction.warnFailOpen(e);
            return text;
        }
    }

    public static String phaseWire(@Nullable String group) {
        return group == null ? "" : group;
    }

    @Override
    public void planStart(BuildPlanView view) {
        sink.emit(new EngineEvent.PlanStart(
                dir,
                view.planName(),
                view.numerator(),
                view.denominator(),
                view.stepsTotal(),
                view.stepsComplete(),
                view.cancelled()));
        hooks.planProgress(dir, view);
    }

    @Override
    public void stepStart(String step, String group, int ticks) {
        String phase = phaseWire(group);
        sink.emit(new EngineEvent.StepStart(dir, step, phase, ticks));
    }

    @Override
    public void progress(String step, int delta, BuildPlanView view) {
        sink.emit(new EngineEvent.Progress(
                dir,
                step,
                delta,
                view.numerator(),
                view.denominator(),
                view.stepsTotal(),
                view.stepsComplete(),
                view.cancelled()));
        hooks.planProgress(dir, view);
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView view) {
        sink.emit(new EngineEvent.TickUpdate(
                dir,
                step,
                delta,
                view.numerator(),
                view.denominator(),
                view.stepsTotal(),
                view.stepsComplete(),
                view.cancelled()));
        hooks.planProgress(dir, view);
    }

    @Override
    public void label(String step, String label) {
        String safe = redact(label);
        sink.emit(new EngineEvent.Label(dir, step, safe));
    }

    @Override
    public void output(String step, String line) {
        String safe = redact(line);
        sink.emit(new EngineEvent.Output(dir, step, safe));
    }

    @Override
    public void warn(String step, String code, String message) {
        sink.emit(new EngineEvent.Warn(dir, step, code, redact(message)));
    }

    @Override
    public void error(String step, String code, String message) {
        error(step, code, message, (String) null, null);
    }

    @Override
    public void error(String step, String code, String message, String test, String exceptionClass) {
        sink.emit(new EngineEvent.ErrorLine(dir, step, code, redact(message), test, exceptionClass));
    }

    @Override
    public void error(String step, String code, String message, TestFailureInfo failure) {
        if (failure == null) {
            error(step, code, message);
            return;
        }
        TestFailureInfo safe = redactFailureHoisted(failure);
        String msg = redact(message == null || message.isEmpty() ? failure.message() : message);
        sink.emit(new EngineEvent.ErrorFailure(dir, step, code, msg, safe));
    }

    private @Nullable TestFailureInfo redactFailureHoisted(@Nullable TestFailureInfo f) {
        try {
            return EventRedaction.redactFailure(redactor(), f);
        } catch (RuntimeException e) {
            return f;
        }
    }

    @Override
    public void stepFinish(String step, String group, TaskStatus status, Duration duration, Duration waited) {
        long millis = duration.toMillis();
        long waitMillis = waited == null ? 0 : waited.toMillis();
        String phase = phaseWire(group);
        String st = status.name();
        sink.emit(new EngineEvent.StepFinish(dir, step, phase, st, millis, waitMillis));
        hooks.stepFinished(dir, step, phase, st, millis, waitMillis);
    }

    @Override
    public void planFinish(BuildPlanResult result) {
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            var tf = d.testFailure();
            if (tf != null) {
                TestFailureInfo safe = redactFailureHoisted(tf);
                sink.emit(new EngineEvent.PlanDiagnosticFailure(dir, d.step(), d.code(), redact(d.message()), safe));
            } else {
                sink.emit(new EngineEvent.PlanDiagnostic(
                        dir, d.step(), d.code(), redact(d.message()), d.test(), d.exceptionClass()));
            }
        }
        // Timeline + exclusive-slot release must precede the terminal plan-finish line:
        // a client that has returned must not observe the engine still writing the chrome
        // profile, and a reconnect must not see an already-running fingerprint.
        hooks.planFinished(dir, result);
        sink.emit(new EngineEvent.PlanFinish(dir, result.success()));
        hooks.planDiagnostics(dir, result);
        if (finishEncoder != null) {
            sink.emit(new EngineEvent.PlanFinishLine(finishEncoder.apply(result)));
        }
    }
}
