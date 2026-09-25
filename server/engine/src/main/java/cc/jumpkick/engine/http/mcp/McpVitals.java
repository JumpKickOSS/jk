// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.http.StatusSnapshot;
import java.util.LinkedHashMap;
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
        Map<String, Object> last = McpHistoryViews.jobSummary(McpDiagnostics.findNewest(ctx.history(), bound));
        if (last != null) fields.put("lastRun", last);
        return McpEnvelope.of("status", fields);
    }

    /** One-line summary for dumb clients that only read {@code content[0].text}. */
    public static String statusSummary(Map<String, Object> status) {
        return "engine " + status.getOrDefault("version", "") + " pid " + status.getOrDefault("pid", "");
    }

    /** The newest live jid for {@code dir} (every dir when {@code null}), or {@code null}. */
    public static @Nullable Long latestLiveJid(McpContext ctx, @Nullable String dir) {
        String want = dir == null ? null : McpHistoryViews.normalizeDir(dir);
        HttpLive.Run newest = null;
        for (HttpLive.Run r : ctx.liveRuns().get()) {
            if (want != null) {
                String have = McpHistoryViews.normalizeDir(r.dir() == null ? "" : r.dir());
                if (!have.equals(want) && !have.startsWith(want + "/")) continue;
            }
            // Newest by startedAt (jid tie-break) — the live-run snapshot iterates a hash map,
            // so list position is meaningless.
            if (newest == null
                    || r.startedAt() > newest.startedAt()
                    || (r.startedAt() == newest.startedAt() && r.requestId() > newest.requestId())) {
                newest = r;
            }
        }
        return newest == null ? null : Long.valueOf(newest.requestId());
    }

    /** True while {@code jid} is in flight — one membership probe, not a snapshot of every run. */
    public static boolean isLive(McpContext ctx, long jid) {
        return ctx.liveJid().test(jid);
    }
}
