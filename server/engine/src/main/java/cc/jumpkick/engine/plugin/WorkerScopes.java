// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The engine's live jobs' {@link WorkerScope}s by job id: a cancel, a suspend or a drain arrives
 * with only the id. A job's scope is opened where its request is bound and closed when the job is
 * settled; a fork that outlives that still holds its scope through its request, and is killed on
 * arrival.
 */
public final class WorkerScopes {

    private final ConcurrentHashMap<Long, WorkerScope> byId = new ConcurrentHashMap<>();

    /** A new scope for job {@code id}, replacing any scope an earlier use of the id left. */
    public WorkerScope open(long id) {
        WorkerScope scope = new WorkerScope(id);
        byId.put(id, scope);
        return scope;
    }

    /** Job {@code id}'s scope while it is open, or {@code null}. */
    public @Nullable WorkerScope get(long id) {
        return byId.get(id);
    }

    /** {@link WorkerScope#shutdown} for job {@code id}; {@code 0} when it has no open scope. */
    public int shutdown(long id, long graceMs) {
        WorkerScope scope = byId.get(id);
        return scope == null ? 0 : scope.shutdown(graceMs);
    }

    /** {@link WorkerScope#hold} for job {@code id}; {@code 0} when it has no open scope. */
    public int hold(long id) {
        WorkerScope scope = byId.get(id);
        return scope == null ? 0 : scope.hold();
    }

    /** {@link WorkerScope#release} for job {@code id}; {@code 0} when it has no open scope. */
    public int release(long id) {
        WorkerScope scope = byId.get(id);
        return scope == null ? 0 : scope.release();
    }

    /** Job {@code id}'s forks still alive; {@code 0} when it has no open scope. */
    public int liveCount(long id) {
        WorkerScope scope = byId.get(id);
        return scope == null ? 0 : scope.liveCount();
    }

    /** Shut down job {@code id}'s workers at once and forget its scope. Idempotent. */
    public int close(long id) {
        WorkerScope scope = byId.remove(id);
        return scope == null ? 0 : scope.shutdown(0L);
    }
}
