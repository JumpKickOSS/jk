// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.api.CoalescingBuildPlanListener;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.api.SseEvents;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.jobs.JobSession;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.listen.SseFrame;
import cc.jumpkick.engine.plugin.MemoryHolds;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.RunShapes;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ErrorLineEvent;
import cc.jumpkick.wire.protocol.EventCatalog;
import cc.jumpkick.wire.protocol.MemoryHoldEvent;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.runtime.RemainingWork;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Dashboard SSE fan-out and workspace-progress emit. */
@RequiredArgsConstructor
public final class SsePublisher implements SseEvents {

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

    private @Nullable RemainingWork remaining(long id) {
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

    private @Nullable WorkspaceProgressTracker trackerOrNull(long requestId) {
        JobSession s = get(requestId);
        return s == null ? null : s.existingTracker();
    }

    private Object emitLock(long requestId) {
        JobSession s = open(requestId);
        return s == null ? new Object() : s.emitLock();
    }

    private long @Nullable [] emitState(long requestId) {
        JobSession s = get(requestId);
        return s == null ? null : s.emitState();
    }

    private void setEmitState(long requestId, long[] state) {
        JobSession s = open(requestId);
        if (s != null) s.emitState(state);
    }

    private @Nullable BuildAccumulator accumulator(long requestId) {
        JobSession s = get(requestId);
        return s == null ? null : s.accumulator();
    }

    public void publishEvent(String type, JsonOut payload) {
        publishEvent(type, payload, false);
    }

    /**
     * As {@link #publishEvent(String, cc.jumpkick.engine.api.JsonOut)}; {@code dashboardOnly}
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
            if (server != null
                    && ("request-start".equals(type)
                            || "request-queued".equals(type)
                            || "request-finish".equals(type))) {
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
            long requestId, String dir, BuildPlanView view, @Nullable BufferedWriter writer, boolean forceEmit) {
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

    /**
     * A module's step started or finished: a model priced step by step counts the module down by
     * its steps ({@link RemainingWork#stepStart}); a weight-priced one ignores it.
     */
    public void trackStep(long requestId, String dir, String step, boolean started, @Nullable BufferedWriter writer) {
        if (requestId <= 0 || dir == null || step == null) return;
        RemainingWork rw = remaining(requestId);
        if (rw == null) return;
        Path p = Path.of(dir);
        synchronized (rw) {
            if (!rw.steps(p)) return;
            if (started) rw.stepStart(p, step);
            else rw.stepFinish(p, step);
            tracker(requestId).noteRemaining(rw.remaining(), rw.R0());
        }
        emitWorkspaceProgress(requestId, writer, false);
    }

    /**
     * A test class of a module's suite finished: the suite's progress is the share of its class
     * walls done, each class at its recorded wall ({@link SuiteClassProgress}).
     */
    public void trackTestClass(
            long requestId, String dir, String step, String className, long millis, @Nullable BufferedWriter writer) {
        if (requestId <= 0 || dir == null || className == null) return;
        RemainingWork rw = remaining(requestId);
        if (rw == null) return;
        Path p = Path.of(dir);
        synchronized (rw) {
            if (!rw.steps(p)) return;
            String suite =
                    TaskNames.RUN_TESTS.equals(step) ? RunShapes.of(requestId).suite() : step;
            double f = suiteProgress
                    .computeIfAbsent(requestId + "\n" + dir, k -> SuiteClassProgress.of(dir, suite))
                    .finished(className, millis);
            if (f == SuiteClassProgress.UNKNOWN) return;
            rw.suiteProgress(p, f);
            tracker(requestId).noteRemaining(rw.remaining(), rw.R0());
        }
        emitWorkspaceProgress(requestId, writer, false);
    }

    /**
     * The build went on hold for memory ({@link MemoryHolds}): its clock and its running steps
     * stand still until it resumes, and the client and the dashboard are told.
     */
    public void holdStarted(long requestId, long needBytes, long freeBytes) {
        JobSession s = get(requestId);
        if (s == null || s.retired()) return;
        s.tracker().hold();
        RemainingWork rw = s.remaining();
        if (rw != null) {
            synchronized (rw) {
                rw.hold();
            }
        }
        sendHold(requestId, s, MemoryHoldEvent.start(progressRoot(requestId), needBytes, freeBytes));
    }

    /** The build resumed after {@code heldMs} on hold: the time held is taken off its clock. */
    public void holdEnded(long requestId, long heldMs) {
        JobSession s = get(requestId);
        if (s == null || s.retired()) return;
        s.tracker().resume();
        RemainingWork rw = s.remaining();
        if (rw != null) {
            synchronized (rw) {
                rw.resume();
            }
        }
        sendHold(requestId, s, MemoryHoldEvent.end(progressRoot(requestId), heldMs));
    }

    private void sendHold(long requestId, JobSession s, MemoryHoldEvent event) {
        String line = event.encode();
        BufferedWriter writer = s.writer();
        if (writer != null) WireWriter.sendQuiet(writer, line);
        if (eventsWanted()) publishEvent(event.type(), SseFrame.of(event.type(), requestId, line), false);
        emitWorkspaceProgress(requestId, writer, true);
    }

    /** Per request and module, its running suite's class progress. */
    private final ConcurrentHashMap<String, SuiteClassProgress> suiteProgress = new ConcurrentHashMap<>();

    public void trackModuleComplete(long requestId, String dir, long lastDen, @Nullable BufferedWriter writer) {
        if (requestId <= 0) return;
        if (dir != null) suiteProgress.remove(requestId + "\n" + dir);
        if (dir != null) sessions.moduleCompleted(requestId, dir);
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
    public void emitWorkspaceProgress(long requestId, @Nullable BufferedWriter writer, boolean force) {
        emitWorkspaceProgress(requestId, writer, force, false);
    }

    public void emitWorkspaceProgress(
            long requestId, @Nullable BufferedWriter writer, boolean force, boolean dashboardOnly) {
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
            // Held percent on both surfaces: SSE sends the line the wire does.
            double pct = heldPct;
            long heldMs = tracker.onHold() ? MemoryHolds.heldMillis(requestId) : -1;
            String line = ProtoEvents.workspaceProgress(
                    dir, num, den, snap.phase(), snap.modulesComplete(), snap.modulesTotal(), rem, r0, pct, heldMs);
            if (writer != null) WireWriter.sendQuiet(writer, line);
            if (eventsWanted()) {
                publishEvent(
                        EngineProtocol.WORKSPACE_PROGRESS,
                        withProgress(SseFrame.of(EngineProtocol.WORKSPACE_PROGRESS, requestId, line), requestId),
                        dashboardOnly);
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
    /**
     * A job admitted to the memory queue, not yet to the heap: the dashboard paints a queued card
     * that {@code request-start} turns live. {@code ahead} is its place in line.
     */
    public void publishRequestQueued(long requestId, String kind, String dir, int ahead) {
        if (!eventsWanted()) return;
        publishEvent(
                "request-queued",
                JsonOut.object()
                        .put("schema", 1)
                        .put("type", "request-queued")
                        .put("jid", requestId)
                        .put("kind", kind)
                        .put("dir", dir)
                        .put("projectId", ProjectIds.idOf(dir))
                        .put("ahead", ahead)
                        .put("reason", "memory")
                        .put("activeBuildPlans", activePlans.get()));
    }

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
            var project =
                    JkBuildParser.parse(ManifestPaths.manifestIn(Path.of(dir))).project();
            coord = project.group() + ":" + project.name();
        } catch (Exception e) {
            // unparseable/missing jk.toml — the dashboard falls back to showing the dir
            Log.debug("publishRequestStart: unparseable/missing jk.toml", e);
        }
        var payload = JsonOut.object()
                .put("schema", 1)
                .put("type", "request-start")
                .put("jid", requestId)
                .put("kind", kind)
                .put("dir", dir)
                .put("coord", coord)
                .put("projectId", ProjectIds.idOf(dir));
        // The origin rides the first frame so a card shows who asked from its first paint.
        var hold = inFlight.get(requestId).orElse(null);
        if (hold != null && hold.trigger() != null) payload = payload.put("trigger", hold.trigger());
        if (hold != null && hold.session() != null) payload = payload.put("session", hold.session());
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
     * The SSE {@code error} event body for one diagnostic: its {@code error-line} record under the
     * name the dashboard has always known it by.
     */
    static JsonOut errorEventJson(long requestId, String dir, BuildPlanResult.Diagnostic d) {
        return SseFrame.of(
                EventCatalog.JSONL_ERROR,
                requestId,
                new ErrorLineEvent(
                                dir,
                                d.step(),
                                d.code(),
                                orEmpty(d.message()),
                                orEmpty(d.test()),
                                orEmpty(d.module()),
                                d.suite(),
                                orEmpty(d.engine()),
                                orEmpty(d.className()),
                                orEmpty(d.method()),
                                orEmpty(d.exceptionClass()),
                                orEmpty(d.file()),
                                d.line(),
                                d.snippetStart(),
                                d.worker(),
                                d.snippet() == null ? List.of() : d.snippet(),
                                orEmpty(d.stack()),
                                0,
                                0)
                        .encode());
    }

    /** A single request-level failure line (bad jk.toml, workspace orchestration error, …). */
    public void publishRequestError(long requestId, @Nullable String dir, String message) {
        if (!eventsWanted() || message == null || message.isBlank()) return;
        String record = new ErrorLineEvent(
                        dir == null ? "" : dir,
                        "request",
                        "error",
                        message,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        0,
                        0,
                        0,
                        List.of(),
                        "",
                        0,
                        0)
                .encode();
        publishEvent(
                EventCatalog.JSONL_ERROR,
                withProgress(SseFrame.of(EventCatalog.JSONL_ERROR, requestId, record), requestId));
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    /**
     * A plan's progress as the dashboard's {@code progress} frame. Aggregate % on SSE/MCP: workspace
     * builds use {@link cc.jumpkick.wire.runtime.WorkspaceProgressTracker}; single-plan jobs
     * (build/test/compile) have no tracker — the plan <em>is</em> the whole request, so feed
     * last-progress on the session from these counts. The frame's {@code progress} is the run's.
     */
    @Override
    public void publishPlanProgress(long requestId, String dir, long numerator, long denominator, String record) {
        if (requestId > 0 && trackerOrNull(requestId) == null && denominator > 0) {
            double p = WorkspaceProgressTracker.percentOf(numerator, denominator);
            if (!Double.isNaN(p)) setLastProgress(requestId, p);
        }
        if (!eventsWanted()) return;
        publishEvent(
                EngineProtocol.PROGRESS,
                withProgress(SseFrame.of(EngineProtocol.PROGRESS, requestId, record), requestId));
    }

    @Override
    public void publishStepStart(long requestId, String dir, String step, String phase, String record) {
        acc.stepStart(requestId, dir, step, phase);
        publishRecord(requestId, record);
    }

    @Override
    public void publishRecord(long requestId, String record) {
        if (!eventsWanted()) return;
        String type = Objects.requireNonNull(EngineProtocol.typeOf(record), record);
        publishEvent(type, withProgress(SseFrame.of(type, requestId, record), requestId));
    }

    /**
     * The build's total weight (Σ module weights) — seeds the dashboard bar's denominator up front so
     * the aggregate never jumps backward as later modules start. Mirrors what {@code onPlan} already
     * sends the CLI as per-module {@code plan-module} weights. Weight is the same abstract unit the
     * CLI bar uses ({@code EffortWeights}, ≈150 ms/unit); the dashboard only needs the ratio.
     */
    @Override
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
     * Guard for event publishers: build the payload only when someone is listening. Split from
     * {@link #publishEvent} so hot listener callbacks (per-plan, per-module) pay one boolean check,
     * not a {@code JsonOut} allocation, when no dashboard is open.
     */
    @Override
    public boolean eventsWanted() {
        return events != null && events.hasSubscribers();
    }
}
