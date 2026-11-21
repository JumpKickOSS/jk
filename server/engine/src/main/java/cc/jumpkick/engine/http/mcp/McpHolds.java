// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.plugin.MemoryHolds;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.host.time.Suspensions;
import cc.jumpkick.run.SlowestTests;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A job on hold for memory ({@link MemoryHolds}), as an agent learns of it: a parked call's
 * progress notifications ({@link McpCallProgress}) say when the hold starts, keep coming while it
 * lasts (so the client does not time the call out as stalled) and say when it resumes; {@code status} names each held job;
 * the final verdict names the time held in all, and the time suspended, so a slow build can be told
 * from a starved host or a machine that slept.
 */
final class McpHolds {

    /** While on hold, a progress notification at most this often. */
    static final long HELD_EVERY_MS = 5_000L;

    /** The words a hold starts with, on every surface. */
    static final String WAITING = "JumpKick is waiting for system memory to become available…";

    private McpHolds() {}

    /** The note a parked call gets as its job's hold starts, with the memory it waits for when known. */
    static String startLine(long jid) {
        long[] need = MemoryHolds.holdNeed(jid);
        return need == null
                ? WAITING
                : WAITING + " (need " + WorkerLeases.format(need[0]) + ", free " + WorkerLeases.format(need[1]) + ")";
    }

    /** The note a parked call gets while its job's hold lasts. */
    static String holdLine(long jid) {
        return "On hold " + seconds(MemoryHolds.heldMillis(jid)) + " waiting for system memory";
    }

    /** The note a parked call gets as its job resumes. */
    static String resumeLine(long jid) {
        return "Resumed: the build was on hold " + seconds(MemoryHolds.totalHeldMillis(jid)) + " for memory";
    }

    /**
     * The verdict's lines for a job that spent time on hold or suspended (the machine slept or the
     * engine was stopped), or {@code ""} when it spent none: its wall is not what its work took.
     */
    static String totalLine(long jid) {
        long held = MemoryHolds.totalHeldMillis(jid);
        long suspended = Suspensions.system().suspendedMillis(jid);
        return (held < 1_000 ? "" : "on hold for memory " + seconds(held) + " in all\n")
                + (suspended < 1_000
                        ? ""
                        : "suspended " + SlowestTests.duration(suspended)
                                + " (the machine slept or JumpKick was stopped)\n");
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
