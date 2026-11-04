// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.engine.api.SseEvents;
import cc.jumpkick.wire.protocol.EventCatalog;
import cc.jumpkick.wire.protocol.FailureTextRefs;
import cc.jumpkick.wire.protocol.PlanFinishEvent;
import org.jspecify.annotations.Nullable;

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

    /** Never consulted: SSE carries no test failure, the one record that refers back to earlier text. */
    private final FailureTextRefs texts = new FailureTextRefs();

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
        // SSE carries what the wire sends, as the wire spells it: one record, one codec. Encoded
        // only for a listener; with none, the publisher still keeps its books from the counts.
        String record = !sse.eventsWanted()
                ? ""
                : event instanceof EngineEvent.PlanFinish e
                        ? new PlanFinishEvent(e.dir(), e.success(), false).encode()
                        : WireEventSink.encode(event, texts);
        switch (event) {
            case EngineEvent.Plan e -> sse.publishPlan(requestId, e.totalWeight());
            case EngineEvent.PlanStart e ->
                sse.publishPlanProgress(requestId, e.dir(), e.numerator(), e.denominator(), required(record, event));
            case EngineEvent.Progress e ->
                sse.publishPlanProgress(requestId, e.dir(), e.numerator(), e.denominator(), required(record, event));
            case EngineEvent.TickUpdate e ->
                sse.publishPlanProgress(requestId, e.dir(), e.numerator(), e.denominator(), required(record, event));
            case EngineEvent.StepStart e ->
                sse.publishStepStart(requestId, e.dir(), e.step(), e.phase(), required(record, event));
            case EngineEvent.StepFinish e -> sse.publishRecord(requestId, required(record, event));
            case EngineEvent.Label e -> sse.publishRecord(requestId, required(record, event));
            case EngineEvent.Output e -> sse.publishRecord(requestId, required(record, event));
            case EngineEvent.ModuleStart e -> sse.publishRecord(requestId, required(record, event));
            case EngineEvent.ModuleFinish e -> sse.publishRecord(requestId, required(record, event));
            case EngineEvent.Eta e -> sse.publishRecord(requestId, required(record, event));
            case EngineEvent.PlanFinish e -> sse.publishRecord(requestId, required(record, event));
            default ->
                throw new IllegalStateException(
                        "EventCatalog says the SSE event sink carries " + event.type() + ", and no case publishes it");
        }
    }

    private static String required(@Nullable String record, EngineEvent event) {
        if (record == null) throw new IllegalStateException(event.type() + " has no wire record to publish");
        return record;
    }
}
