// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A task's interpolated tick update: the delta, the plan's bar units (with the derived percent) and task counts (see {@link EngineProtocol#TICK_UPDATE}). */
public record TickUpdateEvent(
        String dir,
        String task,
        int delta,
        long numerator,
        long denominator,
        int tasksTotal,
        int tasksComplete,
        boolean cancelled) {
    public String encode() {
        return ProgressFields.encode(
                EngineProtocol.TICK_UPDATE,
                dir,
                task,
                delta,
                numerator,
                denominator,
                tasksTotal,
                tasksComplete,
                cancelled);
    }

    public static TickUpdateEvent decode(String json) {
        return new TickUpdateEvent(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "task"),
                Jsonl.intValue(json, "delta", 0),
                Jsonl.longValue(json, "numerator", 0),
                Jsonl.longValue(json, "denominator", 0),
                Jsonl.intValue(json, "tasksTotal", 0),
                Jsonl.intValue(json, "tasksComplete", 0),
                Jsonl.bool(json, "cancelled", false));
    }
}
