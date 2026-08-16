// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import java.io.BufferedWriter;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Encodes {@link EngineEvent} as CLI JSONL via {@link EngineProtocol}.
 *
 * <p>Writes are synchronized on the {@link BufferedWriter}: concurrent modules (and the heartbeat)
 * share one socket. Unsynchronized interleaving corrupts JSONL lines so the client drops them —
 * a lost {@code task-finish} leaves a zombie ACTIVE row in the live tree for the rest of the build.
 */
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
            // Same monitor as EngineServer.send — plan workers and heartbeats share the writer.
            synchronized (writer) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException ignored) {
            // client gone
        }
    }

    static @Nullable String encode(EngineEvent event) {
        return switch (event) {
            case EngineEvent.PlanStart e ->
                ProtoEvents.planStart(
                        e.dir(),
                        e.name(),
                        e.numerator(),
                        e.denominator(),
                        e.stepsTotal(),
                        e.stepsComplete(),
                        e.cancelled());
            case EngineEvent.StepStart e -> ProtoEvents.stepStart(e.dir(), e.step(), e.phase(), e.ticks());
            case EngineEvent.Progress e ->
                ProtoEvents.progress(
                        e.dir(),
                        e.step(),
                        e.delta(),
                        e.numerator(),
                        e.denominator(),
                        e.stepsTotal(),
                        e.stepsComplete(),
                        e.cancelled());
            case EngineEvent.TickUpdate e ->
                ProtoEvents.tickUpdate(
                        e.dir(),
                        e.step(),
                        e.delta(),
                        e.numerator(),
                        e.denominator(),
                        e.stepsTotal(),
                        e.stepsComplete(),
                        e.cancelled());
            case EngineEvent.Label e -> ProtoEvents.label(e.dir(), e.step(), e.text());
            case EngineEvent.Output e -> ProtoEvents.output(e.dir(), e.step(), e.line());
            case EngineEvent.Warn e -> ProtoEvents.warn(e.dir(), e.step(), e.code(), e.message());
            case EngineEvent.ErrorLine e ->
                ProtoEvents.errorLine(e.dir(), e.step(), e.code(), e.message(), e.test(), e.exceptionClass());
            case EngineEvent.StepFinish e ->
                ProtoEvents.stepFinish(e.dir(), e.step(), e.phase(), e.status(), e.millis());
            case EngineEvent.PlanFinishLine e -> e.encodedWireLine();
            case EngineEvent.PlanDiagnostic e ->
                ProtoEvents.planDiagnostic(e.dir(), e.step(), e.code(), e.message(), e.test(), e.exceptionClass());
            case EngineEvent.ErrorFailure e ->
                ProtoEvents.errorLine(e.dir(), e.step(), e.code(), e.message(), e.failure());
            case EngineEvent.PlanDiagnosticFailure e ->
                ProtoEvents.planDiagnostic(e.dir(), e.step(), e.code(), e.message(), e.failure());
            case EngineEvent.Preflight e -> ProtoEvents.preflight(e.stage(), e.done(), e.total(), e.label());
            case EngineEvent.InvocationPhase e -> ProtoEvents.invocationPhase(e.name(), e.status());
            case EngineEvent.PlanModule e ->
                ProtoEvents.planModule(e.dir(), e.coord(), e.planName(), (int) e.weight(), e.fullyCached());
            case EngineEvent.PlanStep e -> ProtoEvents.planStep(e.dir(), e.name(), e.label(), e.phase());
            case EngineEvent.PlanDone e -> ProtoEvents.planDone(e.modules());
            case EngineEvent.ModuleStart e -> ProtoEvents.moduleStart(e.dir());
            case EngineEvent.ModuleFinish e ->
                ProtoEvents.moduleFinish(
                        e.dir(), e.coord(), e.success(), e.exitCode(), e.millis(), e.didWork(), e.cancelled());
            case EngineEvent.Eta e -> ProtoEvents.eta(e.remainingMs());
        };
    }
}
