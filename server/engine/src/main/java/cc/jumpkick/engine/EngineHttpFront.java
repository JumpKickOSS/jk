// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.BuildMetrics;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Embedded HTTP bind + dashboard/MCP job triggers (FireAndForget JobEnvelope). */
@RequiredArgsConstructor
public final class EngineHttpFront {

    private final @Nullable JkHttpConfig config;
    private final EnginePaths.Paths paths;
    private final String version;
    private final @Nullable HttpEvents events;
    private final BuildJournal journal;
    private final Supplier<Path> metricsFile;
    private final Consumer<String> log;
    private final Supplier<StatusSnapshot> status;
    private final LiveRuns liveRuns;
    private final AtomicInteger peakActiveConnections;
    private final IntSupplier liveConnections;
    private final JobEnvelope jobs;
    private final JobSessions sessions;
    private final SsePublisher sse;
    private final JournalWriter journalWriter;
    private final LongSupplier eventRequestId;
    private final EngineListeners listeners;
    private final LongPredicate cancelJob;

    private volatile @Nullable HttpEngineServer server;
    private volatile @Nullable String error;

    public @Nullable HttpEngineServer server() {
        return server;
    }

    public @Nullable String error() {
        return error;
    }

    public int liveEventStreams() {
        HttpEngineServer h = server;
        return h == null ? 0 : h.liveEventStreams();
    }

    /** Start when {@code [http]} is present; bind failure is advisory only. */
    public void start() {
        if (config == null) return;
        HttpEngineServer candidate = new HttpEngineServer(
                config,
                config.webRootPath(),
                paths.httpToken(),
                paths.log(),
                version,
                status,
                events,
                httpJobs(),
                journal,
                () -> BuildMetrics.load(metricsFile.get()).entries(),
                // Single-flight + 30s TTL: dashboard SSE reconnect + GET /api/cache must not each
                // exclusive-walk multi-GiB stores (SerialGC balloons committed heap ~90 MiB).
                cc.jumpkick.engine.http.CacheSnapshot.memoizing(cc.jumpkick.util.JkDirs.cache()),
                log);
        // Hard-refresh mid-build: history rows carry live requestId/progress/phases; SSE connect
        // delivers one compact run-snapshot per job to the new subscription only.
        candidate.setLiveRunSupport(liveRuns::snapshot, liveRuns::rehydrate);
        // Combined-connection peak observed at every admission point (UDS accept bumps it too) —
        // not only when a status snapshot happens to run.
        candidate.setOnSseAdmitted(() -> peakActiveConnections.accumulateAndGet(liveConnections.getAsInt(), Math::max));
        try {
            candidate.start();
            Files.writeString(paths.http(), candidate.url());
            server = candidate;
            log.accept("jk engine: http listening on " + candidate.url());
        } catch (IOException | RuntimeException e) {
            candidate.close();
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.accept("jk engine: http failed to start (" + error + ") — continuing without http");
        }
    }

    /** Hand the Web UI port to a successor immediately (idempotent). */
    public void stopNow() {
        HttpEngineServer h = server;
        if (h != null) h.stopNow();
    }

    public void close() {
        HttpEngineServer h = server;
        if (h != null) h.close();
        server = null;
    }

    private EngineHttpJobs httpJobs() {
        return new EngineHttpJobs() {
            @Override
            public long triggerBuild(String dir) {
                return triggerWorkspace(dir, "build", false, false);
            }

            @Override
            public long triggerTest(String dir) {
                return triggerWorkspace(dir, "test", false, true);
            }

            @Override
            public long triggerLock(String dir) {
                return EngineHttpFront.this.triggerLock(dir);
            }

            @Override
            public boolean cancel(long requestId) {
                return cancelJob.test(requestId);
            }
        };
    }

    /** {@code POST /api/build} / MCP: same JobEnvelope as CLI, FireAndForget. */
    private long triggerWorkspace(String dirStr, String kind, boolean skipTests, boolean testOnly) {
        Path entryDir = cc.jumpkick.util.PathUtil.resolveUserPath(dirStr);
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        String line = "{\"type\":\"build-request\",\"dir\":"
                + cc.jumpkick.plugin.protocol.Jsonl.quote(entryDir.toString())
                + ",\"trigger\":\"web\"}";
        JobRequest req = JobRequest.workspace(
                kind, "jk-engine-http-" + kind + "-", (l, tok, w) -> runWorkspace(entryDir, skipTests, testOnly, tok));
        return jobs.submitAsync(line, req, BuildJobFingerprint.ofHttp(kind, entryDir, skipTests, testOnly));
    }

    private long triggerLock(String dirStr) {
        Path entryDir = cc.jumpkick.util.PathUtil.resolveUserPath(dirStr);
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        String line = "{\"type\":\"lock-request\",\"dir\":"
                + cc.jumpkick.plugin.protocol.Jsonl.quote(entryDir.toString())
                + ",\"trigger\":\"web\"}";
        return jobs.submitAsync(
                line, JobRequest.plan("lock", "jk-engine-http-lock-", (l, tok, w) -> runLock(entryDir, tok)), "");
    }

    private boolean runWorkspace(Path entryDir, boolean skipTests, boolean testOnly, Session.CancelToken cancelToken) {
        try {
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            Path cache = cc.jumpkick.util.JkDirs.cache();
            Path jdksDir = cc.jumpkick.util.JkDirs.jdks();
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir,
                            entryBuild,
                            cache,
                            jdksDir,
                            Runtime.getRuntime().availableProcessors(),
                            null,
                            skipTests,
                            false,
                            0,
                            null,
                            false,
                            true)
                    .withTestOnly(testOnly);
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken);
            long rid = eventRequestId.getAsLong();
            if (rid > 0) sessions.progressRoot(rid, entryDir.toString());
            WorkspaceResult result = SessionContext.where(
                    session, () -> BuildService.buildWorkspace(req, listeners.hub(entryDir.toString())));
            journalWriter.accOutcome(rid, result.success(), result.exitCode());
            if (rid > 0) {
                if (result.success()) sessions.tracker(rid).finish();
                sse.emitWorkspaceProgress(rid, null, true);
            }
            if (!result.success()) {
                for (String err : result.errors().stream().limit(5).toList()) {
                    sse.publishRequestError(rid, entryDir.toString(), err);
                }
            }
            return result.success();
        } catch (Exception e) {
            journalWriter.accOutcome(eventRequestId.getAsLong(), false, 1);
            log.accept("jk engine: http-triggered job of " + entryDir + " failed: "
                    + EventRedaction.redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            sse.publishRequestError(eventRequestId.getAsLong(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }

    private boolean runLock(Path entryDir, Session.CancelToken cancelToken) {
        try {
            Path cache = cc.jumpkick.util.JkDirs.cache();
            var scope = cc.jumpkick.runtime.LockPlans.lockScope(entryDir);
            Path lockDir = scope.lockDir();
            Session session = Session.defaults()
                    .withWorkingDir(lockDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.LockPlans.lockBuildPlan(
                    lockDir, scope.effective(), cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
            plan.addListener(listeners.hubPlan(lockDir.toString()));
            cc.jumpkick.run.BuildPlanResult result;
            synchronized (cc.jumpkick.runtime.LockGate.monitorFor(lockDir)) {
                result = SessionContext.where(session, plan::run);
            }
            journalWriter.accOutcome(eventRequestId.getAsLong(), result.success(), result.success() ? 0 : 1);
            if (!result.success()) {
                for (var d : result.errors().stream().limit(5).toList()) {
                    sse.publishRequestError(eventRequestId.getAsLong(), entryDir.toString(), d.message());
                }
            }
            return result.success();
        } catch (Exception e) {
            journalWriter.accOutcome(eventRequestId.getAsLong(), false, 1);
            log.accept("jk engine: http-triggered lock of " + entryDir + " failed: "
                    + EventRedaction.redactEnv(entryDir.toString(), String.valueOf(e.getMessage())));
            sse.publishRequestError(eventRequestId.getAsLong(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }
}
