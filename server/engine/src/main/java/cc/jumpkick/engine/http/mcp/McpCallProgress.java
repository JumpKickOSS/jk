// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.plugin.MemoryHolds;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/**
 * A parked call's {@code notifications/progress}, sent on the call's own response stream: the
 * job's running step and percent, when they change (at most once per {@link #CHANGE_GAP_MS}) and
 * at least every {@link #HEARTBEAT_MS} while the job runs, so a client that resets its timeout on
 * progress keeps a long wait alive; and the job's hold for memory ({@link McpHolds}). The first
 * note goes out {@link #CHANGE_GAP_MS} into the wait, so a quick call answers plain JSON.
 *
 * <p>{@code progress} is the job's percent of {@code total} 100, nudged up by a hundredth when it
 * has not moved: the spec requires every notification for a token to increase it. One instance
 * serves the whole call, so a group run's members continue one sequence. Silent when the call has
 * no progress token or no way back to its client. The token goes back as the client sent it: a
 * number stays a number.
 */
final class McpCallProgress {

    /** The shortest gap between two notes about the running job. */
    static final long CHANGE_GAP_MS = 1_000L;

    /** The longest a running job goes without a note. */
    static final long HEARTBEAT_MS = 5_000L;

    private final @Nullable Object token;
    private final @Nullable McpOutbound out;
    private final LongFunction<HttpLive.@Nullable Run> live;
    private long jid;
    private boolean held;
    private long firstTickMs = -1;
    private long lookedMs;
    private long sentMs;
    private double sent = -1;
    private @Nullable String lastMessage;

    McpCallProgress(McpCall call, long jid) {
        this(call.progressToken(), call.outbound(), jid, id -> liveRun(call.ctx(), id));
    }

    /** {@code live} answers the in-flight snapshot of a job, or null when it is not running. */
    McpCallProgress(
            @Nullable Object token, @Nullable McpOutbound out, long jid, LongFunction<HttpLive.@Nullable Run> live) {
        this.token = token;
        this.out = out;
        this.jid = jid;
        this.live = live;
    }

    /** Follow job {@code next} from now on; the sequence of {@code progress} values carries on. */
    void follow(long next) {
        jid = next;
        held = false;
        lastMessage = null;
    }

    /** One poll of the wait: send what changed. */
    void tick(long nowMs) {
        if (token == null || out == null) return;
        if (firstTickMs < 0) firstTickMs = nowMs;
        boolean now = MemoryHolds.onHold(jid);
        if (now != held) {
            held = now;
            send(nowMs, Double.NaN, now ? McpHolds.startLine(jid) : McpHolds.resumeLine(jid));
            return;
        }
        if (held) {
            if (nowMs - sentMs >= McpHolds.HELD_EVERY_MS) send(nowMs, Double.NaN, McpHolds.holdLine(jid));
            return;
        }
        if (nowMs - firstTickMs < CHANGE_GAP_MS || nowMs - lookedMs < CHANGE_GAP_MS) return;
        lookedMs = nowMs;
        HttpLive.Run run = live.apply(jid);
        if (run == null) return;
        String message = message(run);
        if (!message.equals(lastMessage) || nowMs - sentMs >= HEARTBEAT_MS) send(nowMs, run.progress(), message);
    }

    /** The running step, with the job's percent when it is known: {@code api: compileJava · 45%}. */
    static String message(HttpLive.Run run) {
        String step = runningStep(run);
        String what = step == null ? (run.kind() == null || run.kind().isBlank() ? "running" : run.kind()) : step;
        double p = run.progress();
        return Double.isNaN(p) ? what : what + " · " + Math.round(p) + "%";
    }

    /** The last task still running, named under its module when the job has several; null for none. */
    private static @Nullable String runningStep(HttpLive.Run run) {
        String step = lastRunning(run.tasks());
        for (HttpLive.Module m : run.modules()) {
            String task = lastRunning(m.tasks());
            if (task == null) continue;
            Path leaf = m.dir().isEmpty() ? null : Path.of(m.dir()).getFileName();
            step = leaf == null ? task : leaf + ": " + task;
        }
        return step;
    }

    private static @Nullable String lastRunning(List<HttpLive.Task> tasks) {
        String name = null;
        for (HttpLive.Task t : tasks) if ("RUN".equals(t.status())) name = t.name();
        return name;
    }

    private void send(long nowMs, double percent, String message) {
        double base = Double.isNaN(percent) ? 0 : percent;
        double value = Math.round(Math.max(base, sent + 0.01) * 100) / 100.0;
        if (value <= sent) value = Math.round((sent + 0.01) * 100) / 100.0;
        sent = value;
        sentMs = nowMs;
        lastMessage = message;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("progressToken", token);
        params.put("progress", value);
        if (value <= 100) params.put("total", 100);
        params.put("message", message);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/progress");
        note.put("params", params);
        try {
            if (out != null) out.send(note);
        } catch (IOException | RuntimeException ignored) {
            // The client stopped listening; the verdict still answers the call.
        }
    }

    private static HttpLive.@Nullable Run liveRun(McpContext ctx, long jid) {
        if (!McpVitals.isLive(ctx, jid)) return null;
        for (HttpLive.Run r : ctx.liveRuns().get()) if (r.requestId() == jid) return r;
        return null;
    }
}
