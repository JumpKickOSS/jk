// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.http.HttpLive;
import cc.jumpkick.engine.http.StatusSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code status} envelope: engine vitals, the live-job rows, and the last finished run. One
 * owner, because {@code jk_status} and the {@code jk://session} resource are the same facts and
 * must not answer differently.
 */
public final class McpVitals {

    /** Age after which a live job with no progress is marked stalled. */
    public static final long STALL_MS = 60_000;

    private McpVitals() {}

    public static Map<String, Object> statusPayload(McpContext ctx) {
        StatusSnapshot s = ctx.status().get();
        // Every vital, from the one enumeration; only the MCP-specific keys are added here.
        Map<String, Object> fields = new LinkedHashMap<>(s.vitals());
        String bound = ctx.session().dir();
        if (bound != null) fields.put("boundDir", bound);
        fields.put("jobs", liveJobRows(ctx));
        Map<String, Object> last = McpHistoryViews.jobSummary(McpDiagnostics.findNewest(ctx.history(), bound));
        if (last != null) fields.put("lastRun", last);
        return McpEnvelope.of("status", fields);
    }

    /** One-line summary for dumb clients that only read {@code content[0].text}. */
    public static String statusSummary(Map<String, Object> status) {
        return "engine " + status.getOrDefault("version", "") + " pid " + status.getOrDefault("pid", "");
    }

    private static List<Map<String, Object>> liveJobRows(McpContext ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (HttpLive.Run r : ctx.liveRuns().get()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jid", r.requestId());
            row.put("kind", r.kind());
            row.put("dir", r.dir());
            row.put("progress", Double.isNaN(r.progress()) ? null : r.progress());
            // Stall is silence, not age: a healthy 10-minute build ticks progress the whole way.
            // Jobs that never emit a signal fall back to startedAt.
            long basis = r.lastEventAt() > 0 ? r.lastEventAt() : r.startedAt();
            long age = Math.max(0, now - basis);
            row.put("lastEventAgeMs", age);
            row.put("stalled", age >= STALL_MS && (Double.isNaN(r.progress()) || r.progress() < 100));
            out.add(row);
        }
        return out;
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

    /** True while {@code jid} is in the live-run snapshot. */
    public static boolean isLive(McpContext ctx, long jid) {
        for (HttpLive.Run r : ctx.liveRuns().get()) {
            if (r.requestId() == jid) return true;
        }
        return false;
    }
}
