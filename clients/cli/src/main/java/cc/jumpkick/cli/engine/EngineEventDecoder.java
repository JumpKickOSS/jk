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
import cc.jumpkick.wire.protocol.ErrorLineEvent;
import cc.jumpkick.wire.protocol.EtaEvent;
import cc.jumpkick.wire.protocol.LabelEvent;
import cc.jumpkick.wire.protocol.ModuleFinishEvent;
import cc.jumpkick.wire.protocol.OutputEvent;
import cc.jumpkick.wire.protocol.PlanDiagnosticEvent;
import cc.jumpkick.wire.protocol.PlanFinishEvent;
import cc.jumpkick.wire.protocol.PlanFinishOutcomeEvent;
import cc.jumpkick.wire.protocol.PlanModuleEvent;
import cc.jumpkick.wire.protocol.PlanStartEvent;
import cc.jumpkick.wire.protocol.PlanTaskEvent;
import cc.jumpkick.wire.protocol.PreflightEvent;
import cc.jumpkick.wire.protocol.ProgressEvent;
import cc.jumpkick.wire.protocol.TaskFinishEvent;
import cc.jumpkick.wire.protocol.TaskStartEvent;
import cc.jumpkick.wire.protocol.TickUpdateEvent;
import cc.jumpkick.wire.protocol.WarnEvent;
import cc.jumpkick.wire.protocol.WorkspaceFinishEvent;
import cc.jumpkick.wire.protocol.WorkspaceProgressEvent;
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
            @Nullable String @Nullable [] buildOutcomeOut)
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
            @Nullable String @Nullable [] buildOutcomeOut,
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
                    case EngineProtocol.PLAN_TASK -> steps.add(taskFromWire(line));
                    case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(steps);
                    case EngineProtocol.BUILDPLAN_FINISH -> {
                        PlanFinishOutcomeEvent e = PlanFinishOutcomeEvent.decode(line);
                        TestSummary counts = e.total() < 0
                                ? null
                                : new TestSummary(e.total(), e.succeeded(), e.failed(), e.skipped(), List.of());
                        if (counts != null && testResultOut != null) testResultOut[0] = counts;
                        if (buildOutcomeOut != null) buildOutcomeOut[0] = e.buildOutcome();
                        // `cancelled` is spliced onto any plan-finish after the fact (withCancelled);
                        // the outcome record does not carry it.
                        boolean cancelled = Jsonl.bool(line, "cancelled", false);
                        BuildPlanResult result = new BuildPlanResult(
                                "test",
                                e.success(),
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
                        PlanModuleEvent e = PlanModuleEvent.decode(line);
                        planByDir.put(dir, new ModuleMeta(e.coord(), e.planName(), e.weight(), e.fullyCached()));
                        pendingPlanDir = dir;
                    }
                    case EngineProtocol.PLAN_TASK -> {
                        ModuleMeta m = planByDir.get(dir != null ? dir : pendingPlanDir);
                        if (m != null) m.steps.add(taskFromWire(line));
                    }
                    case EngineProtocol.PREFLIGHT -> {
                        PreflightEvent e = PreflightEvent.decode(line);
                        listener.onPreflight(e.stage(), e.done(), e.total(), e.label());
                    }
                    case EngineProtocol.WORKSPACE_PROGRESS ->
                        listener.onWorkspaceProgress(snapshotOf(WorkspaceProgressEvent.decode(line)));
                    case EngineProtocol.PLAN_DONE -> listener.onPlan(buildModulePlans(planByDir, cache));
                    case EngineProtocol.ETA ->
                        // Seed / re-seed only (R0). Client locks after execute starts. The line's
                        // `millis` duplicates `remainingMs`; the record carries the one value.
                        listener.onEtaEstimate(EtaEvent.decode(line).remainingMs());
                    case EngineProtocol.MODULE_START -> {
                        ModulePlan plan = buildModulePlan(dir, planByDir.get(dir), cache);
                        BuildPlanListener gl = listener.onModuleStart(plan);
                        planListenersByDir.put(dir, gl != null ? gl : NOOP);
                    }
                    case EngineProtocol.BUILDPLAN_FINISH -> {
                        ModuleMeta meta = planByDir.get(dir);
                        List<BuildPlanResult.Diagnostic> diags = diagnosticsByDir.remove(dir);
                        PlanFinishEvent e = PlanFinishEvent.decode(line);
                        planListenersByDir
                                .getOrDefault(dir, NOOP)
                                .planFinish(new BuildPlanResult(
                                        meta != null ? meta.planName : dir,
                                        e.success(),
                                        Duration.ZERO,
                                        List.of(),
                                        List.of(),
                                        diags != null ? diags : List.of(),
                                        e.cancelled(),
                                        e.cancelled()));
                    }
                    case EngineProtocol.MODULE_FINISH -> {
                        ModuleOutcome outcome = readOutcome(dir, line);
                        outcomes.add(outcome);
                        listener.onModuleFinish(outcome);
                    }
                    case EngineProtocol.WORKSPACE_FINISH -> {
                        WorkspaceFinishEvent e = WorkspaceFinishEvent.decode(line);
                        // An absent exit code reads as failure here, where the record reads 0.
                        int exitCode = Jsonl.has(line, "exitCode") ? e.exitCode() : 1;
                        WorkspaceResult result = new WorkspaceResult(
                                e.success(), exitCode, List.copyOf(outcomes), e.errors(), e.cancelled());
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
            case EngineProtocol.BUILDPLAN_START -> {
                PlanStartEvent e = PlanStartEvent.decode(line);
                listener.planStart(new BuildPlanView(
                        e.planName(),
                        e.numerator(),
                        e.denominator(),
                        e.tasksTotal(),
                        e.tasksComplete(),
                        e.cancelled()));
            }
            case EngineProtocol.TASK_START -> {
                TaskStartEvent e = TaskStartEvent.decode(line);
                listener.stepStart(e.task(), wireGroup(e.stage()), e.ticks());
            }
            case EngineProtocol.PROGRESS -> {
                ProgressEvent e = ProgressEvent.decode(line);
                // A progress line names no plan: the view's plan name is whatever the line says,
                // which is nothing — read as before, so the value the renderers see is unchanged.
                listener.progress(
                        e.task(),
                        e.delta(),
                        planView(
                                line,
                                e.numerator(),
                                e.denominator(),
                                e.tasksTotal(),
                                e.tasksComplete(),
                                e.cancelled()));
            }
            case EngineProtocol.TICK_UPDATE -> {
                TickUpdateEvent e = TickUpdateEvent.decode(line);
                listener.tickUpdate(
                        e.task(),
                        e.delta(),
                        planView(
                                line,
                                e.numerator(),
                                e.denominator(),
                                e.tasksTotal(),
                                e.tasksComplete(),
                                e.cancelled()));
            }
            case EngineProtocol.LABEL -> {
                LabelEvent e = LabelEvent.decode(line);
                listener.label(e.task(), e.label());
            }
            case EngineProtocol.OUTPUT -> {
                OutputEvent e = OutputEvent.decode(line);
                listener.output(e.task(), e.line());
            }
            case EngineProtocol.WARN -> {
                WarnEvent e = WarnEvent.decode(line);
                listener.warn(e.task(), e.code(), e.message());
            }
            case EngineProtocol.ERROR_LINE -> dispatchError(listener, line);
            case EngineProtocol.TASK_FINISH -> {
                TaskFinishEvent e = TaskFinishEvent.decode(line);
                listener.stepFinish(
                        e.task(),
                        wireGroup(e.stage()),
                        TaskStatus.valueOf(e.status()),
                        Duration.ofMillis(e.millis()),
                        Duration.ofMillis(e.waitMillis()));
            }
            default -> {
                /* forward-compatible no-op */
            }
        }
    }

    /** Dispatch a wire error line to the plan listener (enriched test-failure when fields present). */
    private static void dispatchError(BuildPlanListener listener, String line) {
        ErrorLineEvent e = ErrorLineEvent.decode(line);
        TestFailureInfo failure = testFailureOf(
                line,
                e.code(),
                e.message(),
                e.test(),
                e.module(),
                e.engine(),
                e.testClass(),
                e.method(),
                e.exceptionClass(),
                e.stack(),
                e.file(),
                e.worker(),
                e.line(),
                e.snippetStart(),
                e.snippet());
        if (failure != null) {
            listener.error(e.task(), e.code(), e.message(), failure);
        } else {
            listener.error(e.task(), e.code(), e.message(), e.test(), e.exceptionClass());
        }
    }

    private static BuildPlanResult.Diagnostic diagnosticFromWire(String line) {
        PlanDiagnosticEvent e = PlanDiagnosticEvent.decode(line);
        TestFailureInfo f = testFailureOf(
                line,
                e.code(),
                e.message(),
                e.test(),
                e.module(),
                e.engine(),
                e.testClass(),
                e.method(),
                e.exceptionClass(),
                e.stack(),
                e.file(),
                e.worker(),
                e.line(),
                e.snippetStart(),
                e.snippet());
        if (f != null) {
            return new BuildPlanResult.Diagnostic(e.task(), e.code(), e.message(), f);
        }
        return new BuildPlanResult.Diagnostic(e.task(), e.code(), e.message(), e.test(), e.exceptionClass());
    }

    /**
     * The enriched test-failure identity of an error/diagnostic line, from the decoded fields. Null
     * when no structured test identity is present (plain javac/resolve errors). The record reads
     * absent strings as empty; the legacy nested {@code throwable.stack} — written by no current
     * engine — is the one field still read off the raw line.
     */
    private static @Nullable TestFailureInfo testFailureOf(
            String line,
            String code,
            String message,
            String test,
            String module,
            String engine,
            String className,
            String method,
            String exceptionClass,
            String stack,
            String file,
            int worker,
            int lineNo,
            int snippetStart,
            List<String> snippet) {
        if (method.isEmpty()) method = test;
        if (stack.isEmpty()) {
            String th = Jsonl.nested(line, "throwable");
            if (th != null) stack = nz(Jsonl.str(th, "stack"));
        }
        boolean anyIdentity = !module.isEmpty()
                || !engine.isEmpty()
                || !className.isEmpty()
                || !method.isEmpty()
                || !stack.isEmpty()
                || !file.isEmpty();
        // No identity at all: only a line the engine explicitly coded as a test failure counts,
        // and even then it must carry an exception class or there is nothing to render.
        if (!anyIdentity && !"test-failure".equals(code)) return null;
        if (!anyIdentity && exceptionClass.isEmpty()) return null;
        return new TestFailureInfo(
                module,
                engine,
                className,
                method,
                exceptionClass,
                message,
                stack,
                worker,
                file,
                lineNo,
                snippetStart,
                snippet);
    }

    /** The engine's strategy percent when it sent one (clock-based once R0 is set), else num/den. */
    private static WorkspaceProgressTracker.Snapshot snapshotOf(WorkspaceProgressEvent e) {
        double pct = e.progressPercent();
        if (Double.isNaN(pct) && e.denominator() > 0) {
            pct = WorkspaceProgressTracker.percentOf(e.numerator(), e.denominator());
        }
        // Residual remainingMs rides the snapshot; AggregateContext re-anchors the countdown +
        // adaptive bar (the seed path stays on eta events only).
        return new WorkspaceProgressTracker.Snapshot(
                e.numerator(),
                e.denominator(),
                pct,
                e.phase() == null ? "" : e.phase(),
                e.modulesComplete(),
                e.modulesTotal(),
                e.remainingMs(),
                e.r0Ms());
    }

    private static ModuleOutcome readOutcome(String dir, String line) {
        ModuleFinishEvent e = ModuleFinishEvent.decode(line);
        // Older engines that omit the fields: didWork fails open ("built") and a missing exit code is
        // a failure — the record reads both as their zero.
        boolean didWork = Jsonl.has(line, "didWork") ? e.didWork() : true;
        int exitCode = Jsonl.has(line, "exitCode") ? e.exitCode() : 1;
        ModuleOutcome outcome =
                new ModuleOutcome(e.coord(), Path.of(dir), e.success(), exitCode, e.millis(), didWork, e.cancelled());
        return e.image() == null ? outcome : outcome.withImage(e.image());
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

    /** The inert client-side task a {@code plan-task} line describes: name, label and group only. */
    static Task taskFromWire(String line) {
        PlanTaskEvent e = PlanTaskEvent.decode(line);
        return Task.builder(e.name())
                .label(e.label())
                .group(wireGroup(e.stage()))
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

    private static BuildPlanView planView(
            String line, long numerator, long denominator, int tasksTotal, int tasksComplete, boolean cancelled) {
        return new BuildPlanView(
                Jsonl.str(line, "planName"), numerator, denominator, tasksTotal, tasksComplete, cancelled);
    }

    static @Nullable String wireGroup(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
