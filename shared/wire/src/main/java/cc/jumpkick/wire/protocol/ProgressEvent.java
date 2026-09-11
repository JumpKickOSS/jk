// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A task's progress tick: the delta, the plan's bar units (with the derived percent) and task counts (see {@link EngineProtocol#PROGRESS}). */
public record ProgressEvent(
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
                EngineProtocol.PROGRESS,
                dir,
                task,
                delta,
                numerator,
                denominator,
                tasksTotal,
                tasksComplete,
                cancelled);
    }

    public static ProgressEvent decode(String json) {
        return new ProgressEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "task"),
                Jsonl.intValue(json, "delta", 0),
                Jsonl.longValue(json, "numerator", 0),
                Jsonl.longValue(json, "denominator", 0),
                Jsonl.intValue(json, "tasksTotal", 0),
                Jsonl.intValue(json, "tasksComplete", 0),
                Jsonl.bool(json, "cancelled", false));
    }
}
