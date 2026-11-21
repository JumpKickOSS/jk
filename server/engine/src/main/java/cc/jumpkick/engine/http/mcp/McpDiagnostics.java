// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Finds the journal record a diagnostics, details or run call answers from. */
public final class McpDiagnostics {

    private McpDiagnostics() {}

    public static @Nullable Map<String, Object> findRun(
            List<String> historyRaw, @Nullable String run, @Nullable String dir) {
        if (historyRaw == null) return null;
        boolean lastFail = run == null || run.isBlank() || "last-fail".equalsIgnoreCase(run);
        for (String raw : historyRaw) {
            Map<String, Object> rec = McpHistoryViews.parseRecord(raw);
            if (rec == null) continue;
            if (McpHistoryViews.bool(rec, "running")) continue;
            if (lastFail) {
                if (!McpHistoryViews.matches(rec, dir, null, Boolean.FALSE, null)) continue;
            } else {
                // A specific run id names one record; the bound-dir filter must not hide it.
                if (run == null || !run.equals(McpHistoryViews.str(rec, "id"))) continue;
            }
            return rec;
        }
        return null;
    }

    /** Finished row whose {@code requestId} equals {@code jid}. */
    public static @Nullable Map<String, Object> findByRequestId(List<String> historyRaw, long jid) {
        if (historyRaw == null || jid <= 0) return null;
        for (String raw : historyRaw) {
            Map<String, Object> rec = McpHistoryViews.parseRecord(raw);
            if (rec == null) continue;
            if (McpHistoryViews.lng(rec, "requestId") != jid) continue;
            if (McpHistoryViews.bool(rec, "running")) continue;
            return rec;
        }
        return null;
    }

    /** Newest finished row for {@code dir} (any kind). */
    public static @Nullable Map<String, Object> findNewest(List<String> historyRaw, @Nullable String dir) {
        if (historyRaw == null) return null;
        for (String raw : historyRaw) {
            Map<String, Object> rec = McpHistoryViews.parseRecord(raw);
            if (rec == null) continue;
            if (McpHistoryViews.bool(rec, "running")) continue;
            if (!McpHistoryViews.matches(rec, dir, null, null, null)) continue;
            return rec;
        }
        return null;
    }
}
