// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Turns the engine's build event stream back into {@link BuildPlanListener} /
 * {@link WorkspaceBuildListener} calls — the client half of the wire contract, and the only place
 * that knows what a {@code task-finish} line means.
 *
 * <p>Many pump loops, one table. {@link #streamSingleBuildPlanEvents} replays one plan ({@code jk
 * test}, {@code jk build} on a leaf project, {@code jk install}); {@link #streamWorkspaceEvents}
 * replays a workspace's many plans, keyed by module dir; {@link EnginePluginAdapter} and {@link
 * EngineResolveAdapter} pump the hosted plugin/resolver plans. The ten events that are the same in
 * all of them — everything from {@code plan-start} to {@code task-finish} — are decoded by {@link
 * #dispatch}, once. Private copies of this table are how a finished step came to render {@code
 * Duration.ZERO} and a test failure lost its file/line/snippet enrichment.
 *
 * <p>Decode order IS the contract: a {@code job-start} decoded after its first {@code task-start}
 * renders a module that never started, so the arms below stay in the order the engine writes them.
 */
final class EngineEventDecoder {

    private EngineEventDecoder() {}

    /** One module's identity/sizing, accumulated from the {@code plan-module}/{@code plan-step} burst. */
    private static final class ModuleMeta {
        final String coord;
        final String planName;
        final int weight;
        final boolean fullyCached;
        final List<Task> steps = new ArrayList<>();

        ModuleMeta(String coord, String planName, int weight, boolean fullyCached) {
            this.coord = coord;
            this.planName = planName;
            this.weight = weight;
            this.fullyCached = fullyCached;
        }
    }

    /** Stands in for a module whose {@code module-start} never arrived, so decode never NPEs. */
    private static final BuildPlanListener NOOP = new BuildPlanListener() {};

    // Package-visible for tests (ActiveJobs lifecycle, cancel terminals).
    static BuildPlanResult streamSingleBuildPlanEvents(
            BufferedReader reader,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary @Nullable [] testResultOut,
            String @Nullable [] buildOutcomeOut)
            throws IOException {
        return streamSingleBuildPlanEvents(reader, listenerFactory, testResultOut, buildOutcomeOut, null);
    }

    /**
     * Replay one plan's stream. {@code listenerFactory} is invoked at {@code plan-done}, once the
     * step list is known — the wire has no real {@code BuildPlan} to ask, so the steps arrive as
     * their own small burst first. {@code testResultOut}/{@code buildOutcomeOut} settle before the
     * terminal reaches the listener, mirroring the in-process path where {@code plan.get(...)} is
     * already populated by the time the console listener's {@code planFinish} fires.
     */
    static BuildPlanResult streamSingleBuildPlanEvents(
            BufferedReader reader,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary @Nullable [] testResultOut,
            String @Nullable [] buildOutcomeOut,
            @Nullable SocketChannel ch)
            throws IOException {
        // The wire carries no duration; the summary's "took …" is this client-side
        // wall clock over the whole stream (spawn latency excluded — ensureRunning
        // already returned before the request was written).
        long startNanos = System.nanoTime();

        return WireStream.pumpJob(reader, ch, new WireStream.Decoder<BuildPlanResult>() {
            private final List<Task> steps = new ArrayList<>();
            private final List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
            private @Nullable BuildPlanListener listener;

            @Override
            public @Nullable BuildPlanResult onLine(String type, String line) throws IOException {
                switch (type) {
                    case EngineProtocol.PLAN_TASK -> steps.add(readTask(line));
                    case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(steps);
                    case EngineProtocol.BUILDPLAN_FINISH -> {
                        TestSummary counts = TestSummary.readCounts(line);
                        if (counts != null && testResultOut != null) testResultOut[0] = counts;
                        if (buildOutcomeOut != null) buildOutcomeOut[0] = Jsonl.str(line, "buildOutcome");
                        boolean cancelled = Jsonl.bool(line, "cancelled", false);
                        BuildPlanResult result = new BuildPlanResult(
                                "test",
                                Jsonl.bool(line, "success", false),
                                Duration.ofNanos(System.nanoTime() - startNanos),
                                List.of(),
                                List.of(),
                                diagnostics,
                                cancelled,
                                cancelled);
                        // A remote cancel injects this terminal from another thread — it can land
                        // before plan-done ever created the listener.
                        if (listener != null) listener.planFinish(result);
                        return result;
                    }
                    case EngineProtocol.ERROR ->
                        throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                    default -> dispatch(type, line, listener, diagnostics::add);
                }
                return null;
            }
        });
    }

    /**
     * Replay a workspace's stream: a {@code plan-module}/{@code plan-step} burst per module, then
     * interleaved per-module plan events keyed by {@code dir}, then the workspace terminal.
     * Client-side {@link ModulePlan}s carry inert steps — name and step list only, which is all the
     * renderers read.
     */
    static WorkspaceResult streamWorkspaceEvents(
            BufferedReader reader, WorkspaceBuildListener listener, Path cache, @Nullable SocketChannel ch)
            throws IOException {
        Map<String, ModuleMeta> planByDir = new LinkedHashMap<>();
        Map<String, BuildPlanListener> planListenersByDir = new LinkedHashMap<>();
        Map<String, List<BuildPlanResult.Diagnostic>> diagnosticsByDir = new LinkedHashMap<>();
        List<ModuleOutcome> outcomes = new ArrayList<>();

        return WireStream.pumpJob(reader, ch, new WireStream.Decoder<WorkspaceResult>() {
            /** The dir most recently opened by {@code plan-module}, for its {@code plan-step} lines. */
            private @Nullable String pendingPlanDir;

            @Override
            public @Nullable WorkspaceResult onLine(String type, String line) throws IOException {
                String dir = Jsonl.str(line, "dir");
                switch (type) {
                    case EngineProtocol.PLAN_MODULE -> {
                        planByDir.put(
                                dir,
                                new ModuleMeta(
                                        Jsonl.str(line, "coord"),
                                        Jsonl.str(line, "planName"),
                                        Jsonl.intValue(line, "weight", 0),
                                        Jsonl.bool(line, "fullyCached", false)));
                        pendingPlanDir = dir;
                    }
                    case EngineProtocol.PLAN_TASK -> {
                        ModuleMeta m = planByDir.get(dir != null ? dir : pendingPlanDir);
                        if (m != null) m.steps.add(readTask(line));
                    }
                    case EngineProtocol.PREFLIGHT ->
                        listener.onPreflight(
                                Jsonl.str(line, "stage"),
                                Jsonl.intValue(line, "done", 0),
                                Jsonl.intValue(line, "total", 0),
                                Jsonl.str(line, "label"));
                    case EngineProtocol.WORKSPACE_PROGRESS -> listener.onWorkspaceProgress(readProgress(line));
                    case EngineProtocol.PLAN_DONE -> listener.onPlan(buildModulePlans(planByDir, cache));
                    case EngineProtocol.ETA -> {
                        // Seed / re-seed only (R0). Client locks after execute starts.
                        long rem = Jsonl.longValue(line, "remainingMs", -1);
                        if (rem < 0) rem = Jsonl.longValue(line, "millis", 0);
                        listener.onEtaEstimate(rem);
                    }
                    case EngineProtocol.MODULE_START -> {
                        ModulePlan plan = buildModulePlan(dir, planByDir.get(dir), cache);
                        BuildPlanListener gl = listener.onModuleStart(plan);
                        planListenersByDir.put(dir, gl != null ? gl : NOOP);
                    }
                    case EngineProtocol.BUILDPLAN_FINISH -> {
                        ModuleMeta meta = planByDir.get(dir);
                        List<BuildPlanResult.Diagnostic> diags = diagnosticsByDir.remove(dir);
                        boolean cancelled = Jsonl.bool(line, "cancelled", false);
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .planFinish(new BuildPlanResult(
                                        meta != null ? meta.planName : dir,
                                        Jsonl.bool(line, "success", false),
                                        Duration.ZERO,
                                        List.of(),
                                        List.of(),
                                        diags != null ? diags : List.of(),
                                        cancelled,
                                        cancelled));
                    }
                    case EngineProtocol.MODULE_FINISH -> {
                        ModuleOutcome outcome = readOutcome(dir, line);
                        outcomes.add(outcome);
                        listener.onModuleFinish(outcome);
                    }
                    case EngineProtocol.WORKSPACE_FINISH -> {
                        WorkspaceResult result = new WorkspaceResult(
                                Jsonl.bool(line, "success", false),
                                Jsonl.intValue(line, "exitCode", 1),
                                List.copyOf(outcomes),
                                Jsonl.strArray(line, "errors"),
                                Jsonl.bool(line, "cancelled", false));
                        listener.onWorkspaceFinish(result);
                        return result;
                    }
                    case EngineProtocol.ERROR -> {
                        WorkspaceResult failed = readWorkspaceError(line);
                        listener.onWorkspaceFinish(failed);
                        return failed;
                    }
                    default ->
                        dispatch(type, line, planListenersByDir.getOrDefault(dir, NOOP), d -> diagnosticsByDir
                                .computeIfAbsent(dir, k -> new ArrayList<>())
                                .add(d));
                }
                return null;
            }
        });
    }

    /**
     * Replay one standard plan event into {@code listener}, handing {@code plan-diagnostic}s to
     * {@code onDiagnostic} instead. This is the whole per-plan event vocabulary in one table: the
     * single-plan and workspace loops above differ only in <em>which</em> listener a line lands on,
     * never in what the line means. Unknown types are forward-compatible no-ops.
     *
     * <p>A null listener is the pre-{@code plan-done} window, where the factory has not run yet: a
     * diagnostic still matters (it is carried on the terminal result), everything else needs a
     * listener that does not exist.
     */
    static void dispatch(
            String type,
            String line,
            @Nullable BuildPlanListener listener,
            Consumer<BuildPlanResult.Diagnostic> onDiagnostic) {
        if (EngineProtocol.BUILDPLAN_DIAGNOSTIC.equals(type)) {
            onDiagnostic.accept(diagnosticFromWire(line));
            return;
        }
        if (listener == null) return;
        switch (type) {
            case EngineProtocol.BUILDPLAN_START -> listener.planStart(readBuildPlanView(line));
            case EngineProtocol.TASK_START ->
                listener.stepStart(
                        Jsonl.str(line, "task"), wireGroup(Jsonl.str(line, "stage")), Jsonl.intValue(line, "ticks", 0));
            case EngineProtocol.PROGRESS ->
                listener.progress(Jsonl.str(line, "task"), Jsonl.intValue(line, "delta", 0), readBuildPlanView(line));
            case EngineProtocol.TICK_UPDATE ->
                listener.tickUpdate(Jsonl.str(line, "task"), Jsonl.intValue(line, "delta", 0), readBuildPlanView(line));
            case EngineProtocol.LABEL -> listener.label(Jsonl.str(line, "task"), Jsonl.str(line, "label"));
            case EngineProtocol.OUTPUT -> listener.output(Jsonl.str(line, "task"), Jsonl.str(line, "line"));
            case EngineProtocol.WARN ->
                listener.warn(Jsonl.str(line, "task"), Jsonl.str(line, "code"), Jsonl.str(line, "message"));
            case EngineProtocol.ERROR_LINE -> dispatchError(listener, line);
            case EngineProtocol.TASK_FINISH ->
                listener.stepFinish(
                        Jsonl.str(line, "task"),
                        wireGroup(Jsonl.str(line, "stage")),
                        TaskStatus.valueOf(Jsonl.str(line, "status")),
                        Duration.ofMillis(Jsonl.longValue(line, "millis", 0)),
                        Duration.ofMillis(Jsonl.longValue(line, "waitMillis", 0)));
            default -> {
                /* forward-compatible no-op */
            }
        }
    }

    /** Dispatch a wire error line to the plan listener (enriched test-failure when fields present). */
    private static void dispatchError(BuildPlanListener listener, String line) {
        TestFailureInfo failure = testFailureFromWire(line);
        String task = Jsonl.str(line, "task");
        String code = Jsonl.str(line, "code");
        String message = Jsonl.str(line, "message");
        if (failure != null) {
            listener.error(task, code, message, failure);
        } else {
            listener.error(task, code, message, Jsonl.str(line, "test"), Jsonl.str(line, "exceptionClass"));
        }
    }

    private static BuildPlanResult.Diagnostic diagnosticFromWire(String line) {
        TestFailureInfo f = testFailureFromWire(line);
        if (f != null) {
            return new BuildPlanResult.Diagnostic(
                    Jsonl.str(line, "task"), Jsonl.str(line, "code"), Jsonl.str(line, "message"), f);
        }
        return new BuildPlanResult.Diagnostic(
                Jsonl.str(line, "task"),
                Jsonl.str(line, "code"),
                Jsonl.str(line, "message"),
                Jsonl.str(line, "test"),
                Jsonl.str(line, "exceptionClass"));
    }

    /**
     * Parse enriched test-failure fields from an error/diagnostic wire line. Returns null when no
     * structured test identity is present (plain javac/resolve errors).
     */
    private static @Nullable TestFailureInfo testFailureFromWire(String line) {
        String module = nz(Jsonl.topStr(line, "module"));
        String engine = nz(Jsonl.topStr(line, "engine"));
        String className = nz(Jsonl.topStr(line, EngineProtocol.TEST_CLASS_FIELD));
        String method = nz(Jsonl.topStr(line, "method"));
        if (method.isEmpty()) method = nz(Jsonl.topStr(line, "test"));
        String exceptionClass = nz(Jsonl.str(line, "exceptionClass"));
        String stack = nz(Jsonl.str(line, "stack"));
        if (stack.isEmpty()) {
            String th = Jsonl.nested(line, "throwable");
            if (th != null) stack = nz(Jsonl.str(th, "stack"));
        }
        String file = nz(Jsonl.str(line, "file"));
        boolean anyIdentity = !module.isEmpty()
                || !engine.isEmpty()
                || !className.isEmpty()
                || !method.isEmpty()
                || !stack.isEmpty()
                || !file.isEmpty();
        // No identity at all: only a line the engine explicitly coded as a test failure counts,
        // and even then it must carry an exception class or there is nothing to render.
        if (!anyIdentity && !"test-failure".equals(Jsonl.str(line, "code"))) return null;
        if (!anyIdentity && exceptionClass.isEmpty()) return null;
        int worker = Jsonl.intValue(line, "worker", 0);
        return new TestFailureInfo(
                module,
                engine,
                className,
                method,
                exceptionClass,
                nz(Jsonl.str(line, "message")),
                stack,
                worker,
                file,
                Jsonl.intValue(line, "line", 0),
                Jsonl.intValue(line, "snippetStart", 0),
                Jsonl.strArray(line, "snippet"));
    }

    /** The engine's strategy percent when it sent one (clock-based once R0 is set), else num/den. */
    private static WorkspaceProgressTracker.Snapshot readProgress(String line) {
        long num = Jsonl.longValue(line, "numerator", 0);
        long den = Jsonl.longValue(line, "denominator", 0);
        double pct = Jsonl.has(line, "progress") ? Jsonl.doubleValue(line, "progress", Double.NaN) : Double.NaN;
        if (Double.isNaN(pct) && den > 0) pct = WorkspaceProgressTracker.percentOf(num, den);
        String phase = Jsonl.str(line, "phase");
        // Residual remainingMs rides the snapshot; AggregateContext re-anchors the countdown +
        // adaptive bar (the seed path stays on eta events only).
        return new WorkspaceProgressTracker.Snapshot(
                num,
                den,
                pct,
                phase == null ? "" : phase,
                Jsonl.intValue(line, "modulesComplete", 0),
                Jsonl.intValue(line, "modulesTotal", 0),
                Jsonl.longValue(line, "remainingMs", -1),
                Jsonl.longValue(line, "R0", 0));
    }

    private static ModuleOutcome readOutcome(String dir, String line) {
        // didWork defaults true for older engines that omit the field (fail-open "built").
        ModuleOutcome outcome = new ModuleOutcome(
                Jsonl.str(line, "coord"),
                Path.of(dir),
                Jsonl.bool(line, "success", false),
                Jsonl.intValue(line, "exitCode", 1),
                Jsonl.longValue(line, "millis", 0),
                Jsonl.bool(line, "didWork", true),
                Jsonl.bool(line, "cancelled", false));
        if (!Jsonl.bool(line, "hasImage", false)) return outcome;
        return outcome.withImage(new ModuleOutcome.Image(
                Jsonl.str(line, "imageRef"),
                Jsonl.str(line, "imageTarball"),
                Jsonl.str(line, "imageName"),
                Jsonl.str(line, "imageVersion"),
                Jsonl.str(line, "imageDaemonExe")));
    }

    /**
     * A workspace-level {@code error} line. {@code request-failed} is the engine reporting a build
     * that failed to start, which is a workspace result with the message on it — not an exception;
     * everything else throws.
     */
    private static WorkspaceResult readWorkspaceError(String line) throws EngineWireException {
        EngineWireException wire = EngineWireException.fromJsonLine(line);
        // Surface the wedge as its message body without engine noise.
        if (wire.alreadyRunning()) {
            String msg = wire.getMessage();
            throw new EngineWireException(wire.code(), msg == null || msg.isBlank() ? "Build is already running" : msg);
        }
        if (EngineProtocol.ERR_REQUEST_FAILED.equals(wire.code())) {
            String msg = wire.getMessage() == null ? "" : wire.getMessage();
            return new WorkspaceResult(false, 2, List.of(), List.of(msg), false);
        }
        throw new EngineWireException(wire.code(), "jk engine: build failed: " + wire.getMessage());
    }

    private static Task readTask(String line) {
        return Task.builder(Jsonl.str(line, "name"))
                .label(Jsonl.str(line, "label"))
                .group(wireGroup(Jsonl.str(line, "stage")))
                .build();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<ModulePlan> buildModulePlans(Map<String, ModuleMeta> planByDir, Path cache) {
        List<ModulePlan> plans = new ArrayList<>(planByDir.size());
        for (Map.Entry<String, ModuleMeta> e : planByDir.entrySet()) {
            plans.add(buildModulePlan(e.getKey(), e.getValue(), cache));
        }
        return plans;
    }

    private static ModulePlan buildModulePlan(String dir, @Nullable ModuleMeta meta, Path cache) {
        // A plan-done for a dir that never announced its module is a wire violation, not a case.
        ModuleMeta m = Objects.requireNonNull(meta, () -> "no module meta for " + dir);
        BuildPlan inertBuildPlan =
                BuildPlan.builder(m.planName).addAllTasks(m.steps).build();
        return ModulePlan.fromWire(Path.of(dir), m.coord, inertBuildPlan, m.weight, m.fullyCached, cache);
    }

    private static BuildPlanView readBuildPlanView(String line) {
        return new BuildPlanView(
                Jsonl.str(line, "planName"),
                Jsonl.longValue(line, "numerator", 0),
                Jsonl.longValue(line, "denominator", 0),
                Jsonl.intValue(line, "tasksTotal", 0),
                Jsonl.intValue(line, "tasksComplete", 0),
                Jsonl.bool(line, "cancelled", false));
    }

    static @Nullable String wireGroup(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
