// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.engine.SsePublisher;

/**
 * Encodes {@link EngineEvent} as dashboard/MCP SSE frames via {@link SsePublisher}. The second
 * production sink beside {@link WireEventSink}: one event vocabulary, two encodings. Events with
 * no SSE frame (wire-only bursts, per-line diagnostics — the capped publication needs the whole
 * list and stays on {@code Hooks.planDiagnostics}) map to nothing.
 */
public final class SseEventSink implements EventSink {

    private final SsePublisher sse;
    private final long requestId;

    public SseEventSink(SsePublisher sse, long requestId) {
        this.sse = sse;
        this.requestId = requestId;
    }

    @Override
    public void emit(EngineEvent event) {
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
                        requestId, e.dir(), e.coord(), e.success(), e.millis(), e.didWork(), e.cancelled());
            case EngineEvent.Eta e -> sse.publishEta(requestId, e.remainingMs());
            case EngineEvent.Plan e -> sse.publishPlan(requestId, e.totalWeight());
            case EngineEvent.PlanFinish e -> sse.publishBuildPlanFinish(requestId, e.dir(), e.success());
            case EngineEvent.Warn e -> {}
            case EngineEvent.ErrorLine e -> {}
            case EngineEvent.ErrorFailure e -> {}
            case EngineEvent.PlanDiagnostic e -> {}
            case EngineEvent.PlanDiagnosticFailure e -> {}
            case EngineEvent.PlanFinishLine e -> {}
            case EngineEvent.Preflight e -> {}
            case EngineEvent.InvocationPhase e -> {}
            case EngineEvent.PlanModule e -> {}
            case EngineEvent.PlanStep e -> {}
            case EngineEvent.PlanDone e -> {}
        }
    }
}
