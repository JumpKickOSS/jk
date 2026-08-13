// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.engine.protocol.EngineProtocol;
import java.io.BufferedWriter;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/** Encodes {@link EngineEvent} as CLI JSONL via {@link EngineProtocol}. */
public final class WireEventSink implements EventSink {
    private final @Nullable BufferedWriter writer;

    public WireEventSink(@Nullable BufferedWriter writer) {
        this.writer = writer;
    }

    @Override
    public void emit(EngineEvent event) {
        if (writer == null) return;
        String line = encode(event);
        if (line == null) return;
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException ignored) {
            // client gone
        }
    }

    static @Nullable String encode(EngineEvent event) {
        return switch (event) {
            case EngineEvent.PlanStart e ->
                EngineProtocol.planStart(
                        e.dir(),
                        e.name(),
                        e.numerator(),
                        e.denominator(),
                        e.stepsTotal(),
                        e.stepsComplete(),
                        e.cancelled());
            case EngineEvent.StepStart e -> EngineProtocol.stepStart(e.dir(), e.step(), e.phase(), e.ticks());
            case EngineEvent.Progress e ->
                EngineProtocol.progress(
                        e.dir(),
                        e.step(),
                        e.delta(),
                        e.numerator(),
                        e.denominator(),
                        e.stepsTotal(),
                        e.stepsComplete(),
                        e.cancelled());
            case EngineEvent.TickUpdate e ->
                EngineProtocol.tickUpdate(
                        e.dir(),
                        e.step(),
                        e.delta(),
                        e.numerator(),
                        e.denominator(),
                        e.stepsTotal(),
                        e.stepsComplete(),
                        e.cancelled());
            case EngineEvent.Label e -> EngineProtocol.label(e.dir(), e.step(), e.text());
            case EngineEvent.Output e -> EngineProtocol.output(e.dir(), e.step(), e.line());
            case EngineEvent.Warn e -> EngineProtocol.warn(e.dir(), e.step(), e.code(), e.message());
            case EngineEvent.ErrorLine e ->
                EngineProtocol.errorLine(e.dir(), e.step(), e.code(), e.message(), e.test(), e.exceptionClass());
            case EngineEvent.StepFinish e ->
                EngineProtocol.stepFinish(e.dir(), e.step(), e.phase(), e.status(), e.millis());
            case EngineEvent.PlanFinishLine e -> e.encodedWireLine();
            case EngineEvent.PlanDiagnostic e ->
                EngineProtocol.planDiagnostic(e.dir(), e.step(), e.code(), e.message(), e.test(), e.exceptionClass());
        };
    }
}
