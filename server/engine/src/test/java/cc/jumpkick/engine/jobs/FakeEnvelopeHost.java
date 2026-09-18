// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** {@link JobEnvelope.Host} with every seam observable: what the wire and the dashboard would see, in order. */
final class FakeEnvelopeHost implements JobEnvelope.Host {
    boolean tryStart = true;
    volatile int abandoned;
    volatile int finished;
    volatile BuildAccumulator accumulator = new BuildAccumulator("build", "/p", null, "cli");
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final List<Long> cleared = Collections.synchronizedList(new ArrayList<>());
    final List<String> teardownOrder = Collections.synchronizedList(new ArrayList<>());
    final InFlightBuilds inFlight = new InFlightBuilds();
    final AtomicLong ids = new AtomicLong();
    final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

    @Override
    public boolean tryStartBuildPlan() {
        if (tryStart) activePlans++;
        return tryStart;
    }

    @Override
    public void abandonBuildPlanSlot() {
        abandoned++;
    }

    @Override
    public void noteBuildPlanFinished() {
        finished++;
        activePlans--;
        sequence.add("plan-slot-released");
    }

    /** Every host call the envelope makes that the wire or the dashboard can observe, in order. */
    final List<String> sequence = Collections.synchronizedList(new ArrayList<>());

    volatile int activePlans;

    @Override
    public boolean draining() {
        return !tryStart;
    }

    @Override
    public long nextRequestId() {
        return ids.incrementAndGet();
    }

    /** Frozen by default; a deadline test swaps in the wall clock. */
    LongSupplier clock = () -> 1_000L;

    @Override
    public long nowMillis() {
        return clock.getAsLong();
    }

    @Override
    public void putMode(long id, ProgressBarMode mode) {}

    @Override
    public void publishRequestStart(long id, String kind, String dir, long buildNumber) {
        sequence.add("request-start");
    }

    @Override
    public void publishRequestQueued(long id, String kind, String dir, int ahead) {
        sequence.add("request-queued");
        events.add("request-queued:" + id + ":" + ahead);
    }

    @Nullable
    Boolean lastNoTimeline;

    /** The build number the last admitted job was registered under. */
    volatile long lastBuildNumber = -1;

    @Override
    public void registerAccumulator(
            long id,
            String kind,
            String dir,
            String trigger,
            @Nullable String session,
            boolean noTimeline,
            boolean rebuild,
            long buildNumber,
            @Nullable String journalId) {
        lastNoTimeline = noTimeline;
        lastBuildNumber = buildNumber;
    }

    @Override
    public ReentrantReadWriteLock cacheGate() {
        return gate;
    }

    @Override
    public void bindEventRequestId(long id) {}

    @Override
    public void unbindEventRequestId() {}

    final IoLedger io = new IoLedger();

    @Override
    public IoLedger runIo(long id) {
        return io;
    }

    @Override
    public InFlightBuilds inFlight() {
        return inFlight;
    }

    @Override
    public BuildAccumulator accumulatorOf(long id) {
        return accumulator;
    }

    @Override
    public void putLastProgress(long id, double percent) {
        sequence.add("progress-pinned:" + percent);
    }

    volatile long lastEventAt;

    @Override
    public long lastEventAt(long id) {
        return lastEventAt;
    }

    @Override
    public int activeBuildPlans() {
        return activePlans;
    }

    @Override
    public JsonOut withProgress(JsonOut payload, long id) {
        return payload;
    }

    @Override
    public JsonOut withIo(JsonOut payload, long id) {
        return payload;
    }

    @Override
    public void publishEvent(String type, JsonOut payload) {
        events.add(type + ":" + payload);
        sequence.add(type);
    }

    @Override
    public void clearProgress(long id) {
        teardownOrder.add("clearProgress");
        sequence.add("clearProgress");
        cleared.add(id);
    }

    volatile boolean journalWritten;
    volatile boolean journalCancelled;
    volatile long journalMillis;

    volatile boolean journalThrows;

    @Override
    public void writeJournal(long id, boolean cancelled, long millis, @Nullable BufferedWriter writer) {
        teardownOrder.add("writeJournal");
        sequence.add("writeJournal");
        journalCancelled = cancelled;
        journalMillis = millis;
        journalWritten = true;
        if (journalThrows) throw new IllegalStateException("journal disk full");
    }

    /** The row JournalWriter would persist for this run, built the same way it builds it. */
    BuildRecord journalRecord() {
        return accumulator.toRecord(
                2_000L, journalCancelled || accumulator.wasCancelled(), journalMillis, version(), null);
    }

    @Override
    public void maybeIdleBoundary() {
        sequence.add("idle-boundary");
    }

    @Override
    public void maybeIdleGc() {
        sequence.add("idle-gc");
    }

    final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void log(String message) {
        logs.add(message);
    }

    @Override
    public String version() {
        return "0.0.0-test";
    }

    @Override
    public JkHistoryConfig historyConfig() {
        return new JkHistoryConfig(false, 0, 0); // never write real journal stubs from a unit test
    }

    @Override
    public BuildJournal journal() {
        return BuildJournal.current();
    }

    @Override
    public String coordOf(String dir) {
        return "test:job";
    }
}
