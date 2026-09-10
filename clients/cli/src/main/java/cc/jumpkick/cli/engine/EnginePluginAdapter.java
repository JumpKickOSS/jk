// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.ProvisionResultEvent;
import cc.jumpkick.wire.runtime.HostedEvents;
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
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

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
     * EngineJobs.runTest}) → plan events → terminal plan-finish. {@code onEvent}
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
     * {@code EngineJobs.runTest} settles {@code testResultOut} first.
     */
    static HostedFinish stream(
            EnginePaths.Paths paths,
            String requestLine,
            String planName,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            BiConsumer<String, String> onEvent,
            @Nullable Consumer<String> preFinish)
            throws IOException {
        EngineClient.ensureRunning(paths, JkVersion.VERSION);

        try (SocketChannel ch = EngineWire.connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineWire.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-plan request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned plans are byte-identical.
            var session = SessionContext.current();
            send(
                    writer,
                    ProtoSession.withToolchain(
                            ProtoSession.withSession(
                                    requestLine,
                                    session.variant(),
                                    session.clientEnv(),
                                    session.jvm(),
                                    session.config().rebuildOr(false)),
                            SessionContext.current().jdkSpec(),
                            SessionContext.current().graalSpec(),
                            SessionContext.current().graalHome() == null
                                    ? null
                                    : SessionContext.current().graalHome().toString()));

            return WireStream.pumpJob(reader, ch, hostedDecoder(planName, listenerFactory, onEvent, preFinish));
        }
    }

    /**
     * The single-plan reader every hosted verb shares. It ends on the plan's own terminal or on a
     * workspace terminal — the engine answers a member's compile in the workspace vocabulary — and
     * refuses on the engine's error frame.
     */
    static WireStream.Decoder<HostedFinish> hostedDecoder(
            String planName,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            BiConsumer<String, String> onEvent,
            @Nullable Consumer<String> preFinish) {
        return new WireStream.Decoder<>() {
            private final List<Task> steps = new ArrayList<>();
            private final List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
            private @Nullable BuildPlanListener listener;

            @Override
            public @Nullable HostedFinish onLine(String type, String line) throws IOException {
                switch (type) {
                    case EngineProtocol.PLAN_TASK -> steps.add(EngineEventDecoder.taskFromWire(line));
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
                        return finish(result, line);
                    }
                    case EngineProtocol.WORKSPACE_FINISH -> {
                        return finish(
                                EngineEventDecoder.planResultOf(line, planName, Duration.ZERO, diagnostics), line);
                    }
                    case EngineProtocol.ERROR ->
                        throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                    default -> EngineEventDecoder.dispatch(type, line, listener, diagnostics::add);
                }
                return null;
            }

            private HostedFinish finish(BuildPlanResult result, String line) {
                if (preFinish != null) preFinish.accept(line);
                if (listener != null) listener.planFinish(result);
                return new HostedFinish(result, line);
            }
        };
    }

    /**
     * Send a one-shot {@link EngineProtocol#PROVISION_REQUEST} and wait for its terminal — no plan
     * events stream (see the protocol docs); the engine may still take a while (a distribution
     * download), which is fine on this blocking read.
     */
    static HostedEvents.Provision provision(EnginePaths.Paths paths, String requestLine) throws IOException {
        EngineClient.ensureRunning(paths, JkVersion.VERSION);

        try (SocketChannel ch = EngineWire.connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineWire.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-plan request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned plans are byte-identical.
            var session = SessionContext.current();
            send(
                    writer,
                    ProtoSession.withToolchain(
                            ProtoSession.withSession(
                                    requestLine,
                                    session.variant(),
                                    session.clientEnv(),
                                    session.jvm(),
                                    session.config().rebuildOr(false)),
                            SessionContext.current().jdkSpec(),
                            SessionContext.current().graalSpec(),
                            SessionContext.current().graalHome() == null
                                    ? null
                                    : SessionContext.current().graalHome().toString()));

            return WireStream.pumpJob(reader, ch, (type, line) -> switch (type) {
                case EngineProtocol.PROVISION_RESULT -> {
                    ProvisionResultEvent e = ProvisionResultEvent.decode(line);
                    // An absent exit code is a failure here, where the record reads 0.
                    yield new HostedEvents.Provision(
                            e.bin(), e.version(), e.source(), e.error(), Jsonl.has(line, "exit") ? e.exit() : 1);
                }
                case EngineProtocol.ERROR -> throw EngineWireException.fromJsonLine(line, "jk engine: run failed: ");
                // Anything else is a forward-compatible no-op: ask for the next line.
                default -> null;
            });
        }
    }

    private static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }
}
