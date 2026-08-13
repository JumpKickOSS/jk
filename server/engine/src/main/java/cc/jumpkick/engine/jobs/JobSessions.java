// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The one process-lifetime map of {@link JobSession}. Retired ids stay marked so a late
 * {@code computeIfAbsent} cannot leak a session for the life of the daemon (JK-1474).
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
}
