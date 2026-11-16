// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.WorkspaceGroup;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobOrigin;
import cc.jumpkick.engine.jobs.JobRequests;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.host.time.Suspensions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * The park for a client that gives up on a request after 60 s and then cancels it: answering
     * {@code TIMEOUT … run(jid=N)} first keeps the job, where its cancel would end it. Measured on
     * that client, calls succeed up to 57 s and time out at 63 s; each TIMEOUT costs it a turn.
     */
    static final int SHORT_CLIENT_WAIT_S = 55;

    /** {@code clientInfo.name}s of hosts with that short request timeout. */
    private static final Set<String> SHORT_CLIENTS = Set.of("Cursor");

    private McpJobRuns() {}

    /**
     * Submit and answer immediately with the {@code <kind>-accepted} envelope. When the same job is
     * already running, the answer is that job's {@code jid}, so a {@code run} waits on it and the
     * agent never sees the journal's build number, a second number for the same job.
     */
    public static Map<String, Object> accept(McpCall in, JobSpec spec) {
        McpContext ctx = in.ctx();
        try {
            long requestId;
            try {
                requestId = ctx.jobs().trigger(spec);
            } catch (JobEnvelope.AlreadyRunning running) {
                requestId = running.jid();
            }
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
        Path groupRoot = groupRoot(in);
        if (groupRoot != null) return runGroup(in, pinnedKind, groupRoot);
        McpContext ctx = in.ctx();
        JobSpec spec = decode(in, pinnedKind);
        boolean wait = in.flagOr("wait", true);
        int timeoutS = timeoutS(in);
        long triggeredAt = System.currentTimeMillis();
        long jid = acceptedJid(in, spec);
        if (!wait) {
            return in.text(McpAgentText.running(spec.kind(), jid));
        }
        // Parked waits yield their RPC admission permit — 16 waiting agents must not 503 the surface.
        McpHolds.Notifier holds = new McpHolds.Notifier(in, jid);
        boolean done =
                ctx.admissionYield().yielding(() -> waitUntilGone(ctx, jid, timeoutS * ctx.waitSecondMs(), holds));
        if (!done) {
            return in.text(McpAgentText.timeout(spec.kind(), jid));
        }
        Map<String, Object> last = ctx.admissionYield().yielding(() -> finishedJob(ctx, jid, in.dir(), triggeredAt));
        String text = McpAgentText.of(ctx, last);
        if (text == null) text = "FAIL " + spec.kind() + " jid=" + jid + "\nno run record\n";
        return in.text(text + McpHolds.totalLine(jid));
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
        McpHolds.Notifier holds = new McpHolds.Notifier(in, jid);
        boolean done =
                ctx.admissionYield().yielding(() -> waitUntilGone(ctx, jid, timeoutS * ctx.waitSecondMs(), holds));
        if (!done) {
            return in.text(McpAgentText.timeout(McpVitals.liveKind(ctx, jid), jid));
        }
        Map<String, Object> last = ctx.admissionYield().yielding(() -> waitForJournal(ctx, jid));
        if (last == null) last = McpDiagnostics.findByRequestId(ctx.history(), jid);
        String text = McpAgentText.of(ctx, last);
        return in.text((text != null ? text : "FAIL run jid=" + jid + "\nno run record\n") + McpHolds.totalLine(jid));
    }

    /**
     * The arguments {@code run} and its aliases read themselves; everything else is a job field
     * {@link JobRequests} decodes, and a name in neither set is refused.
     */
    public static final Set<String> RUN_FIELDS =
            Set.of(McpSchemas.GOAL, "wait", "timeout_s", "jid", "run", McpDecisions.DECISION, "upload", "action");

    /**
     * {@code in}'s arguments as the job they ask for, through the decoder every surface shares;
     * {@code pinnedKind} is an alias's kind, else {@code arguments.kind}, else {@code test}.
     */
    public static JobSpec decode(McpCall in, @Nullable String pinnedKind) {
        String kind = pinnedKind;
        if (kind == null) kind = in.str("kind");
        if (kind == null || kind.isBlank()) kind = "test";
        if ("publish".equals(kind) && in.flag("upload")) kind = "upload";
        Map<String, Object> fields = new LinkedHashMap<>(in.args());
        fields.put("kind", kind);
        try {
            return JobRequests.decode(fields, JobOrigin.mcp(in.sessionLabel()), kind, in.requiredDir(), RUN_FIELDS);
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        }
    }

    /** {@code timeout_s}: how long one call parks before it answers {@code TIMEOUT}. */
    static int timeoutS(McpCall in) {
        return in.count("timeout_s", clientWaitS(in), 1, MAX_WAIT_S);
    }

    /**
     * How long one call may stay outstanding before it must answer, so the client's own tool-call
     * timeout never cancels it: {@link #SHORT_CLIENT_WAIT_S} for a host known to give up at 60 s,
     * else {@link #DEFAULT_WAIT_S}.
     */
    static int clientWaitS(McpCall in) {
        McpConnection connection = in.connection();
        String client = connection == null ? null : connection.client();
        return client != null && SHORT_CLIENTS.contains(client) ? SHORT_CLIENT_WAIT_S : DEFAULT_WAIT_S;
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
     * {@code notifications/cancelled}: the job bound to the cancelled call's progress token
     * ({@code params._meta.progressToken}, which {@code jk mcp} adds) is cancelled, so the parked
     * call answers with its verdict. A call that started no job, or an unknown token, is left alone.
     */
    public static void cancelled(McpContext ctx, Map<String, Object> params) {
        if (!(params.get("_meta") instanceof Map<?, ?> meta)) return;
        Object token = meta.get("progressToken");
        if (token == null) return;
        Long jid = ctx.progressTokens().resolve(String.valueOf(token));
        if (jid != null) ctx.jobs().cancel(jid);
    }

    /** Modules and test filters, emitted only when the caller narrowed the job. */
    /** The kinds a group root refuses: each releases one workspace. */
    private static final Set<String> RELEASE_KINDS = Set.of("publish", "upload", "release");

    /** The call's directory when it is a group root ({@link WorkspaceGroup}), else {@code null}. */
    private static @Nullable Path groupRoot(McpCall in) {
        String dir = in.dir();
        if (dir == null || dir.isBlank()) return null;
        Path path = Path.of(dir);
        return WorkspaceGroup.declares(path) ? path : null;
    }

    /**
     * {@code run} at a group root: the job runs in each member the selection names ({@code modules}
     * names members here; empty is every member the root runs by default), one after another in
     * {@code path}-dependency order, each the job a call with that member's {@code dir} starts; the
     * answer is each member's verdict and a total. A member that fails leaves what depends on it
     * unrun.
     */
    private static Map<String, Object> runGroup(McpCall in, @Nullable String pinnedKind, Path root) {
        String kind = pinnedKind != null ? pinnedKind : in.str("kind");
        if (kind == null || kind.isBlank()) kind = "test";
        if (RELEASE_KINDS.contains(kind)) {
            return in.error(root + " groups workspaces and has no version of its own; run " + kind
                    + " with dir set to the workspace it releases\n");
        }
        if (!in.flagOr("wait", true)) {
            return in.error("a group root runs its members one after another; wait=false takes dir=<member>\n");
        }
        WorkspaceGroup group;
        WorkspaceGroup.Selection selection;
        try {
            group = WorkspaceGroup.load(root);
            if (!group.missing().isEmpty()) {
                return in.error("[workspaces] members lists " + String.join(", ", group.missing())
                        + ", which holds no project\n");
            }
            Object profile = in.args().get("profile");
            String rootProfile = profile instanceof String p && group.profiles().containsKey(p) ? p : null;
            selection = group.select(rootProfile, memberNames(in.args().get("modules")));
        } catch (IOException | RuntimeException e) {
            return in.error(e.getMessage() + "\n");
        }
        StringBuilder text = new StringBuilder();
        for (WorkspaceGroup.Member m : selection.skipped()) {
            text.append("SKIP ")
                    .append(m.path())
                    .append(": ")
                    .append(m.excludedBecause())
                    .append('\n');
        }
        Set<Path> broken = new HashSet<>();
        int failed = 0;
        for (WorkspaceGroup.Member m : selection.run()) {
            Path blocker = selection.prerequisites(m).stream()
                    .filter(broken::contains)
                    .findFirst()
                    .orElse(null);
            if (blocker != null) {
                broken.add(m.dir());
                text.append("SKIP ").append(m.path()).append(": depends on a member that failed\n");
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>(in.args());
            fields.remove("modules");
            fields.put("kind", kind);
            String dir = m.dir().toString();
            JobSpec spec;
            try {
                spec = JobRequests.decode(fields, JobOrigin.mcp(in.sessionLabel()), kind, dir, RUN_FIELDS);
            } catch (IllegalArgumentException e) {
                return in.error(e.getMessage() + "\n");
            }
            long triggeredAt = Clock.SYSTEM.millis();
            long jid = acceptedJid(in, spec);
            McpContext ctx = in.ctx();
            McpHolds.Notifier holds = new McpHolds.Notifier(in, jid);
            boolean done = ctx.admissionYield()
                    .yielding(() -> waitUntilGone(ctx, jid, timeoutS(in) * ctx.waitSecondMs(), holds));
            if (!done) {
                text.append(McpAgentText.timeout(spec.kind(), jid));
                return in.text(text.toString());
            }
            Map<String, Object> last = ctx.admissionYield().yielding(() -> finishedJob(ctx, jid, dir, triggeredAt));
            String verdict = McpAgentText.of(ctx, last);
            if (verdict == null) verdict = "FAIL " + spec.kind() + " jid=" + jid + "\nno run record\n";
            text.append("== ").append(m.path()).append('\n').append(verdict);
            if (!verdict.endsWith("\n")) text.append('\n');
            if (!verdict.startsWith("OK")) {
                broken.add(m.dir());
                failed++;
            }
        }
        int n = selection.run().size();
        text.append(failed == 0 && broken.isEmpty() ? "OK " : "FAIL ")
                .append(kind)
                .append(' ')
                .append(root.getFileName())
                .append(" · ")
                .append(n)
                .append(n == 1 ? " workspace" : " workspaces")
                .append(failed == 0 ? "" : ", " + failed + " failed")
                .append('\n');
        return in.text(text.toString());
    }

    /** The {@code modules} argument as member names: a list, or one comma-joined string. */
    private static List<String> memberNames(@Nullable Object modules) {
        List<String> out = new ArrayList<>();
        if (modules instanceof List<?> list) {
            for (Object o : list)
                if (o != null && !o.toString().isBlank()) out.add(o.toString().strip());
        } else if (modules instanceof String s) {
            for (String part : s.split(",")) if (!part.isBlank()) out.add(part.strip());
        }
        return out;
    }

    private static void putSelection(Map<String, Object> fields, JobSpec spec) {
        if (!spec.modules().isEmpty()) fields.put("modules", spec.modules());
        if (spec.hasTestFilter()) {
            fields.put("include_tags", spec.includeTags());
            fields.put("exclude_tags", spec.excludeTags());
            fields.put("suites", spec.suites());
            if (!spec.classes().isEmpty()) fields.put("classes", spec.classes());
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
     * never the live-run snapshot, which copies every in-flight module and step map per call. A job
     * never seen live is done only once its record is there: one that finished between two ticks
     * has one, and one still queued (behind the workspace's previous job, or waiting for memory) is
     * not live yet and has none, and a wait that gave up on it answered FAIL for a build that passed.
     */
    private static boolean waitUntilGone(McpContext ctx, long jid, long timeoutMs, McpHolds.Notifier holds) {
        long start = Clock.SYSTEM.millis();
        Suspensions watch = Suspensions.system();
        long cursor = watch.cursor();
        boolean seen = false;
        // The wait's window restarts when the engine resumes from a suspend or a stop: the client
        // that waits slept too, and its own tool-call timer did not run either.
        while (Clock.SYSTEM.millis() - Math.max(start, watch.resumedAt(cursor)) < timeoutMs) {
            holds.tick(Clock.SYSTEM.millis());
            boolean live = McpVitals.isLive(ctx, jid);
            if (live) seen = true;
            if (seen && !live) return true;
            if (!seen
                    && Clock.SYSTEM.millis() - start > 250
                    && ctx.finishedRecords().apply(jid) != null) {
                return true;
            }
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
