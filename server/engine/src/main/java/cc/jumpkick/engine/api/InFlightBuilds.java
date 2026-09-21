// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Engine-local exclusive slots for same-fingerprint build-like jobs plus a view of every
 * in-flight hold for durable history / dashboard.
 *
 * <p>One engine's table. Across engines on one host the checkout's {@code target/.jk/build.lock}
 * ({@code BuildSlot}) arbitrates; the slot rides with the hold here so one release frees both.
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
            @Nullable String trigger,
            @Nullable String session) {}

    private final ConcurrentHashMap<String, Hold> byFingerprint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Hold> byRequestId = new ConcurrentHashMap<>();

    /** The checkout's cross-process slot each exclusive hold took, closed with the hold. */
    private final ConcurrentHashMap<Long, Closeable> slots = new ConcurrentHashMap<>();

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

    /** Pair {@code requestId}'s hold with the checkout slot it took; {@link #release} closes it. */
    public void attachSlot(long requestId, Closeable slot) {
        slots.put(requestId, slot);
    }

    /** Release after finish/cancel. Idempotent. */
    public void release(long requestId) {
        Closeable slot = slots.remove(requestId);
        if (slot != null) {
            try {
                slot.close();
            } catch (IOException ignored) {
                // the OS releases the lock with the channel either way
            }
        }
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
                        h.trigger(),
                        h.session()));
        Hold updated = byRequestId.get(requestId);
        if (updated != null
                && updated.fingerprint() != null
                && !updated.fingerprint().isEmpty()) {
            byFingerprint.put(updated.fingerprint(), updated);
        }
    }
}
