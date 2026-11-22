// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recent no-ops' finished records by request id. A no-op leaves a run-log line, not a run directory,
 * so an MCP build that waits on its jid reads its verdict here. Bounded: cleared once full.
 */
public final class NoopRecords {

    /**
     * The field a no-op's record carries here, {@code true}: its verdict is {@link
     * JkResultsAgent#upToDate}, never a report on disk, which is the previous run's.
     */
    public static final String UP_TO_DATE = "upToDate";

    private static final int MAX = 256;

    private final ConcurrentHashMap<Long, String> byRequestId = new ConcurrentHashMap<>();

    void put(BuildRecord finished) {
        if (byRequestId.size() >= MAX) byRequestId.clear();
        byRequestId.put(finished.requestId(), Json.writeUpToDate(finished));
    }

    Optional<String> get(long requestId) {
        return Optional.ofNullable(byRequestId.get(requestId));
    }
}
