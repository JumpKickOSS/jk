// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.engine.verbs.VerbHost;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** {@link VerbHost} wiring from the engine composition root. */
@RequiredArgsConstructor
public final class EngineVerbBridge implements VerbHost {

    private final LongSupplier eventRequestId;
    private final JobSessions sessions;
    private final EngineListeners listeners;
    private final JobEnvelope jobs;
    private final JournalWriter journalWriter;
    private final SsePublisher sse;
    private final IdleHousekeeping idle;
    private final ReentrantReadWriteLock cacheGate;
    private final AtomicInteger activeBuildPlans;
    private final LongSupplier clock;
    private final BuildJournal journal;
    private final JkHistoryConfig historyConfig;
    private final Supplier<Path> metricsFile;
    private final InFlightBuilds inFlight;

    @Override
    public long eventRequestId() {
        return eventRequestId.getAsLong();
    }

    @Override
    public void putProgressRoot(long rid, String dir) {
        sessions.progressRoot(rid, dir);
    }

    @Override
    public WorkspaceBuildListener workspaceListener(BufferedWriter writer, String dir) {
        return listeners.wire(writer, dir);
    }

    @Override
    public BuildPlanListener planListener(String dir, BufferedWriter writer, BuildPlan plan) {
        return listeners.wirePlan(dir, writer, plan);
    }

    @Override
    public BuildPlanListener planListener(
            String dir, BufferedWriter writer, Function<BuildPlanResult, String> finishEncoder) {
        return listeners.wirePlan(dir, writer, finishEncoder);
    }

    @Override
    public void releaseExclusiveSlot() {
        long id = eventRequestId.getAsLong();
        if (id > 0) inFlight.release(id);
    }

    @Override
    public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
        return jobs.effectiveCancelled(rid, tokenCancelled);
    }

    @Override
    public void accOutcome(long rid, boolean success, int exit) {
        journalWriter.accOutcome(rid, success, exit);
    }

    @Override
    public void accTests(long rid, @Nullable TestSummary tests) {
        journalWriter.accTests(rid, tests);
    }

    @Override
    public void finishProgress(long rid) {
        sessions.tracker(rid).finish();
    }

    @Override
    public void emitWorkspaceProgress(long rid, BufferedWriter writer, boolean force) {
        sse.emitWorkspaceProgress(rid, writer, force);
    }

    @Override
    public void flushTimeline(long rid, BufferedWriter writer) {
        listeners.flushTimeline(rid, writer);
    }

    @Override
    public void send(BufferedWriter writer, String line) throws IOException {
        EngineServer.send(writer, line);
    }

    @Override
    public void sendQuiet(BufferedWriter writer, String line) {
        EngineServer.sendQuiet(writer, line);
    }

    @Override
    public String redactEnv(@Nullable String dir, @Nullable String text) {
        return EventRedaction.redactEnv(dir, text);
    }

    @Override
    public String requestFailedLine(@Nullable String dir, Throwable e) {
        return ProtoLifecycle.requestFailed(EventRedaction.redactEnv(dir, String.valueOf(e.getMessage())));
    }

    @Override
    public void publishRequestError(long rid, @Nullable String dir, String message) {
        sse.publishRequestError(rid, dir, message);
    }

    @Override
    public Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh) {
        return resolve(requestLine, cancel, refresh);
    }

    @Override
    public void maybeEnqueuePrune(Path cache) {
        idle.maybeEnqueuePrune(cache);
    }

    @Override
    public ReentrantReadWriteLock cacheGate() {
        return cacheGate;
    }

    @Override
    public int activePlanCount() {
        return activeBuildPlans.get();
    }

    @Override
    public long nowMillis() {
        return clock.getAsLong();
    }

    @Override
    public boolean scheduleHostWarmup(boolean force) {
        return idle.scheduleHostWarmup(force);
    }

    @Override
    public BuildJournal journal() {
        return journal;
    }

    @Override
    public JkHistoryConfig historyConfig() {
        return historyConfig;
    }

    @Override
    public Path metricsFile() {
        return metricsFile.get();
    }

    @Override
    public InFlightBuilds inFlightBuilds() {
        return inFlight;
    }

    @Override
    public @Nullable Double lastProgress(long requestId) {
        return sessions.lastProgress(requestId);
    }

    /**
     * Reconstruct the request's {@link Session} from the flat config fields every lock/sync/update
     * request carries ({@code offline}/{@code force}/{@code verbose}, plus sync's {@code refresh}).
     */
    static Session resolve(String requestLine, Session.CancelToken cancelToken, boolean refresh) {
        Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
        Path cache = Path.of(Jsonl.str(requestLine, "cache"));
        JkConfig config = new JkConfig(
                Optional.empty(),
                Optional.of(Jsonl.bool(requestLine, "offline", false)),
                Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Jsonl.bool(requestLine, "verbose", false)),
                Optional.empty(),
                Optional.of(Jsonl.bool(requestLine, "force", false) || refresh),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return Session.defaults()
                .withConfig(config)
                .withWorkingDir(entryDir)
                .withCacheDir(cache)
                .withCancel(cancelToken)
                .withJvm(ProtoSession.jvmTuning(requestLine))
                .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                .withAssemblyOverride(ProtoSession.assemblyOverrideOf(requestLine));
    }
}
