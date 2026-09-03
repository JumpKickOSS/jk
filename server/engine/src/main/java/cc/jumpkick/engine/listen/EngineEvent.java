// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Domain events the engine emits — the one build-event vocabulary. Sinks encode these per
 * surface: {@link WireEventSink} (CLI JSONL) and {@link SseEventSink} (dashboard/MCP SSE);
 * production listeners compose both. Chrome ({@code status}/{@code cache}/{@code run-snapshot})
 * is dashboard sampling, not a build event, and stays off this spine.
 */
public sealed interface EngineEvent {

    record PlanStart(
            String dir,
            String name,
            long numerator,
            long denominator,
            int stepsTotal,
            int stepsComplete,
            boolean cancelled)
            implements EngineEvent {}

    record StepStart(String dir, String step, String phase, int ticks) implements EngineEvent {}

    record Progress(
            String dir,
            String step,
            int delta,
            long numerator,
            long denominator,
            int stepsTotal,
            int stepsComplete,
            boolean cancelled)
            implements EngineEvent {}

    record TickUpdate(
            String dir,
            String step,
            int delta,
            long numerator,
            long denominator,
            int stepsTotal,
            int stepsComplete,
            boolean cancelled)
            implements EngineEvent {}

    record Label(String dir, String step, String text) implements EngineEvent {}

    record Output(String dir, String step, String line) implements EngineEvent {}

    record Warn(String dir, String step, String code, String message) implements EngineEvent {}

    record ErrorLine(
            String dir,
            String step,
            String code,
            String message,
            @Nullable String test,
            @Nullable String exceptionClass)
            implements EngineEvent {}

    record StepFinish(String dir, String step, String phase, String status, long millis) implements EngineEvent {}

    record PlanFinishLine(String encodedWireLine) implements EngineEvent {}

    record PlanDiagnostic(
            String dir,
            String step,
            String code,
            String message,
            @Nullable String test,
            @Nullable String exceptionClass)
            implements EngineEvent {}

    record ErrorFailure(String dir, String step, String code, String message, TestFailureInfo failure)
            implements EngineEvent {}

    record PlanDiagnosticFailure(String dir, String step, String code, String message, TestFailureInfo failure)
            implements EngineEvent {}

    record Preflight(String stage, int done, int total, String label) implements EngineEvent {}

    record InvocationPhase(String name, String status) implements EngineEvent {}

    record PlanModule(String dir, String coord, String planName, long weight, boolean fullyCached)
            implements EngineEvent {}

    record PlanStep(String dir, String name, String label, String phase) implements EngineEvent {}

    record PlanDone(int modules) implements EngineEvent {}

    record ModuleStart(String dir, @Nullable String coord) implements EngineEvent {}

    record ModuleFinish(
            String dir,
            String coord,
            boolean success,
            int exitCode,
            long millis,
            boolean didWork,
            boolean cancelled,
            ModuleOutcome.Image image)
            implements EngineEvent {}

    record Eta(long remainingMs) implements EngineEvent {}

    /** Total plan weight (Σ modules) — seeds the dashboard bar denominator; not a wire line. */
    record Plan(long totalWeight, int modules) implements EngineEvent {}

    /**
     * A plan finished ({@code buildplan-finish} on SSE). The wire terminal stays
     * {@link PlanFinishLine} — its kind-specific tails are encoded by the owning verb.
     */
    record PlanFinish(String dir, boolean success) implements EngineEvent {}
}
