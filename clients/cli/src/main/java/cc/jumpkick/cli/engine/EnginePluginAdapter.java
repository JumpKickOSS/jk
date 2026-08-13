// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.EngineWireException;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.plugin.protocol.Jsonl;
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
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Drives the hosted worker commands ({@code jk audit} / {@code format} / {@code publish} / {@code
 * image} / {@code import} and {@code jk mvn}/{@code gradle} provisioning) against the engine — the
 * Wave-2 sibling of {@link EngineResolveAdapter}: sends the request, replays the single-plan event
 * stream into the command's listener, hands the command's repeated structured events ({@code
 * audit-finding} / {@code format-file} / {@code import-note}) to a per-command hook, and returns the
 * raw terminal {@code plan-finish} line so the command can decode its variant fields.
 *
 * <p>Per the rendering rule, everything arriving here is plain structured text — the engine never
 * themes output; the command's renderer colorizes client-side.
 */
final class EnginePluginAdapter {

    private EnginePluginAdapter() {}

    /** A hosted single-plan run's outcome: the replayed result plus the raw terminal line to decode. */
    record HostedFinish(BuildPlanResult result, String finishLine) {}

    /**
     * Send {@code requestLine} and replay the single-plan stream: plan-step burst → {@code
     * listenerFactory} (invoked once the step list is known, mirroring {@code
     * EngineBuildListenerAdapter.runTest}) → plan events → terminal plan-finish. {@code onEvent}
     * receives each command-specific structured event as {@code (type, rawLine)}.
     */
    static HostedFinish stream(
            EnginePaths.Paths paths,
            String requestLine,
            String planName,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            BiConsumer<String, String> onEvent)
            throws IOException {
        return stream(paths, requestLine, planName, listenerFactory, onEvent, null);
    }

    /**
     * As {@link #stream(EnginePaths.Paths, String, String, Function, BiConsumer)}, additionally
     * invoking {@code preFinish} with the raw terminal line <em>before</em> the listener's own
     * {@code planFinish} is dispatched — for commands whose console listener renders summary fields
     * (populated from the finish line) from within its {@code planFinish} handler, mirroring how
     * {@code EngineBuildListenerAdapter.runTest} settles {@code testResultOut} first.
     */
    static HostedFinish stream(
            EnginePaths.Paths paths,
            String requestLine,
            String planName,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            BiConsumer<String, String> onEvent,
            java.util.function.Consumer<String> preFinish)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-plan request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned plans are byte-identical.
            var session = cc.jumpkick.config.SessionContext.current();
            send(
                    writer,
                    ProtoSession.withSession(
                            requestLine,
                            session.variant(),
                            session.clientEnv(),
                            session.jvm(),
                            session.config().rebuildOr(false)));

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
                    case EngineProtocol.AUDIT_FINDING,
                            EngineProtocol.FORMAT_FILE,
                            EngineProtocol.IMPORT_NOTE,
                            EngineProtocol.PRUNE_WAIT -> onEvent.accept(type, line);
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
                        if (preFinish != null) preFinish.accept(line);
                        if (listener != null) listener.planFinish(result);
                        return new HostedFinish(result, line);
                    }
                    case EngineProtocol.ERROR ->
                        throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                    default -> dispatchBuildPlanEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /**
     * Send a one-shot {@link EngineProtocol#PROVISION_REQUEST} and wait for its terminal — no plan
     * events stream (see the protocol docs); the worker may still take a while (a distribution
     * download), which is fine on this blocking read.
     */
    static cc.jumpkick.runtime.HostedEvents.Provision provision(EnginePaths.Paths paths, String requestLine)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-plan request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned plans are byte-identical.
            var session = cc.jumpkick.config.SessionContext.current();
            send(
                    writer,
                    ProtoSession.withSession(
                            requestLine,
                            session.variant(),
                            session.clientEnv(),
                            session.jvm(),
                            session.config().rebuildOr(false)));

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.PROVISION_RESULT -> {
                        return new cc.jumpkick.runtime.HostedEvents.Provision(
                                Jsonl.str(line, "bin"),
                                Jsonl.str(line, "version"),
                                Jsonl.str(line, "source"),
                                Jsonl.str(line, "error"),
                                Jsonl.intValue(line, "exit", 1),
                                Jsonl.str(line, "diag"));
                    }
                    case EngineProtocol.ERROR ->
                        throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                    default -> {
                        /* forward-compatible no-op */
                    }
                }
            }
            throw disconnected();
        }
    }

    /**
     * Replay one standard single-plan wire event into {@code listener} (accumulating {@code
     * plan-diagnostic}s aside) — the same shared tail {@link EngineResolveAdapter} keeps for the
     * resolver family. Unknown types are forward-compatible no-ops.
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

    private static String wireGroup(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
