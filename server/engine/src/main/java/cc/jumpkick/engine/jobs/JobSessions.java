// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.wire.runtime.RemainingWork;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The one process-lifetime map of {@link JobSession}. Retired ids stay marked so a late
 * {@code computeIfAbsent} cannot leak a session for the life of the daemon.
 */
public final class JobSessions {
    /** How far below the newest request id a retired marker is still worth keeping. */
    static final long RETIRED_WINDOW = 1024L;

    private final ConcurrentHashMap<Long, JobSession> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> retired = new ConcurrentHashMap<>();
    private final LongSupplier newestId;

    public JobSessions(LongSupplier newestId) {
        this.newestId = newestId;
    }

    /** Existing live session, or {@code null}. Does not create. */
    public @Nullable JobSession get(long id) {
        if (id <= 0 || retired.containsKey(id)) return null;
        return byId.get(id);
    }

    /** Live session, creating one if this id is not retired. */
    public @Nullable JobSession open(long id) {
        if (id <= 0 || retired.containsKey(id)) return null;
        return byId.computeIfAbsent(id, JobSession::new);
    }

    /** Live or freshly opened session; {@code null} only for id ≤ 0 or retired. */
    public @Nullable JobSession session(long id) {
        return open(id);
    }

    public boolean retired(long id) {
        return retired.containsKey(id);
    }

    /**
     * Retire {@code id}: drop the row, remember the id so late opens no-op, prune old markers.
     */
    public void retire(long id) {
        if (id <= 0) return;
        retired.put(id, Boolean.TRUE);
        JobSession s = byId.remove(id);
        if (s != null) s.retire();
        long cutoff = newestId.getAsLong() - RETIRED_WINDOW;
        if (cutoff > 0) retired.keySet().removeIf(n -> n < cutoff);
    }

    /** Test seam: live row count (not including retired markers). */
    int liveCount() {
        return byId.size();
    }

    public void progressRoot(long id, String root) {
        JobSession s = open(id);
        if (s != null) s.progressRoot(root);
    }

    public void remaining(long id, RemainingWork rw) {
        JobSession s = open(id);
        if (s != null) s.remaining(rw);
    }

    public void mode(long id, ProgressBarMode mode) {
        JobSession s = open(id);
        if (s != null) s.mode(mode);
    }

    public void lastProgress(long id, double p) {
        JobSession s = open(id);
        if (s != null) s.lastProgress(p);
    }

    public @Nullable Double lastProgress(long id) {
        JobSession s = get(id);
        return s == null ? null : s.lastProgress();
    }

    /** Wall clock of the newest progress/remaining signal for {@code id}; 0 when none. */
    public long lastEventAt(long id) {
        JobSession s = get(id);
        return s == null ? 0L : s.lastEventAt();
    }

    public void accumulator(long id, BuildAccumulator acc) {
        JobSession s = open(id);
        if (s != null) s.accumulator(acc);
    }

    public @Nullable BuildAccumulator accumulator(long id) {
        JobSession s = get(id);
        return s == null ? null : s.accumulator();
    }

    /**
     * Remove and return the accumulator for {@code id}. Safe only while the session is still live —
     * callers must take before {@link #retire(long)} (JobEnvelope: writeJournal then clearProgress).
     */
    public @Nullable BuildAccumulator takeAccumulator(long id) {
        JobSession s = get(id);
        if (s == null) return null;
        var a = s.accumulator();
        s.accumulator(null);
        return a;
    }

    public ConcurrentHashMap<String, Long> weights(long id) {
        JobSession s = open(id);
        return s == null ? new ConcurrentHashMap<>() : s.weights();
    }

    public @Nullable WorkspaceProgressTracker trackerOrNull(long id) {
        JobSession s = get(id);
        return s == null ? null : s.existingTracker();
    }

    public WorkspaceProgressTracker tracker(long id) {
        if (retired(id)) return new WorkspaceProgressTracker(null);
        JobSession s = open(id);
        if (s == null) return new WorkspaceProgressTracker(null);
        return s.tracker();
    }
}
