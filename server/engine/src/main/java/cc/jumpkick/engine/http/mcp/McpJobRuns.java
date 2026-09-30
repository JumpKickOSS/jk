// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.jobs.JobOrigin;
import cc.jumpkick.engine.jobs.JobSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything {@code run} and the thin {@code build}-style aliases share:
 * submit a {@link JobSpec} through the one admission point, optionally park for it, and attach the
 * finished journal row. The wait loop and the journal-settle poll live here so a second tool
 * cannot invent a different definition of "finished".
 */
public final class McpJobRuns {

    /** Hard cap on a single wait; agents call {@code run(jid=N)} to keep waiting. */
    public static final int MAX_WAIT_S = 3600;

    /** How long a call parks when it names no {@code timeout_s}. */
    public static final int DEFAULT_WAIT_S = 240;

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
            putSelection(fields, spec);
            return McpEnvelope.of(spec.kind() + "-accepted", fields);
        } catch (IllegalStateException e) {
            throw new McpError(-32000, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        }
    }

    /**
     * {@code run}: start a job and, unless {@code wait=false}, park for it and attach the
     * outcome. {@code pinnedKind} is set by the thin aliases ({@code publish} and friends);
     * {@code null} reads {@code arguments.kind}, defaulting to {@code test}: the agent loop's step.
     */
    public static Map<String, Object> run(McpCall in, @Nullable String pinnedKind) {
        if (in.args().containsKey("aot_cache")) {
            // Not hosted: rejecting beats a silent no-op an agent would read as AOT training.
            throw new McpError(-32602, "aot_cache is not supported; run jobs train AOT via engine policy");
        }
        McpContext ctx = in.ctx();
        String kind = pinnedKind;
        if (kind == null) kind = in.str("kind");
        if (kind == null || kind.isBlank()) kind = "test";
        List<String> modules = in.strings("modules");
        if (modules.isEmpty()) modules = only(in);
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
                affected,
                deadlineMs(in),
                JobOrigin.mcp(in.sessionLabel()));
        boolean wait = in.flagOr("wait", true);
        int timeoutS = timeoutS(in);
        long triggeredAt = System.currentTimeMillis();
        long jid = acceptedJid(in, spec);
        if (!wait) {
            return in.text(McpAgentText.running(spec.kind(), jid));
        }
        // Parked waits yield their RPC admission permit — 16 waiting agents must not 503 the surface.
        boolean done = ctx.admissionYield().yielding(() -> waitUntilGone(ctx, jid, timeoutS * 1000L));
        if (!done) {
            return in.text(McpAgentText.timeout(spec.kind(), jid));
        }
        Map<String, Object> last = ctx.admissionYield().yielding(() -> finishedJob(ctx, jid, in.dir(), triggeredAt));
        String text = McpAgentText.of(ctx, last);
        if (text == null) text = "FAIL " + spec.kind() + " jid=" + jid + "\nno run record\n";
        return in.text(text);
    }

    /**
     * {@code run(jid=N)}: park on a job an earlier call started and answer its verdict. The call's
     * progress token follows that job, as it does for a job {@code run} starts.
     */
    public static Map<String, Object> await(McpCall in, long jid) {
        McpContext ctx = in.ctx();
        String progressToken = in.progressToken();
        if (progressToken != null) ctx.progressTokens().bind(progressToken, jid);
        int timeoutS = timeoutS(in);
        boolean done = ctx.admissionYield().yielding(() -> waitUntilGone(ctx, jid, timeoutS * 1000L));
        if (!done) {
            return in.text(McpAgentText.timeout(McpVitals.liveKind(ctx, jid), jid));
        }
        Map<String, Object> last = ctx.admissionYield().yielding(() -> waitForJournal(ctx, jid));
        if (last == null) last = McpDiagnostics.findByRequestId(ctx.history(), jid);
        String text = McpAgentText.of(ctx, last);
        return in.text(text != null ? text : "FAIL run jid=" + jid + "\nno run record\n");
    }

    /** {@code timeout_s}: how long one call parks before it answers {@code TIMEOUT}. */
    private static int timeoutS(McpCall in) {
        return in.count("timeout_s", DEFAULT_WAIT_S, 1, MAX_WAIT_S);
    }

    /**
     * Cancel one jid. {@code noteWhenMissed} is the
     * explanation a caller who typed the jid needs; a jid resolved from the live set passes
     * {@code null} because there is nothing to explain.
     */
    public static Map<String, Object> cancel(McpCall in, long jid, @Nullable String noteWhenMissed) {
        if (in.ctx().jobs().cancel(jid)) return in.text("cancelled " + jid + "\n");
        String missed = "jid " + jid + " not cancelled" + (noteWhenMissed == null ? "" : ": " + noteWhenMissed);
        return in.error(missed + "\n");
    }

    /**
     * {@code deadline_s} as the job's wall deadline in ms: absent leaves the engine's detached
     * default, {@code 0} lifts the cap, a negative is the caller's error.
     */
    private static @Nullable Long deadlineMs(McpCall in) {
        Long seconds = in.num("deadline_s");
        if (seconds == null) return null;
        if (seconds < 0) throw new McpError(-32602, "deadline_s must be >= 0 (0 = no deadline)");
        return seconds * 1000L;
    }

    /** {@code only} is the module filter: a string ({@code a,b}) or a string array. */
    private static List<String> only(McpCall in) {
        List<String> many = in.strings("only");
        if (!many.isEmpty()) return many;
        String one = in.str("only");
        if (one == null || one.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : one.split(",", -1)) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
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

    /** The finished journal row, full enough to render. Not a previous run that happened to be newest. */
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
        return rec;
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
