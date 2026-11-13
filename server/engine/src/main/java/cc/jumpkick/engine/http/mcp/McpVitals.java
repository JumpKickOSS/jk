// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.http.StatusSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code status} envelope: engine vitals with their job rows, and the last finished run. One
 * owner, because {@code status} and the {@code jk://session} resource are the same facts and
 * must not answer differently.
 */
public final class McpVitals {

    private McpVitals() {}

    /**
     * Every vital from the one enumeration — the {@code jobs} array included, each live and queued
     * job as the {@code JobRow} that {@code jk engine status} and {@code GET /api/status} render —
     * plus the bound dir and the last run, which are the calling connection's alone; {@code bound}
     * is that connection's dir, or null when it has none.
     */
    public static Map<String, Object> statusPayload(McpContext ctx, @Nullable String bound) {
        StatusSnapshot s = ctx.status().get();
        Map<String, Object> fields = new LinkedHashMap<>(s.vitals());
        if (bound != null) fields.put("boundDir", bound);
        List<HttpLive.Run> runs = ctx.liveRuns().get();
        var estimates = estimateRows(runs);
        if (!estimates.isEmpty()) fields.put("estimates", estimates);
        var held = McpHolds.statusRows(runs);
        if (!held.isEmpty()) fields.put("onHold", held);
        Map<String, Object> last = McpHistoryViews.jobSummary(McpDiagnostics.findNewest(ctx.history(), bound));
        if (last != null) fields.put("lastRun", last);
        return McpEnvelope.of("status", fields);
    }

    /**
     * {@code status}'s estimate for each job in flight, beside its {@code jobs} row: the bar's
     * percent and the remaining time it shows, {@code remainingMs}, with its likely range
     * {@code etaLowMs} to {@code etaHighMs} (the 10th to 90th percentile of where it lands;
     * JK-4TEZ7) and its {@code confidence}, the probability that the truth lands within 10% of
     * {@code remainingMs} (JK-R0N5S), each left out while unknown.
     */
    static List<Map<String, Object>> estimateRows(List<HttpLive.Run> live) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HttpLive.Run r : live) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jid", r.requestId());
            if (!Double.isNaN(r.progress())) row.put("progress", Math.round(r.progress() * 10) / 10.0);
            if (r.remainingMs() >= 0) row.put("remainingMs", r.remainingMs());
            if (r.lowMs() >= 0 && r.highMs() >= 0) {
                row.put("etaLowMs", r.lowMs());
                row.put("etaHighMs", r.highMs());
            }
            if (r.confidence() >= 0) row.put("confidence", Math.round(r.confidence() * 100) / 100.0);
            rows.add(row);
        }
        return rows;
    }

    /** One-line summary for dumb clients that only read {@code content[0].text}. */
    public static String statusSummary(Map<String, Object> status) {
        return "engine " + status.getOrDefault("version", "") + " pid " + status.getOrDefault("pid", "");
    }

    /** The kind of live job {@code jid}, or {@code run} when it is not in the live set. */
    public static String liveKind(McpContext ctx, long jid) {
        for (HttpLive.Run r : ctx.liveRuns().get()) {
            if (r.requestId() == jid && r.kind() != null && !r.kind().isBlank()) return r.kind();
        }
        return "run";
    }

    /** True while {@code jid} is in flight — one membership probe, not a snapshot of every run. */
    public static boolean isLive(McpContext ctx, long jid) {
        return ctx.liveJid().test(jid);
    }
}
