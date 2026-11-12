// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.plugin.MemoryHolds;
import cc.jumpkick.engine.plugin.WorkerLeases;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A job on hold for memory ({@link MemoryHolds}), as an agent learns of it: a parked call's
 * progress notifications say when the hold starts, keep coming while it lasts (so the client does
 * not time the call out as stalled) and say when it resumes; {@code status} names each held job;
 * the final verdict names the time held in all, so a slow build can be told from a starved host.
 */
final class McpHolds {

    /** While on hold, a progress notification at most this often. */
    static final long HELD_EVERY_MS = 5_000L;

    /** The words a hold starts with, on every surface. */
    static final String WAITING = "JumpKick is waiting for system memory to become available…";

    private McpHolds() {}

    /**
     * One parked call's notifier for job {@code jid}: {@link #tick} on each poll sends what changed.
     * Silent when the call has no progress token or no way back to its client.
     */
    static final class Notifier {
        private final @Nullable String token;
        private final @Nullable McpOutbound out;
        private final long jid;
        private boolean held;
        private long lastSentMs;
        private long sent;

        Notifier(McpCall call, long jid) {
            this(call.progressToken(), call.outbound(), jid);
        }

        Notifier(@Nullable String token, @Nullable McpOutbound out, long jid) {
            this.token = token;
            this.out = out;
            this.jid = jid;
        }

        void tick(long nowMs) {
            if (token == null || out == null) return;
            boolean now = MemoryHolds.onHold(jid);
            if (now && !held) {
                long[] need = MemoryHolds.holdNeed(jid);
                send(
                        need == null
                                ? WAITING
                                : WAITING + " (need " + WorkerLeases.format(need[0]) + ", free "
                                        + WorkerLeases.format(need[1]) + ")");
                lastSentMs = nowMs;
            } else if (now && nowMs - lastSentMs >= HELD_EVERY_MS) {
                send("On hold " + seconds(MemoryHolds.heldMillis(jid)) + " waiting for system memory");
                lastSentMs = nowMs;
            } else if (!now && held) {
                send("Resumed: the build was on hold " + seconds(MemoryHolds.totalHeldMillis(jid)) + " for memory");
                lastSentMs = nowMs;
            }
            held = now;
        }

        private void send(String message) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("progressToken", token);
            params.put("progress", ++sent);
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
    }

    /** The verdict's line for a job that spent time on hold, or {@code ""} when it spent none. */
    static String totalLine(long jid) {
        long held = MemoryHolds.totalHeldMillis(jid);
        return held < 1_000 ? "" : "on hold for memory " + seconds(held) + " in all\n";
    }

    /** {@code status}'s rows for the live jobs on hold: jid, time held so far, the memory waited for. */
    static List<Map<String, Object>> statusRows(List<HttpLive.Run> live) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HttpLive.Run r : live) {
            if (!MemoryHolds.onHold(r.requestId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jid", r.requestId());
            row.put("state", "on-hold");
            row.put("reason", "waiting for system memory");
            row.put("heldMs", MemoryHolds.heldMillis(r.requestId()));
            long[] need = MemoryHolds.holdNeed(r.requestId());
            if (need != null) {
                row.put("needBytes", need[0]);
                row.put("freeBytes", need[1]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static String seconds(long ms) {
        return Math.max(0, ms) / 1000L + "s";
    }
}
