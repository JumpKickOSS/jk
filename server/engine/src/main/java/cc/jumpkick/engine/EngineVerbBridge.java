// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.verbs.VerbHost;
import cc.jumpkick.host.Errors;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
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
            String dir, @Nullable BufferedWriter writer, @Nullable Function<BuildPlanResult, String> finishEncoder) {
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
    public void emitWorkspaceProgress(long rid, @Nullable BufferedWriter writer, boolean force) {
        sse.emitWorkspaceProgress(rid, writer, force);
    }

    @Override
    public void flushTimeline(long rid, @Nullable BufferedWriter writer) {
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
    public @Nullable String redactEnv(@Nullable String dir, @Nullable String text) {
        return EventRedaction.redactEnv(dir, text);
    }

    @Override
    public String requestFailedLine(@Nullable String dir, Throwable e) {
        return ProtoLifecycle.requestFailed(EventRedaction.redactText(dir, Errors.text(e)));
    }

    @Override
    public void publishRequestError(long rid, @Nullable String dir, String message) {
        sse.publishRequestError(rid, dir, message);
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
}
