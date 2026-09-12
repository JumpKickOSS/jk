// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.jobs.JobSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything {@code jk_run} / {@code jk_job} / the thin {@code jk_build}-style aliases share:
 * submit a {@link JobSpec} through the one admission point, optionally park for it, and attach the
 * finished journal row. The wait loop and the journal-settle poll live here so a second tool
 * cannot invent a different definition of "finished".
 */
public final class McpJobRuns {

    /** Hard cap on a single wait; agents re-issue {@code jk_job action=wait} to keep waiting. */
    public static final int MAX_WAIT_S = 3600;

    private McpJobRuns() {}

    /** Submit and answer immediately with the {@code <kind>-accepted} envelope. */
    public static Map<String, Object> accept(McpCall in, JobSpec spec) {
        McpContext ctx = in.ctx();
        try {
            long requestId = ctx.jobs().trigger(spec);
            String progressToken = in.progressToken();
            if (progressToken != null) ctx.progressTokens().bind(progressToken, requestId);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("kind", spec.kind());
            fields.put("jid", requestId);
            fields.put("dir", spec.dir());
            fields.put("events", "/api/events");
            fields.put("mcpEvents", "GET /mcp?jid=" + requestId);
            putSelection(fields, spec);
            if (progressToken != null) {
                fields.put("progressToken", progressToken);
                fields.put("mcpEventsByToken", "GET /mcp?progressToken=" + progressToken);
            }
            return McpEnvelope.of(
                    spec.kind() + "-accepted",
                    fields,
                    false,
                    null,
                    "Stream GET /mcp?jid=" + requestId + " or jk_cancel jid=" + requestId);
        } catch (IllegalStateException e) {
            throw new McpError(-32000, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        }
    }

    /**
     * {@code jk_run}: start a job and, unless {@code wait=false}, park for it and attach the
     * outcome. {@code pinnedKind} is set by the thin aliases ({@code jk_publish} and friends);
     * {@code null} reads {@code arguments.kind}, defaulting to {@code build}.
     */
    public static Map<String, Object> run(McpCall in, @Nullable String pinnedKind) {
        if (in.args().containsKey("aot_cache")) {
            // Not hosted: rejecting beats a silent no-op an agent would read as AOT training.
            throw new McpError(-32602, "aot_cache is not supported; run jobs train AOT via engine policy");
        }
        McpContext ctx = in.ctx();
        String kind = pinnedKind;
        if (kind == null) kind = in.str("kind");
        if (kind == null || kind.isBlank()) kind = "build";
        List<String> modules = in.strings("modules");
        boolean affected = in.flag("affected");
        if (affected && (modules == null || modules.isEmpty())) {
            modules = List.of("affected-wip");
        }
        JobSpec spec = new JobSpec(
                kind,
                in.requiredDir(),
                modules,
                in.strings("include_tags"),
                in.strings("exclude_tags"),
                in.strings("suites"),
                in.flag("skip_tests"),
                affected);
        boolean wait = in.flagOr("wait", true);
        int timeoutS = in.count("timeout_s", 600, 1, MAX_WAIT_S);
        long triggeredAt = System.currentTimeMillis();
        long jid = acceptedJid(in, spec);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", spec.kind());
        fields.put("jid", jid);
        fields.put("dir", spec.dir());
        putSelection(fields, spec);
        if (!wait) {
            fields.put("mcpEvents", "GET /mcp?jid=" + jid);
            return in.ok(
                    McpEnvelope.of("job-accepted", fields, false, null, "jk_job action=wait jid=" + jid), "jid " + jid);
        }
        // Parked waits yield their RPC admission permit — 16 waiting agents must not 503 the surface.
        boolean done = ctx.admissionYield().yielding(() -> waitUntilGone(ctx, jid, timeoutS * 1000L));
        fields.put("waited", true);
        fields.put("finished", done);
        if (!done) {
            return in.ok(
                    McpEnvelope.of("job", fields, false, null, "jk_job action=wait jid=" + jid),
                    "still running " + jid);
        }
        Map<String, Object> last = ctx.admissionYield().yielding(() -> finishedJob(ctx, jid, in.dir(), triggeredAt));
        if (last != null) {
            fields.put("result", last);
            if (Boolean.FALSE.equals(last.get("success"))) {
                attachDiagnostics(ctx, fields, last.get("id"), spec.dir());
            }
        }
        return in.ok(McpEnvelope.of("job", fields), "finished " + jid);
    }

    /** {@code jk_job}: get / wait / cancel, defaulting to the latest live job for the bound dir. */
    public static Map<String, Object> job(McpCall in) {
        McpContext ctx = in.ctx();
        String action = in.action("get").toLowerCase(Locale.ROOT);
        Long jid = in.num("jid");
        if ("cancel".equals(action) && jid == null && ctx.session().dir() == null) {
            // Unbound sessions must name their victim: "latest live job" across every dir could
            // kill another client's build.
            throw new McpError(-32602, "jk_job cancel requires jid (or jk_bind first)");
        }
        if (jid == null) jid = McpVitals.latestLiveJid(ctx, ctx.session().dir());
        if ("cancel".equals(action)) {
            if (jid == null) throw new McpError(-32602, "no live job to cancel");
            // No note: this jid came from the live set, so a miss is a race, not a typo.
            return cancel(in, jid.longValue(), null);
        }
        if (jid == null) {
            return in.ok(McpEnvelope.of("job", Map.of("live", false)), "no live job");
        }
        if ("wait".equals(action)) {
            int timeoutS = in.count("timeout_s", 600, 1, MAX_WAIT_S);
            long waitJid = jid.longValue();
            boolean done = ctx.admissionYield().yielding(() -> waitUntilGone(ctx, waitJid, timeoutS * 1000L));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("jid", jid);
            fields.put("finished", done);
            return in.ok(McpEnvelope.of("job", fields), done ? "finished " + jid : "still running " + jid);
        }
        boolean live = McpVitals.isLive(ctx, jid.longValue());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("jid", jid);
        fields.put("live", live);
        return in.ok(McpEnvelope.of("job", fields), live ? "running " + jid : "jid " + jid + " not live");
    }

    /**
     * Cancel one jid and answer the {@code cancel} envelope. {@code noteWhenMissed} is the
     * explanation a caller who typed the jid needs; a jid resolved from the live set passes
     * {@code null} because there is nothing to explain.
     */
    public static Map<String, Object> cancel(McpCall in, long jid, @Nullable String noteWhenMissed) {
        boolean ok = in.ctx().jobs().cancel(jid);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("jid", jid);
        fields.put("cancelled", ok);
        if (!ok && noteWhenMissed != null) fields.put("note", noteWhenMissed);
        return in.ok(McpEnvelope.of("cancel", fields), ok ? "cancelled " + jid : "jid " + jid + " not cancelled");
    }

    /** Modules and test filters, emitted only when the caller narrowed the job. */
    private static void putSelection(Map<String, Object> fields, JobSpec spec) {
        if (!spec.modules().isEmpty()) fields.put("modules", spec.modules());
        if (spec.hasTestFilter()) {
            fields.put("include_tags", spec.includeTags());
            fields.put("exclude_tags", spec.excludeTags());
            fields.put("suites", spec.suites());
        }
    }

    private static long acceptedJid(McpCall in, JobSpec spec) {
        Object jid = accept(in, spec).get("jid");
        if (jid instanceof Number n) return n.longValue();
        throw new McpError(-32603, "job did not return jid");
    }

    /** A failed run answers with the diagnostics an agent would ask for next. */
    private static void attachDiagnostics(
            McpContext ctx, Map<String, Object> fields, @Nullable Object runId, String jobDir) {
        // The job's own dir, not the bound dir — the run may live outside the session.
        String dir = runId == null ? ctx.session().dir() : jobDir;
        String run = runId == null ? null : String.valueOf(runId);
        McpDiagnostics.Page page = McpDiagnostics.page(ctx.history(), new McpDiagnostics.Query(run, dir));
        fields.put("diagnostics", page == null ? List.of() : page.rows());
    }

    private static @Nullable Map<String, Object> finishedJob(
            McpContext ctx, long jid, @Nullable String dir, long triggeredAt) {
        Map<String, Object> rec = waitForJournal(ctx, jid);
        if (rec == null) {
            // Newest-row fallback covers records written without a requestId stamp. A row that
            // started before this job was triggered (delayed complete(), history disabled) is a
            // previous run's outcome and must not be attributed to this jid.
            Map<String, Object> newest = McpDiagnostics.findNewest(ctx.history(), dir);
            if (newest != null && McpHistoryViews.lng(newest, "startedAt") >= triggeredAt) rec = newest;
        }
        return McpHistoryViews.jobSummary(rec);
    }

    /**
     * Journal write races live-run teardown (HTTP jobs unregister before {@code writeJournal}).
     * Poll briefly for the finished row stamped with this jid — via {@code finishedRecords}, so
     * each poll is a memoized single-record read, never a full journal re-scan.
     */
    private static @Nullable Map<String, Object> waitForJournal(McpContext ctx, long jid) {
        long deadline = System.currentTimeMillis() + ctx.journalSettleMs();
        while (true) {
            String raw = ctx.finishedRecords().apply(jid);
            if (raw != null) return McpHistoryViews.parseRecord(raw);
            if (System.currentTimeMillis() >= deadline) return null;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * Park until {@code jid} leaves the live set, asking the job's own liveness probe each tick —
     * never the live-run snapshot, which copies every in-flight module and step map per call.
     */
    private static boolean waitUntilGone(McpContext ctx, long jid, long timeoutMs) {
        long start = System.currentTimeMillis();
        boolean seen = false;
        while (System.currentTimeMillis() - start < timeoutMs) {
            boolean live = McpVitals.isLive(ctx, jid);
            if (live) seen = true;
            if (seen && !live) return true;
            if (!seen && System.currentTimeMillis() - start > 250) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !McpVitals.isLive(ctx, jid);
    }
}
