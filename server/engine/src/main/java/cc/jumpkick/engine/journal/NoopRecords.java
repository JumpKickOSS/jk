// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recent no-ops' finished records by request id. A no-op leaves a run-log line, not a run directory,
 * so an MCP run that waits on its jid reads its verdict here. Bounded: cleared once full.
 */
public final class NoopRecords {

    private static final int MAX = 256;

    private final ConcurrentHashMap<Long, String> byRequestId = new ConcurrentHashMap<>();

    /** The highest request id among {@code journal}'s newest records: where a starting engine numbers on from. */
    public static long newestRequestId(BuildJournal journal) {
        return journal.rawRecords(200).stream()
                .mapToLong(r -> BuildJournal.scanLong(r, "requestId"))
                .max()
                .orElse(0);
    }

    void put(BuildRecord finished) {
        if (byRequestId.size() >= MAX) byRequestId.clear();
        byRequestId.put(finished.requestId(), Json.write(finished));
    }

    Optional<String> get(long requestId) {
        return Optional.ofNullable(byRequestId.get(requestId));
    }
}
