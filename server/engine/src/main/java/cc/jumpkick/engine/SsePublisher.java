// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.jobs.JobSession;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.runtime.ProjectIds;
import cc.jumpkick.runtime.RemainingWork;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import cc.jumpkick.task.IoLedger;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Dashboard SSE fan-out and workspace-progress emit. */
@RequiredArgsConstructor
public final class SsePublisher {

    public interface Acc {
        void stepStart(long requestId, String dir, String step, String phase);
    }

    private final JobSessions sessions;
    private final InFlightBuilds inFlight;
    private final @Nullable HttpEvents events;
    private final Supplier<HttpEngineServer> http;
    private final LongSupplier clock;
    private final AtomicInteger activePlans;
    private final ReentrantReadWriteLock sseConnect;
    private final Acc acc;

    public static final int MAX_DIAGNOSTIC_EVENTS = 8;
    public static final int MAX_TEST_FAILURE_EVENTS = 100;

    private @Nullable JobSession open(long id) {
        return sessions.open(id);
    }

    private @Nullable JobSession get(long id) {
        return sessions.get(id);
    }

    private @Nullable Double lastProgress(long id) {
        JobSession s = get(id);
        return s == null ? null : s.lastProgress();
    }

    private @Nullable Long lastProgressDen(long id) {
        JobSession s = get(id);
        return s == null ? null : s.lastProgressDen();
    }

    private void setLastProgress(long id, double p) {
        JobSession s = open(id);
        if (s != null) s.lastProgress(p);
    }

    private void setLastProgressDen(long id, long den) {
        JobSession s = open(id);
        if (s != null) s.lastProgressDen(den);
    }

    private String progressRoot(long id) {
        JobSession s = get(id);
        String r = s == null ? null : s.progressRoot();
        return r == null ? "" : r;
    }

    private RemainingWork remaining(long id) {
        JobSession s = get(id);
        return s == null ? null : s.remaining();
    }

    private long weight(long requestId, String dir) {
        if (requestId <= 0 || dir == null) return 0;
        JobSession s = get(requestId);
        if (s == null) return 0;
        Long w = s.weights().get(dir);
        return w != null ? w : 0;
    }

    private WorkspaceProgressTracker tracker(long requestId) {
        if (sessions.retired(requestId)) {
            return new WorkspaceProgressTracker(null);
        }
        JobSession s = open(requestId);
        if (s == null) return new WorkspaceProgressTracker(null);
        return s.tracker();
    }

    private WorkspaceProgressTracker trackerOrNull(long requestId) {
        JobSession s = get(requestId);
        return s == null ? null : s.existingTracker();
    }

    private Object emitLock(long requestId) {
        JobSession s = open(requestId);
        return s == null ? new Object() : s.emitLock();
    }

    private long[] emitState(long requestId) {
        JobSession s = get(requestId);
        return s == null ? null : s.emitState();
    }

    private void setEmitState(long requestId, long[] state) {
        JobSession s = open(requestId);
        if (s != null) s.emitState(state);
    }

    private BuildAccumulator accumulator(long requestId) {
        JobSession s = get(requestId);
        return s == null ? null : s.accumulator();
    }

    public void publishEvent(String type, JsonOut payload) {
        publishEvent(type, payload, false);
    }

    /**
     * As {@link #publishEvent(String, cc.jumpkick.engine.JsonOut)}; {@code dashboardOnly}
     * frames (SSE-connect rehydrate replays) skip MCP subscriptions — the dashboard folds a
     * duplicate {@code request-start} idempotently, but an MCP agent treating it as "job began"
     * would double-count.
     */
    public void publishEvent(String type, JsonOut payload, boolean dashboardOnly) {
        sseConnect.readLock().lock();
        try {
            if (events != null && events.hasSubscribers()) {
                if (dashboardOnly) events.publishDashboard(type, payload);
                else events.publish(type, payload);
            }
            // Sampled chrome (status/cache SSE) is change-gated; nudge it when jobs start/finish so
            // Builds Running and storage totals do not wait for the next timer tick.
            HttpEngineServer server = http.get();
            if (server != null && ("request-start".equals(type) || "request-finish".equals(type))) {
                server.notifyLiveStatus();
                if ("request-finish".equals(type)) server.notifyLiveCache();
            }
        } finally {
            sseConnect.readLock().unlock();
        }
    }

    /**
     * Attach last known <em>workspace aggregate</em> {@code progress} (0–100 or null) from the
     * engine tracker. Never compute from module-local ticks here.
     */
    public JsonOut withProgress(JsonOut payload, long requestId) {
        Double p = requestId > 0 ? lastProgress(requestId) : null;
        return payload.putNullable("progress", p);
    }

    /**
     * The ambient byte ledger for a request: the journal accumulator's when the kind is journaled,
     * else a throwaway so metering call sites never branch on whether anyone is recording.
     */
    public IoLedger runIo(long requestId) {
        BuildAccumulator a = accumulator(requestId);
        return a != null ? a.io() : new IoLedger();
    }

    /**
     * Add the run's byte counters to a terminal event so a live dashboard card shows them without
     * waiting for the history backfill. Omitted entirely for a run that moved nothing.
     */
    public JsonOut withIo(JsonOut payload, long requestId) {
        BuildAccumulator a = accumulator(requestId);
        if (a == null) return payload;
        IoLedger.Totals t = a.io().totals();
        if (t.isEmpty()) return payload;
        return payload.put("remoteUpBytes", t.remoteUp())
                .put("remoteDownBytes", t.remoteDown())
                .put("localUpBytes", t.localUp())
                .put("localDownBytes", t.localDown());
    }

    /**
     * Feed module plan ticks into residual {@code R(t)} and the workspace bar, then emit
     * {@code workspace-progress} + updated remaining ETA.
     */
    public void trackModuleBuildPlan(
            long requestId, String dir, BuildPlanView view, BufferedWriter writer, boolean forceEmit) {
        if (requestId <= 0 || view == null) return;
        double frac = view.denominator() > 0
                ? Math.min(1.0, Math.max(0.0, (double) view.numerator() / (double) view.denominator()))
                : 0.0;
        // Bar: effort-weight slices (plan num/den) + residual annotation for adaptive clock/countdown.
        long slice = weight(requestId, dir);
        tracker(requestId).moduleProgress(dir, slice, view.numerator(), view.denominator());
        RemainingWork rw = remaining(requestId);
        if (rw != null && dir != null) {
            // Atomic update+recompute+note per request: two scheduler threads interleaving
            // (T1 computes 10s, T2 computes 9s and notes it, T1 notes 10s last) can regress
            // wire remainingMs. rw's own methods synchronize on rw, so this monitor is
            // reentrant and orders the notes with their computations.
            synchronized (rw) {
                rw.moduleProgress(Path.of(dir), frac);
                tracker(requestId).noteRemaining(rw.remaining(), rw.R0());
            }
        }
        emitWorkspaceProgress(requestId, writer, forceEmit);
    }

    public void trackModuleComplete(long requestId, String dir, long lastDen, BufferedWriter writer) {
        if (requestId <= 0) return;
        RemainingWork rw = remaining(requestId);
        if (rw != null && dir != null) {
            synchronized (rw) {
                rw.moduleComplete(Path.of(dir));
                tracker(requestId).noteRemaining(rw.remaining(), rw.R0());
            }
        }
        tracker(requestId).moduleComplete(dir, lastDen);
        emitWorkspaceProgress(requestId, writer, true);
    }

    /**
     * Emit filterable {@code workspace-progress} on the socket (when {@code writer} non-null) and SSE
     * hub. Throttled unless {@code force} (stage boundaries, module complete, finish).
     */
    public void emitWorkspaceProgress(long requestId, BufferedWriter writer, boolean force) {
        emitWorkspaceProgress(requestId, writer, force, false);
    }

    public void emitWorkspaceProgress(long requestId, BufferedWriter writer, boolean force, boolean dashboardOnly) {
        if (requestId <= 0) return;
        // A straggler from an abandoned job must not re-register the maps teardown just cleared,
        // nor take a fresh emit lock that no longer serializes against anything.
        if (sessions.retired(requestId)) return;
        Object lock = emitLock(requestId);
        synchronized (lock) {
            WorkspaceProgressTracker tracker = trackerOrNull(requestId);
            if (tracker == null) return;
            var snap = tracker.snapshot();
            double heldPct = Double.NaN;
            if (snap.hasPercent()) {
                // Peak-hold machine progress: never publish a lower % than already emitted —
                // rebase when the denominator grew (calibrate), or the preflight peak pins the
                // rider for the whole execute phase.
                Double prevPct = lastProgress(requestId);
                Long prevDen = lastProgressDen(requestId);
                heldPct = snap.percent();
                boolean denGrew = prevDen != null && snap.denominator() > prevDen;
                if (!denGrew && prevPct != null && heldPct + 1e-9 < prevPct) {
                    heldPct = prevPct;
                }
                setLastProgress(requestId, heldPct);
                setLastProgressDen(requestId, snap.denominator());
            }
            if (!force && !shouldEmitWorkspaceProgress(requestId, snap)) return;
            String dir = progressRoot(requestId);
            // snapshot() recomputes open-loop percent when R0 is set (clock strategy).
            long num = snap.numerator();
            long den = snap.denominator();
            long rem = snap.remainingMs();
            long r0 = snap.R0ms();
            // Held percent on both wire surfaces (JSONL and SSE).
            double pct = heldPct;
            String line = ProtoEvents.workspaceProgress(
                    dir, num, den, snap.phase(), snap.modulesComplete(), snap.modulesTotal(), rem, r0, pct);
            if (writer != null) WireWriter.sendQuiet(writer, line);
            if (eventsWanted()) {
                var body = JsonOut.object()
                        .put("schema", 1)
                        .put("type", EngineProtocol.WORKSPACE_PROGRESS)
                        .put("jid", requestId)
                        .put("dir", dir)
                        .put("numerator", num)
                        .put("denominator", den)
                        .put("phase", snap.phase())
                        .put("modulesComplete", snap.modulesComplete())
                        .put("modulesTotal", snap.modulesTotal())
                        .put("remainingMs", rem)
                        .put("R0", r0);
                if (!Double.isNaN(pct)) body.put("progress", pct);
                publishEvent(EngineProtocol.WORKSPACE_PROGRESS, withProgress(body, requestId), dashboardOnly);
            }
            Double held = lastProgress(requestId);
            long pctMillis = held != null
                    ? Math.round(held * 10.0)
                    : (snap.hasPercent() ? Math.round(snap.percent() * 10.0) : -1L);
            setEmitState(requestId, new long[] {System.currentTimeMillis(), pctMillis});
        }
    }

    /**
     * Same human cadence as {@link CoalescingBuildPlanListener} / {@code JK_WIRE_PROGRESS_MS}
     * (default 500 ms). CLI TUI and web open-loop the bar between samples; shipping every TTY
     * frame (80 ms) on UDS+SSE was pure wire cost. {@code force} emits still bypass this (stage
     * boundaries, module complete, finish). {@code JK_WIRE_PROGRESS_MS=0} is unbatched.
     */
    public boolean shouldEmitWorkspaceProgress(long requestId, WorkspaceProgressTracker.Snapshot snap) {
        long cadence = CoalescingBuildPlanListener.cadenceFromEnv();
        if (cadence <= 0) return true;
        long[] prev = emitState(requestId);
        if (prev == null) return true;
        long now = System.currentTimeMillis();
        return now - prev[0] >= cadence;
    }

    /**
     * {@code request-start}, enriched with the project's {@code group:name} coordinate when the
     * dir's {@code jk.toml} parses — the dashboard renders coordinates, not paths, when it can
     * (the design's coord coloring). Best-effort and only attempted with a subscriber connected.
     */
    public void publishRequestStart(long requestId, String kind, String dir) {
        publishRequestStart(requestId, kind, dir, 0L);
    }

    public void publishRequestStart(long requestId, String kind, String dir, long buildNumber) {
        publishRequestStart(requestId, kind, dir, buildNumber, false);
    }

    public void publishRequestStart(long requestId, String kind, String dir, long buildNumber, boolean dashboardOnly) {
        long startedAt =
                inFlight.get(requestId).map(InFlightBuilds.Hold::startedAt).orElse(0L);
        publishRequestStart(requestId, kind, dir, buildNumber, dashboardOnly, startedAt);
    }

    public void publishRequestStart(
            long requestId, String kind, String dir, long buildNumber, boolean dashboardOnly, long startedAt) {
        if (!eventsWanted()) return;
        String coord = null;
        try {
            var project = JkBuildParser.parse(Path.of(dir).resolve(ManifestPaths.MANIFEST))
                    .project();
            coord = project.group() + ":" + project.name();
        } catch (Exception e) {
            // unparseable/missing jk.toml — the dashboard falls back to showing the dir
        }
        var payload = JsonOut.object()
                .put("schema", 1)
                .put("type", "request-start")
                .put("jid", requestId)
                .put("kind", kind)
                .put("dir", dir)
                .put("coord", coord)
                .put("projectId", ProjectIds.idOf(dir));
        if (buildNumber > 0) payload = payload.put("buildNumber", buildNumber);
        if (startedAt > 0) {
            payload = payload.put("startedAt", startedAt);
            // Engine "now" beside engine startedAt: elapsed = serverNow - startedAt is skew-free;
            // the SPA re-anchors it to its own clock at receipt.
            payload = payload.put("serverNow", clock.getAsLong());
        }
        payload = payload.put("activeBuildPlans", activePlans.get());
        publishEvent("request-start", withProgress(payload, requestId), dashboardOnly);
    }

    public void publishStepStart(long requestId, String dir, String step, String phase) {
        publishStepStart(requestId, dir, step, phase, false);
    }

    public void publishStepStart(long requestId, String dir, String step, String phase, boolean dashboardOnly) {
        acc.stepStart(requestId, dir, step, phase);
        if (!eventsWanted()) return;
        // Field names align with CLI JsonlShape (schema + type + task + group).
        publishEvent(
                EngineProtocol.TASK_START,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.TASK_START)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("stage", phase),
                        requestId),
                dashboardOnly);
    }

    public void publishStepFinish(long requestId, String dir, String step, String phase, String status, long millis) {
        publishStepFinish(requestId, dir, step, phase, status, millis, false);
    }

    public void publishStepFinish(
            long requestId, String dir, String step, String phase, String status, long millis, boolean dashboardOnly) {
        if (!eventsWanted()) return;
        publishEvent(
                EngineProtocol.TASK_FINISH,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.TASK_FINISH)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("stage", phase)
                                .put("status", status)
                                .put("millis", millis),
                        requestId),
                dashboardOnly);
    }

    /**
     * Live step detail (test class.method, "shrinking jar", …) — same payload as the socket
     * {@code label} line. The SPA paints it after the running phase node (CLI tree-row parity).
     */
    public void publishLabel(long requestId, String dir, String step, String label) {
        if (!eventsWanted()) return;
        publishEvent(
                EngineProtocol.LABEL,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.LABEL)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("label", EventRedaction.redactEnv(dir, label)),
                        requestId));
    }

    public void publishOutput(long requestId, String dir, String step, String line) {
        if (!eventsWanted()) return;
        // Rate is owned by CoalescingBuildPlanListener (JK_WIRE_PROGRESS_MS) on both CLI wire and
        // HTTP hub paths — do not double-throttle here.
        // Redact here, not per caller: the HTTP/MCP job listener feeds raw step output and the
        // SSE stream is readable token-free on loopback. Idempotent for callers that
        // already masked.
        publishEvent(
                EngineProtocol.OUTPUT,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.OUTPUT)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("task", step)
                                .put("line", EventRedaction.redactEnv(dir, line)),
                        requestId));
    }

    /**
     * Which diagnostics to put on the live SSE card. {@code test-failure} diagnostics are kept up
     * to {@link #MAX_TEST_FAILURE_EVENTS}; other codes (javac, resolve, …) are capped at
     * {@link #MAX_DIAGNOSTIC_EVENTS}.
     */
    public static List<BuildPlanResult.Diagnostic> selectPublishedDiagnostics(List<BuildPlanResult.Diagnostic> errors) {
        if (errors == null || errors.isEmpty()) return List.of();
        ArrayList<BuildPlanResult.Diagnostic> out = new ArrayList<>(errors.size());
        int tests = 0;
        int other = 0;
        for (BuildPlanResult.Diagnostic d : errors) {
            if ("test-failure".equals(d.code())) {
                if (tests < MAX_TEST_FAILURE_EVENTS) {
                    out.add(d);
                    tests++;
                }
                continue;
            }
            if (other < MAX_DIAGNOSTIC_EVENTS) {
                out.add(d);
                other++;
            }
        }
        return out;
    }

    /** How many diagnostics {@link #selectPublishedDiagnostics} dropped — feeds the "+N more" line. */
    public static int unpublishedCount(List<BuildPlanResult.Diagnostic> errors) {
        if (errors == null) return 0;
        int tests = 0;
        int other = 0;
        for (BuildPlanResult.Diagnostic d : errors) {
            if ("test-failure".equals(d.code())) tests++;
            else other++;
        }
        return Math.max(0, other - MAX_DIAGNOSTIC_EVENTS) + Math.max(0, tests - MAX_TEST_FAILURE_EVENTS);
    }

    /** Publish structured {@link BuildPlanResult.Diagnostic}s for a failed request card. */
    public void publishDiagnostics(long requestId, String dir, List<BuildPlanResult.Diagnostic> errors) {
        if (!eventsWanted() || errors.isEmpty()) return;
        for (BuildPlanResult.Diagnostic d : selectPublishedDiagnostics(errors)) {
            publishEvent("error", withProgress(errorEventJson(requestId, dir, d), requestId));
        }
        int dropped = unpublishedCount(errors);
        if (dropped > 0) {
            publishRequestError(requestId, dir, "+ " + dropped + " more errors — see the CLI output");
        }
    }

    /**
     * The SSE {@code error} event body for one diagnostic. The event name equals the payload type
     * ("error"), same as CLI JsonlShape; the test class rides {@link EngineProtocol#TEST_CLASS_FIELD},
     * the journal's persisted spelling.
     */
    static JsonOut errorEventJson(long requestId, String dir, BuildPlanResult.Diagnostic d) {
        var o = JsonOut.object()
                .put("schema", 1)
                .put("type", "error")
                .put("jid", requestId)
                .put("dir", dir)
                .put("task", d.step())
                .put("code", d.code())
                .put("message", EventRedaction.redactEnv(dir, d.message()));
        if (d.module() != null && !d.module().isEmpty()) o.put("module", d.module());
        if (d.engine() != null && !d.engine().isEmpty()) o.put("engine", d.engine());
        if (d.className() != null && !d.className().isEmpty()) o.put(EngineProtocol.TEST_CLASS_FIELD, d.className());
        if (d.method() != null && !d.method().isEmpty()) o.put("method", d.method());
        if (d.exceptionClass() != null && !d.exceptionClass().isEmpty()) o.put("exceptionClass", d.exceptionClass());
        if (d.stack() != null && !d.stack().isEmpty()) o.put("stack", EventRedaction.redactEnv(dir, d.stack()));
        if (d.file() != null && !d.file().isEmpty()) o.put("file", d.file());
        if (d.line() > 0) o.put("line", d.line());
        if (d.snippetStart() > 0) o.put("snippetStart", d.snippetStart());
        if (d.snippet() != null && !d.snippet().isEmpty()) o.putStrings("snippet", d.snippet());
        if (d.worker() > 0) o.put("worker", d.worker());
        if (d.test() != null && !d.test().isEmpty()) o.put("test", d.test());
        return o;
    }

    /** A single request-level failure line (bad jk.toml, workspace orchestration error, …). */
    public void publishRequestError(long requestId, String dir, String message) {
        if (!eventsWanted() || message == null || message.isBlank()) return;
        publishEvent(
                "error",
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", "error")
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("task", "request")
                                .put("code", "error")
                                .put("message", EventRedaction.redactEnv(dir, message))
                                .put("test", "")
                                .put("exceptionClass", ""),
                        requestId));
    }

    /**
     * The build's total weight (Σ module weights) — seeds the dashboard bar's denominator up front so
     * the aggregate never jumps backward as later modules start. Mirrors what {@code onPlan} already
     * sends the CLI as per-module {@code plan-module} weights. Weight is the same abstract unit the
     * CLI bar uses ({@code EffortWeights}, ≈150 ms/unit); the dashboard only needs the ratio.
     */
    public void publishPlan(long requestId, long totalWeight) {
        if (!eventsWanted()) return;
        // Plan seeds the bar denominator; progress is still preflight-only until execute ticks.
        publishEvent(
                "plan",
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", "plan")
                                .put("jid", requestId)
                                .put("weight", totalWeight),
                        requestId));
    }

    /**
     * Fine-grained module plan ticks for the dashboard (step detail). Aggregate % on SSE/MCP:
     * workspace builds use {@link cc.jumpkick.runtime.WorkspaceProgressTracker}; single-plan
     * jobs (build/test/compile) have no tracker yet — the plan <em>is</em> the whole request, so
     * feed last-progress on the session from this view.
     */
    public void publishPlanProgress(long requestId, String dir, long numerator, long denominator) {
        if (requestId > 0 && trackerOrNull(requestId) == null && denominator > 0) {
            double p = WorkspaceProgressTracker.percentOf(numerator, denominator);
            if (!Double.isNaN(p)) setLastProgress(requestId, p);
        }
        if (!eventsWanted()) return;
        publishEvent(
                EngineProtocol.PROGRESS,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.PROGRESS)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("numerator", numerator)
                                .put("denominator", denominator),
                        requestId));
    }

    /**
     * The calibrated ETA in millis — the same value {@code jk build}'s countdown and {@code jk
     * explain}'s estimate show ({@code BuildService.seedEta} + live re-projections). Emitted for the
     * seed and every re-projection, exactly where the socket path sends {@code EngineProtocol.eta}.
     */
    public void publishEta(long requestId, long millis) {
        if (!eventsWanted()) return;
        publishEvent(
                EngineProtocol.ETA,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.ETA)
                                .put("jid", requestId)
                                .put("millis", millis),
                        requestId));
    }

    /**
     * Guard for event publishers: build the payload only when someone is listening. Split from
     * {@link #publishEvent} so hot listener callbacks (per-plan, per-module) pay one boolean check,
     * not a {@code JsonOut} allocation, when no dashboard is open.
     */
    public boolean eventsWanted() {
        return events != null && events.hasSubscribers();
    }

    public void publishModuleStart(long requestId, String dir, String coord) {
        publishModuleStart(requestId, dir, coord, false);
    }

    public void publishModuleStart(long requestId, String dir, String coord, boolean dashboardOnly) {
        if (!eventsWanted()) return;
        publishEvent(
                EngineProtocol.MODULE_START,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.MODULE_START)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("coord", coord),
                        requestId),
                dashboardOnly);
    }

    public void publishModuleFinish(
            long requestId, String dir, String coord, boolean success, long millis, boolean didWork) {
        publishModuleFinish(requestId, dir, coord, success, millis, didWork, false, false);
    }

    public void publishModuleFinish(
            long requestId,
            String dir,
            String coord,
            boolean success,
            long millis,
            boolean didWork,
            boolean cancelled) {
        publishModuleFinish(requestId, dir, coord, success, millis, didWork, cancelled, false);
    }

    public void publishModuleFinish(
            long requestId,
            String dir,
            String coord,
            boolean success,
            long millis,
            boolean didWork,
            boolean cancelled,
            boolean dashboardOnly) {
        if (!eventsWanted()) return;
        publishEvent(
                EngineProtocol.MODULE_FINISH,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.MODULE_FINISH)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("coord", coord)
                                .put("success", success)
                                .put("millis", millis)
                                .put("didWork", didWork)
                                .put("cancelled", cancelled),
                        requestId),
                dashboardOnly);
    }

    public void publishBuildPlanFinish(long requestId, String dir, boolean success) {
        if (!eventsWanted()) return;
        // Do not clear the workspace tracker here — modules finish many times per request.
        // Request teardown / finish owns final 100% and clearProgress.
        publishEvent(
                EngineProtocol.BUILDPLAN_FINISH,
                withProgress(
                        JsonOut.object()
                                .put("schema", 1)
                                .put("type", EngineProtocol.BUILDPLAN_FINISH)
                                .put("jid", requestId)
                                .put("dir", dir)
                                .put("success", success),
                        requestId));
    }
}
