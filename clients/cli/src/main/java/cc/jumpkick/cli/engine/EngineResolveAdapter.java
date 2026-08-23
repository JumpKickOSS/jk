// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.EngineWireException;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Engine-hosted lock/update/sync: decode wire events into the command's listeners/handlers.
 * Lock/update cascade per module; sync is a single plan. Output is unthemed structured text.
 */
final class EngineResolveAdapter {

    private EngineResolveAdapter() {}

    /**
     * Run {@code jk outdated} against the engine: one synchronous request, one {@code outdated-ack}
     * carrying the {@link cc.jumpkick.engine.protocol.OutdatedReport} back. Read-only — no cascade,
     * no plan stream.
     */
    static cc.jumpkick.engine.protocol.OutdatedReport runOutdated(
            EnginePaths.Paths paths, EngineRequests.OutdatedRequest req) throws IOException {
        return EngineBuildListenerAdapter.request(
                paths,
                ProtoReads.outdatedRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.offline(),
                        req.force()),
                EngineProtocol.OUTDATED_ACK,
                "outdated request",
                cc.jumpkick.engine.protocol.OutdatedReport::decode);
    }

    /** Run {@code jk lock}'s cascade against the engine, driving {@code handler}. */
    static EngineRequests.LockOutcome runLock(
            EnginePaths.Paths paths, EngineRequests.LockRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return streamCascade(
                paths,
                ProtoJobs.lockRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.features(),
                        req.noDefaultFeatures(),
                        req.sources(),
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.offline(),
                        req.force(),
                        req.verbose(),
                        req.conservative()),
                handler,
                "lock");
    }

    /** Run {@code jk update}'s full re-resolve cascade against the engine, driving {@code handler}. */
    static EngineRequests.LockOutcome runUpdate(
            EnginePaths.Paths paths, EngineRequests.UpdateRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return streamCascade(paths, updateRequestLine(req, false, null), handler, "update");
    }

    /**
     * Run {@code jk update --git [<name>]} against the engine. No plan events stream — the engine
     * splices the lock and replies with just the terminal, whose {@code refreshed} count and plain
     * {@code errors} the command renders.
     */
    static EngineRequests.LockOutcome runUpdateGitOnly(
            EnginePaths.Paths paths, EngineRequests.UpdateRequest req, String gitTarget) throws IOException {
        return streamCascade(paths, updateRequestLine(req, true, gitTarget), NOOP_HANDLER, "update");
    }

    private static String updateRequestLine(EngineRequests.UpdateRequest req, boolean gitOnly, String gitTarget) {
        return ProtoJobs.updateRequest(
                req.entryDir().toString(),
                req.cache().toString(),
                req.features(),
                req.noDefaultFeatures(),
                req.repoUrl() != null ? req.repoUrl().toString() : null,
                gitOnly,
                gitTarget,
                req.offline(),
                req.force(),
                req.verbose(),
                req.platform());
    }

    /**
     * Run {@code jk sync}'s single plan against the engine — the same listener-factory contract as
     * {@link EngineBuildListenerAdapter#runTest}. {@code fetchedOut}/{@code upToDateOut} (single-slot
     * holders) are populated from the terminal plan-finish <em>before</em> it reaches the factory's
     * listener, exactly mirroring how the in-process path's counters are already settled by the time
     * the console listener's own {@code planFinish} renders the summary line.
     */
    static BuildPlanResult runSync(
            EnginePaths.Paths paths,
            EngineRequests.SyncRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            long[] fetchedOut,
            long[] upToDateOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            send(
                    writer,
                    ProtoJobs.syncRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.jdksDir() != null ? req.jdksDir().toString() : null,
                            req.repoUrl() != null ? req.repoUrl().toString() : null,
                            req.sources(),
                            req.offline(),
                            req.force(),
                            req.refresh(),
                            req.verbose()));

            List<Task> steps = new ArrayList<>();
            List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
            BuildPlanListener listener = null;

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.PLAN_TASK ->
                        steps.add(Task.builder(Jsonl.str(line, "name"))
                                .label(Jsonl.str(line, "label"))
                                .phase(wireGroup(Jsonl.str(line, "stage")))
                                .build());
                    case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(steps);
                    case EngineProtocol.BUILDPLAN_FINISH -> {
                        if (fetchedOut != null) fetchedOut[0] = Jsonl.longValue(line, "syncFetched", 0);
                        if (upToDateOut != null) upToDateOut[0] = Jsonl.longValue(line, "syncUpToDate", 0);
                        BuildPlanResult result = new BuildPlanResult(
                                "sync",
                                Jsonl.bool(line, "success", false),
                                Duration.ZERO,
                                List.of(),
                                List.of(),
                                diagnostics,
                                false,
                                false);
                        if (listener != null) listener.planFinish(result);
                        return result;
                    }
                    case EngineProtocol.ERROR ->
                        throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                    default -> dispatchBuildPlanEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /** Send a cascade request and replay its stream into {@code handler} until the terminal arrives. */
    private static EngineRequests.LockOutcome streamCascade(
            EnginePaths.Paths paths, String requestLine, EngineRequests.LockHandler handler, String planName)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            send(writer, requestLine);

            // Cascade state: the module currently streaming. Modules are strictly sequential on the
            // wire (the engine locks them one at a time), so one slot suffices.
            String currentDir = null;
            String currentCoord = null;
            List<Task> steps = new ArrayList<>();
            List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
            BuildPlanListener listener = null;

            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    if (type == null) continue;
                    switch (type) {
                        case EngineProtocol.LOCK_MODULE -> {
                            currentDir = Jsonl.str(line, "dir");
                            currentCoord = Jsonl.str(line, "coord");
                            steps = new ArrayList<>();
                            diagnostics = new ArrayList<>();
                            listener = null;
                        }
                        case EngineProtocol.PLAN_TASK ->
                            steps.add(Task.builder(Jsonl.str(line, "name"))
                                    .label(Jsonl.str(line, "label"))
                                    .phase(wireGroup(Jsonl.str(line, "stage")))
                                    .build());
                        case EngineProtocol.PLAN_DONE ->
                            listener = handler.onModuleStart(currentDir, currentCoord, steps);
                        case EngineProtocol.LOCK_PACKAGE ->
                            handler.onPackage(
                                    Jsonl.str(line, "dir"),
                                    Jsonl.str(line, "name"),
                                    Jsonl.str(line, "version"),
                                    Jsonl.intValue(line, "total", -1));
                        case EngineProtocol.BUILDPLAN_FINISH -> {
                            BuildPlanResult result = new BuildPlanResult(
                                    planName,
                                    Jsonl.bool(line, "success", false),
                                    Duration.ZERO,
                                    List.of(),
                                    List.of(),
                                    diagnostics,
                                    false,
                                    false);
                            if (listener != null) listener.planFinish(result);
                            listener = null; // settled — the catch below must not settle it twice
                            handler.onModuleFinish(
                                    currentDir,
                                    result,
                                    new EngineRequests.LockCounts(
                                            Jsonl.longValue(line, "lockPackages", -1),
                                            Jsonl.longValue(line, "lockSources", -1),
                                            Jsonl.longValue(line, "lockPlugins", -1)));
                        }
                        case EngineProtocol.LOCK_FINISH -> {
                            return new EngineRequests.LockOutcome(
                                    Jsonl.bool(line, "success", false),
                                    Jsonl.intValue(line, "exitCode", 1),
                                    Jsonl.strArray(line, "errors"),
                                    Jsonl.intValue(line, "refreshed", -1));
                        }
                        case EngineProtocol.ERROR ->
                            throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                        default -> dispatchBuildPlanEvent(type, line, listener, diagnostics);
                    }
                }
                throw disconnected();
            } catch (IOException | RuntimeException e) {
                // An in-flight module's live region must settle before the error propagates:
                // planFinish dismisses the pinned region and restores the captured System.out,
                // or the failure prints interleaved with a still-animating region and the
                // terminal is left mid-frame with stdout redirected.
                if (listener != null) {
                    try {
                        listener.planFinish(new BuildPlanResult(
                                planName, false, Duration.ZERO, List.of(), List.of(), diagnostics, false, false));
                    } catch (RuntimeException settling) {
                        e.addSuppressed(settling);
                    }
                }
                throw e;
            }
        }
    }

    /**
     * Replay one standard single-plan wire event into {@code listener} (accumulating {@code
     * plan-diagnostic}s aside, like {@link EngineBuildListenerAdapter} does) — the shared tail of
     * both stream loops. Unknown types are forward-compatible no-ops.
     */
    private static void dispatchBuildPlanEvent(
            String type, String line, BuildPlanListener listener, List<BuildPlanResult.Diagnostic> diagnostics) {
        if (listener == null) {
            // plan-diagnostics can still matter pre-listener; everything else needs one.
            if (EngineProtocol.BUILDPLAN_DIAGNOSTIC.equals(type)) {
                diagnostics.add(readDiagnostic(line));
            }
            return;
        }
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
            case EngineProtocol.ERROR_LINE ->
                listener.error(
                        Jsonl.str(line, "task"),
                        Jsonl.str(line, "code"),
                        Jsonl.str(line, "message"),
                        Jsonl.str(line, "test"),
                        Jsonl.str(line, "exceptionClass"));
            case EngineProtocol.BUILDPLAN_DIAGNOSTIC -> diagnostics.add(readDiagnostic(line));
            case EngineProtocol.TASK_FINISH ->
                listener.stepFinish(
                        Jsonl.str(line, "task"),
                        wireGroup(Jsonl.str(line, "stage")),
                        TaskStatus.valueOf(Jsonl.str(line, "status")),
                        Duration.ZERO);
            default -> {
                /* forward-compatible no-op */
            }
        }
    }

    private static BuildPlanResult.Diagnostic readDiagnostic(String line) {
        return new BuildPlanResult.Diagnostic(
                Jsonl.str(line, "task"),
                Jsonl.str(line, "code"),
                Jsonl.str(line, "message"),
                Jsonl.str(line, "test"),
                Jsonl.str(line, "exceptionClass"));
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

    private static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static IOException disconnected() {
        return new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                + "(it may have crashed); run `jk engine status` for details");
    }

    private static final EngineRequests.LockHandler NOOP_HANDLER = (dir, coord, steps) -> new BuildPlanListener() {};

    private static String wireGroup(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
