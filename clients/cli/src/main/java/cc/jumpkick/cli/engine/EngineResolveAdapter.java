// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import cc.jumpkick.wire.protocol.AffectedTestsRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.EngineWireException;
import cc.jumpkick.wire.protocol.LockFinishEvent;
import cc.jumpkick.wire.protocol.LockModuleEvent;
import cc.jumpkick.wire.protocol.LockPackageEvent;
import cc.jumpkick.wire.protocol.LockPhaseEvent;
import cc.jumpkick.wire.protocol.LockRequest;
import cc.jumpkick.wire.protocol.OutdatedReport;
import cc.jumpkick.wire.protocol.OutdatedRequest;
import cc.jumpkick.wire.protocol.PlanFinishLockEvent;
import cc.jumpkick.wire.protocol.PlanFinishSyncEvent;
import cc.jumpkick.wire.protocol.SyncRequest;
import cc.jumpkick.wire.protocol.UpdateRequest;
import cc.jumpkick.wire.protocol.UpdateRewriteEvent;
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
import java.util.Objects;
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
            EnginePaths.Paths paths,
            Path dir,
            TestSelection selection,
            @Nullable String since,
            @Nullable String modules)
            throws IOException {
        return EngineReads.request(
                paths,
                EngineJobs.envelope(new AffectedTestsRequest(dir.toString(), selection, since, modules).encode()),
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
                EngineJobs.envelope(new OutdatedRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.repoUrl() != null ? req.repoUrl().toString() : null,
                                req.offline(),
                                req.force())
                        .encode()),
                EngineProtocol.OUTDATED_ACK,
                "outdated request",
                OutdatedReport::decode);
    }

    /** Run {@code jk lock}'s cascade against the engine, driving {@code handler}. */
    static EngineRequests.LockOutcome runLock(
            EnginePaths.Paths paths, EngineRequests.LockRequest req, EngineRequests.LockHandler handler)
            throws IOException {
        return streamCascade(paths, lockRequestLine(req), handler, "lock");
    }

    /**
     * The lock request line, session envelope attached. The resolve behind {@code jk lock} runs in
     * a resident engine whose own environment is the shell that started it; the caller's {@code
     * JK_REPO_*} credentials, variant and toolchain choice reach it only on the request.
     */
    static String lockRequestLine(EngineRequests.LockRequest req) {
        return EngineJobs.envelope(new LockRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.features(),
                        req.noDefaultFeatures(),
                        req.sources(),
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.offline(),
                        req.force(),
                        req.verbose(),
                        req.freshen())
                .encode());
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
            EnginePaths.Paths paths, EngineRequests.UpdateRequest req, @Nullable String gitTarget) throws IOException {
        return streamCascade(paths, updateRequestLine(req, true, gitTarget), NOOP_HANDLER, "update");
    }

    /** The update request line, session envelope attached — see {@link #lockRequestLine}. */
    static String updateRequestLine(EngineRequests.UpdateRequest req, boolean gitOnly, @Nullable String gitTarget) {
        return EngineJobs.envelope(new UpdateRequest(
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
                        Objects.requireNonNullElse(req.platform(), ""),
                        req.deps(),
                        req.major(),
                        false)
                .encode());
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
        EngineClient.ensureRunning(paths, JkVersion.VERSION);

        try (SocketChannel ch = EngineWire.connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineWire.protocolReader(ch);

            send(
                    writer,
                    EngineJobs.envelope(new SyncRequest(
                                    req.entryDir().toString(),
                                    req.cache().toString(),
                                    req.jdksDir() != null ? req.jdksDir().toString() : null,
                                    req.repoUrl() != null ? req.repoUrl().toString() : null,
                                    req.sources(),
                                    req.offline(),
                                    req.force(),
                                    req.refresh(),
                                    req.verbose())
                            .encode()));

            return WireStream.pumpJob(reader, ch, new WireStream.Decoder<BuildPlanResult>() {
                private final List<Task> steps = new ArrayList<>();
                private final List<BuildPlanResult.Diagnostic> diagnostics = new ArrayList<>();
                private @Nullable BuildPlanListener listener;

                @Override
                public @Nullable BuildPlanResult onLine(String type, String line) throws IOException {
                    switch (type) {
                        case EngineProtocol.PLAN_TASK -> steps.add(EngineEventDecoder.taskFromWire(line));
                        case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(steps);
                        case EngineProtocol.BUILDPLAN_FINISH -> {
                            PlanFinishSyncEvent e = PlanFinishSyncEvent.decode(line);
                            if (fetchedOut != null) fetchedOut[0] = e.fetched();
                            if (upToDateOut != null) upToDateOut[0] = e.upToDate();
                            BuildPlanResult result = new BuildPlanResult(
                                    "sync",
                                    e.success(),
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
        EngineClient.ensureRunning(paths, JkVersion.VERSION);

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
                            LockModuleEvent e = LockModuleEvent.decode(line);
                            currentDir = e.dir();
                            currentCoord = e.coord();
                            steps = new ArrayList<>();
                            diagnostics = new ArrayList<>();
                            listener = null;
                        }
                        case EngineProtocol.PLAN_TASK -> steps.add(EngineEventDecoder.taskFromWire(line));
                        case EngineProtocol.PLAN_DONE ->
                            listener = handler.onModuleStart(
                                    Objects.requireNonNull(currentDir, "plan-done before module-start"),
                                    Objects.requireNonNull(currentCoord, "plan-done before module-start"),
                                    steps);
                        case EngineProtocol.LOCK_PACKAGE -> {
                            LockPackageEvent e = LockPackageEvent.decode(line);
                            handler.onPackage(e.dir(), e.name(), e.version(), e.totalSeen());
                        }
                        case EngineProtocol.LOCK_PHASE -> {
                            LockPhaseEvent e = LockPhaseEvent.decode(line);
                            handler.onPhase(e.dir(), e.label());
                        }
                        case EngineProtocol.UPDATE_REWRITE -> {
                            UpdateRewriteEvent e = UpdateRewriteEvent.decode(line);
                            handler.onRewrite(e.dir(), e.table(), e.handle(), e.module(), e.from(), e.to());
                        }
                        case EngineProtocol.BUILDPLAN_FINISH -> {
                            PlanFinishLockEvent e = PlanFinishLockEvent.decode(line);
                            BuildPlanResult result = new BuildPlanResult(
                                    planName,
                                    e.success(),
                                    Duration.ZERO,
                                    List.of(),
                                    List.of(),
                                    diagnostics,
                                    false,
                                    false);
                            if (listener != null) listener.planFinish(result);
                            listener = null; // settled — settle() must not settle it twice
                            // Absent counts are unknown (-1) here, where the record reads 0.
                            handler.onModuleFinish(
                                    Objects.requireNonNull(currentDir, "module-finish before module-start"),
                                    result,
                                    new EngineRequests.LockCounts(
                                            Jsonl.has(line, "lockPackages") ? e.packages() : -1,
                                            Jsonl.has(line, "lockSources") ? e.sources() : -1,
                                            Jsonl.has(line, "lockPlugins") ? e.plugins() : -1,
                                            e.unverified(),
                                            e.insecureRepos()));
                        }
                        case EngineProtocol.LOCK_FINISH -> {
                            LockFinishEvent e = LockFinishEvent.decode(line);
                            // An absent exit code is a failure here, where the record reads 0.
                            int exitCode = Jsonl.has(line, "exitCode") ? e.exitCode() : 1;
                            return new EngineRequests.LockOutcome(e.success(), exitCode, e.errors(), e.refreshed());
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
