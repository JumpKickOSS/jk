// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.api.LockFloor;
import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.verbs.HostedVerb;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Embedded HTTP bind + dashboard/MCP job admission. A job kind resolves to its {@link HostedVerb},
 * decodes to the same wire request line the CLI would send, and submits FireAndForget on the one
 * {@link JobEnvelope} — there is no second verb body behind this class.
 */
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
    private final VerbRegistry verbs;
    private final LongPredicate cancelJob;
    private final ToIntFunction<String> cancelJobsForDir;
    private final @Nullable ReentrantReadWriteLock cacheGate;

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
        // The hub and the config are created together: no [http] table, neither of them.
        HttpEvents hub = events;
        if (config == null || hub == null) return;
        HttpEngineServer candidate = new HttpEngineServer(
                config,
                config.webRootPath(),
                paths.httpToken(),
                paths.log(),
                version,
                status,
                hub,
                httpJobs(),
                journal,
                () -> BuildMetrics.load(metricsFile.get()).entries(),
                // Single-flight + 30s TTL: dashboard SSE reconnect + GET /api/cache must not each
                // exclusive-walk multi-GiB stores (SerialGC balloons committed heap ~90 MiB).
                CacheSnapshot.memoizing(JkDirs.cache()),
                log);
        // Hard-refresh mid-build: history rows carry live requestId/progress/phases; SSE connect
        // delivers one compact run-snapshot per job to the new subscription only.
        candidate.setLiveRunSupport(liveRuns::snapshot, liveRuns::isLive, liveRuns::rehydrate);
        // Combined-connection peak observed at every admission point (UDS accept bumps it too) —
        // not only when a status snapshot happens to run.
        candidate.setOnSseAdmitted(() -> peakActiveConnections.accumulateAndGet(liveConnections.getAsInt(), Math::max));
        if (cacheGate != null) candidate.setCacheGate(cacheGate);
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
            public long trigger(JobSpec spec) {
                return EngineHttpFront.this.trigger(spec);
            }

            @Override
            public boolean cancel(long requestId) {
                return cancelJob.test(requestId);
            }

            @Override
            public int cancelDir(String dir) {
                return cancelJobsForDir.applyAsInt(dir);
            }
        };
    }

    /**
     * {@code POST /api/build} / MCP {@code jk_run}: resolve the verb, decode the spec into its
     * wire request line, submit FireAndForget on the one envelope.
     */
    private long trigger(JobSpec spec) {
        Path entryDir = requireProject(spec.dir());
        HostedVerb verb = verbs.forJobKind(spec.kind());
        if (verb == null) throw new IllegalArgumentException("kind not hosted: " + spec.kind());
        LockFloor.refuseIfBelow(entryDir, verb.wireType(), version);
        String line = verb.decodeJob(spec.withDir(entryDir.toString()));
        return jobs.submit(line, verb.toJobRequest(line), spec.transport());
    }

    private static Path requireProject(String dirStr) {
        Path entryDir = PathUtil.resolveUserPath(dirStr);
        if (!Files.isRegularFile(entryDir.resolve(ManifestPaths.MANIFEST))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        return entryDir;
    }
}
