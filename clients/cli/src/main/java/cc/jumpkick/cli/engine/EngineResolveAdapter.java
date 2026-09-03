// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import cc.jumpkick.wire.protocol.AffectedTestsRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.LockRequest;
import cc.jumpkick.wire.protocol.OutdatedReport;
import cc.jumpkick.wire.protocol.OutdatedRequest;
import cc.jumpkick.wire.protocol.SyncRequest;
import cc.jumpkick.wire.protocol.UpdateRequest;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Engine-hosted lock/update/sync: decode wire events into the command's listeners/handlers.
 * Lock/update cascade per module; sync is a single plan. Output is unthemed structured text.
 */
final class EngineResolveAdapter {

    private EngineResolveAdapter() {}

    /**
     * Ranked tests ({@code jk test --affected} / {@code --affected-since}): one synchronous request,
     * one {@code affected-tests-ack}. Read-only — no compile, no test run.
     */
    static AffectedTestsReport runAffectedTests(
            EnginePaths.Paths paths, Path dir, TestSelection selection, String since, String modules)
            throws IOException {
        return EngineReads.request(
                paths,
                new AffectedTestsRequest(dir.toString(), selection, since, modules).encode(),
                EngineProtocol.AFFECTED_TESTS_ACK,
                "affected-tests request",
                AffectedTestsReport::decode);
    }

    /**
     * Run {@code jk outdated} against the engine: one synchronous request, one {@code outdated-ack}
     * carrying the {@link cc.jumpkick.wire.protocol.OutdatedReport} back. Read-only — no cascade,
     * no plan stream.
     */
    static OutdatedReport runOutdated(EnginePaths.Paths paths, EngineRequests.OutdatedRequest req) throws IOException {
        return EngineReads.request(
                paths,
                new OutdatedRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.repoUrl() != null ? req.repoUrl().toString() : null,
                                req.offline(),
                                req.force())
                        .encode(),
                EngineProtocol.OUTDATED_ACK,
                "outdated request",
                OutdatedReport::decode);
    }

    /** Run {@code jk lock}'s cascade against the engine, driving {@code handler}. */
    static EngineRequests.LockOutcome runLock(
            EnginePaths.Paths paths, EngineRequests.LockRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return streamCascade(
                paths,
                new LockRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.features(),
                                req.noDefaultFeatures(),
                                req.sources(),
                                req.repoUrl() != null ? req.repoUrl().toString() : null,
                                req.offline(),
                                req.force(),
                                req.verbose(),
                                req.conservative())
                        .encode(),
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
        return new UpdateRequest(
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
                        req.platform())
                .encode();
    }

    /**
     * Run {@code jk sync}'s single plan against the engine — the same listener-factory contract as
     * {@link EngineJobs#runTest}. {@code fetchedOut}/{@code upToDateOut} (single-slot
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

        try (SocketChannel ch = EngineWire.connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineWire.protocolReader(ch);

            send(
                    writer,
                    new SyncRequest(
                                    req.entryDir().toString(),
                                    req.cache().toString(),
                                    req.jdksDir() != null ? req.jdksDir().toString() : null,
                                    req.repoUrl() != null ? req.repoUrl().toString() : null,
                                    req.sources(),
                                    req.offline(),
                                    req.force(),
                                    req.refresh(),
                                    req.verbose())
                            .encode());

            return WireStream.pumpJob(reader, ch, new WireStream.Decoder<BuildPlanResult>() {
                private final List<Task> steps = new ArrayList<>();
                private final List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
                private @Nullable BuildPlanListener listener;

                @Override
                public @Nullable BuildPlanResult onLine(String type, String line) throws IOException {
                    switch (type) {
                        case EngineProtocol.PLAN_TASK ->
                            steps.add(Task.builder(Jsonl.str(line, "name"))
                                    .label(Jsonl.str(line, "label"))
                                    .group(EngineEventDecoder.wireGroup(Jsonl.str(line, "stage")))
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
                        default -> EngineEventDecoder.dispatch(type, line, listener, diagnostics::add);
                    }
                    return null;
                }
            });
        }
    }

    /** Send a cascade request and replay its stream into {@code handler} until the terminal arrives. */
    private static EngineRequests.LockOutcome streamCascade(
            EnginePaths.Paths paths, String requestLine, EngineRequests.LockHandler handler, String planName)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineWire.connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineWire.protocolReader(ch);
            send(writer, requestLine);

            // Cascade state: the module currently streaming. Modules are strictly sequential on the
            // wire (the engine locks them one at a time), so one slot suffices. A named type, not
            // locals, because the catch below has to reach the module that was still live.
            final class Cascade implements WireStream.Decoder<EngineRequests.LockOutcome> {
                private @Nullable String currentDir;
                private @Nullable String currentCoord;
                private List<Task> steps = new ArrayList<>();
                private List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
                private @Nullable BuildPlanListener listener;

                @Override
                public EngineRequests.@Nullable LockOutcome onLine(String type, String line) throws IOException {
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
                                    .group(EngineEventDecoder.wireGroup(Jsonl.str(line, "stage")))
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
                            listener = null; // settled — settle() must not settle it twice
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
                        default -> EngineEventDecoder.dispatch(type, line, listener, diagnostics::add);
                    }
                    return null;
                }

                /**
                 * An in-flight module's live region must settle before the error propagates:
                 * planFinish dismisses the pinned region and restores the captured System.out, or
                 * the failure prints interleaved with a still-animating region and the terminal is
                 * left mid-frame with stdout redirected.
                 */
                void settle(Throwable failure) {
                    if (listener == null) return;
                    try {
                        listener.planFinish(new BuildPlanResult(
                                planName, false, Duration.ZERO, List.of(), List.of(), diagnostics, false, false));
                    } catch (RuntimeException settling) {
                        failure.addSuppressed(settling);
                    }
                }
            }

            Cascade cascade = new Cascade();
            try {
                return WireStream.pumpJob(reader, ch, cascade);
            } catch (IOException | RuntimeException e) {
                cascade.settle(e);
                throw e;
            }
        }
    }

    private static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static final EngineRequests.LockHandler NOOP_HANDLER = (dir, coord, steps) -> new BuildPlanListener() {};
}
