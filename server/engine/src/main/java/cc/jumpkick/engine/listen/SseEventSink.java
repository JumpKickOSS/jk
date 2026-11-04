// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.engine.api.SseEvents;
import cc.jumpkick.wire.protocol.EventCatalog;

/**
 * Encodes {@link EngineEvent} as dashboard/MCP SSE frames via {@link SseEvents}. The second
 * production sink beside {@link WireEventSink}: one event vocabulary, two encodings. Which events
 * become frames is {@link EventCatalog}'s to say, each omission with its reason: per-line
 * diagnostics, for one, reach SSE through the capped publication at the plan's end
 * ({@code Hooks.planDiagnostics}), not here.
 */
public final class SseEventSink implements EventSink {

    private final SseEvents sse;
    private final long requestId;

    public SseEventSink(SseEvents sse, long requestId) {
        this.sse = sse;
        this.requestId = requestId;
    }

    @Override
    public void emit(EngineEvent event) {
        // The catalog decides what SSE carries from an engine event; the switch only spells how.
        // An event it omits is dropped here with its reason written there, never by an empty case.
        if (!(EventCatalog.row(event.type()) instanceof EventCatalog.Row row)
                || !(row.sse() instanceof EventCatalog.Carried carried)
                || !carried.fromEventSink()
                || event instanceof EngineEvent.PlanFinishLine) {
            return;
        }
        switch (event) {
            case EngineEvent.PlanStart e -> sse.publishPlanProgress(requestId, e.dir(), e.numerator(), e.denominator());
            case EngineEvent.Progress e -> sse.publishPlanProgress(requestId, e.dir(), e.numerator(), e.denominator());
            case EngineEvent.TickUpdate e ->
                sse.publishPlanProgress(requestId, e.dir(), e.numerator(), e.denominator());
            case EngineEvent.StepStart e -> sse.publishStepStart(requestId, e.dir(), e.step(), e.phase());
            case EngineEvent.StepFinish e ->
                sse.publishStepFinish(requestId, e.dir(), e.step(), e.phase(), e.status(), e.millis());
            case EngineEvent.Label e -> sse.publishLabel(requestId, e.dir(), e.step(), e.text());
            case EngineEvent.Output e -> sse.publishOutput(requestId, e.dir(), e.step(), e.line());
            case EngineEvent.ModuleStart e -> sse.publishModuleStart(requestId, e.dir(), e.coord());
            case EngineEvent.ModuleFinish e ->
                sse.publishModuleFinish(
                        requestId,
                        e.dir(),
                        e.coord(),
                        e.success(),
                        e.millis(),
                        e.didWork(),
                        e.cancelled(),
                        e.stopped());
            case EngineEvent.Eta e -> sse.publishEta(requestId, e.remainingMs());
            case EngineEvent.Plan e -> sse.publishPlan(requestId, e.totalWeight());
            case EngineEvent.PlanFinish e -> sse.publishBuildPlanFinish(requestId, e.dir(), e.success());
            default ->
                throw new IllegalStateException(
                        "EventCatalog says the SSE event sink carries " + event.type() + ", and no case publishes it");
        }
    }
}
