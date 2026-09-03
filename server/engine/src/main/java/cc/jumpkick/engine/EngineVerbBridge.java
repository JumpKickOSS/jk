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
import cc.jumpkick.engine.verbs.VerbHost;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
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
    public WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter writer, String dir) {
        return listeners.workspace(writer, dir);
    }

    @Override
    public BuildPlanListener planListener(String dir, @Nullable BufferedWriter writer, BuildPlan plan) {
        return listeners.plan(dir, writer, plan);
    }

    @Override
    public BuildPlanListener planListener(
            String dir, @Nullable BufferedWriter writer, Function<BuildPlanResult, String> finishEncoder) {
        return listeners.plan(dir, writer, finishEncoder);
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
    public void accTests(long rid, @Nullable TestSummary tests) {
        journalWriter.accTests(rid, tests);
    }

    @Override
    public void accAffected(long rid, cc.jumpkick.test.@Nullable AffectedTests affected) {
        journalWriter.accAffected(rid, affected);
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
    public void send(@Nullable BufferedWriter writer, String line) throws IOException {
        if (writer == null) return; // detached job — the sinks and hooks carry the facts
        WireWriter.send(writer, line);
    }

    @Override
    public void sendQuiet(@Nullable BufferedWriter writer, String line) {
        if (writer == null) return;
        WireWriter.sendQuiet(writer, line);
    }

    @Override
    public String redactEnv(@Nullable String dir, @Nullable String text) {
        return EventRedaction.redactEnv(dir, text);
    }

    @Override
    public String requestFailedLine(@Nullable String dir, Throwable e) {
        return ProtoLifecycle.requestFailed(EventRedaction.redactEnv(dir, Errors.text(e)));
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
        // Read-only verbs need no cache path and no longer send one (project-info dropped the
        // field in, before gave the verb a session at all). Absent means "the
        // engine's own", which is what Session.defaults() already holds — not a null Path.
        String cacheStr = Jsonl.str(requestLine, "cache");
        JkConfig config = JkConfig.empty()
                .withOffline(Jsonl.bool(requestLine, "offline", false))
                .withRebuild(Jsonl.bool(requestLine, "rebuild", false))
                .withVerbose(Jsonl.bool(requestLine, "verbose", false))
                .withForce(Jsonl.bool(requestLine, "force", false) || refresh);
        Session base = Session.defaults().withConfig(config).withWorkingDir(entryDir);
        if (cacheStr != null && !cacheStr.isBlank()) base = base.withCacheDir(Path.of(cacheStr));
        return base.withCancel(cancelToken)
                .withJvm(ProtoSession.jvmTuning(requestLine))
                .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                // The request's toolchain selection, resolved once for every verb that takes a
                // session from here. Without it the engine's SWITCH tier is permanently empty and a
                // resident daemon ignores both --jdk and JK_JDK.
                .withToolchainSpecs(
                        ProtoSession.jdkSpecOf(requestLine),
                        ProtoSession.graalSpecOf(requestLine),
                        ProtoSession.graalHomeOf(requestLine))
                .withAssemblyOverride(ProtoSession.assemblyOverrideOf(requestLine));
    }
}
