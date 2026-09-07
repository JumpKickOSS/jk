// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Engine-local exclusive slots for same-fingerprint build-like jobs plus a view of every
 * in-flight hold for durable history / dashboard.
 *
 * <p>Not a distributed lock: one resident engine process. Different engines on the same host
 * are out of scope.
 */
public final class InFlightBuilds {

    /** One admitted exclusive (or tracked) job. */
    public record Hold(
            long requestId,
            long buildNumber,
            String fingerprint,
            String kind,
            String dir,
            @Nullable String coord,
            long startedAt,
            @Nullable String journalId,
            @Nullable String trigger) {}

    private final ConcurrentHashMap<String, Hold> byFingerprint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Hold> byRequestId = new ConcurrentHashMap<>();

    /** Current holder of {@code fingerprint}, if any. */
    public Optional<Hold> peek(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return Optional.empty();
        return Optional.ofNullable(byFingerprint.get(fingerprint));
    }

    /**
     * Try to admit {@code candidate}. Empty → admitted (holder is stored). Present → rejected; value
     * is the existing holder.
     */
    public Optional<Hold> tryAcquire(Hold candidate) {
        if (candidate == null) return Optional.empty(); // nothing to track
        if (candidate.fingerprint() == null || candidate.fingerprint().isEmpty()) {
            // Non-exclusive path: track by request only for list/running UI if needed.
            byRequestId.put(candidate.requestId(), candidate);
            return Optional.empty();
        }
        Hold[] rejected = new Hold[1];
        byFingerprint.compute(candidate.fingerprint(), (fp, existing) -> {
            if (existing != null) {
                rejected[0] = existing;
                return existing;
            }
            byRequestId.put(candidate.requestId(), candidate);
            return candidate;
        });
        return Optional.ofNullable(rejected[0]);
    }

    /** Release after finish/cancel. Idempotent. */
    public void release(long requestId) {
        Hold h = byRequestId.remove(requestId);
        if (h == null) return;
        if (h.fingerprint() != null && !h.fingerprint().isEmpty()) {
            byFingerprint.computeIfPresent(h.fingerprint(), (fp, cur) -> cur.requestId() == requestId ? null : cur);
        }
    }

    public Optional<Hold> get(long requestId) {
        return Optional.ofNullable(byRequestId.get(requestId));
    }

    /** Snapshot of every in-flight hold (for /api/history merge / tests). */
    public List<Hold> list() {
        return new ArrayList<>(byRequestId.values());
    }

    /** Update journal id after begin() persists an entry. */
    public void bindJournalId(long requestId, String journalId) {
        byRequestId.computeIfPresent(
                requestId,
                (id, h) -> new Hold(
                        h.requestId(),
                        h.buildNumber(),
                        h.fingerprint(),
                        h.kind(),
                        h.dir(),
                        h.coord(),
                        h.startedAt(),
                        journalId,
                        h.trigger()));
        Hold updated = byRequestId.get(requestId);
        if (updated != null
                && updated.fingerprint() != null
                && !updated.fingerprint().isEmpty()) {
            byFingerprint.put(updated.fingerprint(), updated);
        }
    }
}
