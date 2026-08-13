// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import org.jspecify.annotations.Nullable;

/** Domain events the engine emits. Sinks encode these for CLI JSONL or dashboard SSE. */
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
}
