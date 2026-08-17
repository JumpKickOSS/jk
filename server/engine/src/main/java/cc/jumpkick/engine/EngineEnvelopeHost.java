// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.runtime.progress.ProgressBarMode;
import java.io.BufferedWriter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** {@link JobEnvelope.Host} wiring from the engine composition root. */
@RequiredArgsConstructor
public final class EngineEnvelopeHost implements JobEnvelope.Host {

    private final BooleanSupplier tryStart;
    private final Runnable abandon;
    private final Runnable noteFinished;
    private final BooleanSupplier draining;
    private final AtomicLong requestIds;
    private final LongSupplier clock;
    private final JobSessions sessions;
    private final SsePublisher sse;
    private final JournalWriter journalWriter;
    private final ReentrantReadWriteLock cacheGate;
    private final ThreadLocal<Long> currentEventRequestId;
    private final InFlightBuilds inFlight;
    private final AtomicInteger activeBuildPlans;
    private final IdleHousekeeping idle;
    private final Consumer<String> log;
    private final String version;
    private final JkHistoryConfig historyConfig;
    private final BuildJournal journal;

    @Override
    public boolean tryStartBuildPlan() {
        return tryStart.getAsBoolean();
    }

    @Override
    public void abandonBuildPlanSlot() {
        abandon.run();
    }

    @Override
    public void noteBuildPlanFinished() {
        noteFinished.run();
    }

    @Override
    public boolean draining() {
        return draining.getAsBoolean();
    }

    @Override
    public long nextRequestId() {
        return requestIds.incrementAndGet();
    }

    @Override
    public long nowMillis() {
        return clock.getAsLong();
    }

    @Override
    public void putMode(long id, ProgressBarMode mode) {
        sessions.mode(id, mode);
    }

    @Override
    public void publishRequestStart(long id, String kind, String dir, long buildNumber) {
        sse.publishRequestStart(id, kind, dir, buildNumber);
    }

    @Override
    public void registerAccumulator(
            long id,
            String kind,
            String dir,
            String trigger,
            boolean noTimeline,
            boolean rebuild,
            long buildNumber,
            @Nullable String journalId) {
        journalWriter.register(id, kind, dir, trigger, noTimeline, rebuild, buildNumber, journalId);
    }

    @Override
    public ReentrantReadWriteLock cacheGate() {
        return cacheGate;
    }

    @Override
    public void bindEventRequestId(long id) {
        currentEventRequestId.set(id);
    }

    @Override
    public void unbindEventRequestId() {
        currentEventRequestId.remove();
    }

    @Override
    public cc.jumpkick.task.IoLedger runIo(long id) {
        return sse.runIo(id);
    }

    @Override
    public InFlightBuilds inFlight() {
        return inFlight;
    }

    @Override
    public @Nullable BuildAccumulator accumulatorOf(long id) {
        return sessions.accumulator(id);
    }

    @Override
    public void putLastProgress(long id, double percent) {
        sessions.lastProgress(id, percent);
    }

    @Override
    public int activeBuildPlans() {
        return activeBuildPlans.get();
    }

    @Override
    public JsonOut withProgress(JsonOut payload, long id) {
        return sse.withProgress(payload, id);
    }

    @Override
    public JsonOut withIo(JsonOut payload, long id) {
        return sse.withIo(payload, id);
    }

    @Override
    public void publishEvent(String type, JsonOut payload) {
        sse.publishEvent(type, payload);
    }

    @Override
    public void clearProgress(long id) {
        sessions.retire(id);
    }

    @Override
    public void writeJournal(long id, boolean cancelled, long millis, BufferedWriter writer) {
        journalWriter.write(id, cancelled, millis, writer);
    }

    @Override
    public void maybeIdleBoundary() {
        idle.maybeIdleBoundary();
    }

    @Override
    public void maybeIdleGc() {
        idle.maybeIdleGc();
    }

    @Override
    public void log(String message) {
        log.accept(message);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public JkHistoryConfig historyConfig() {
        return historyConfig;
    }

    @Override
    public BuildJournal journal() {
        return journal;
    }

    @Override
    public String coordOf(String dir) {
        return JournalWriter.coordOf(dir);
    }
}
