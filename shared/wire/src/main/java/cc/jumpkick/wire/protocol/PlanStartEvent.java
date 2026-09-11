// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A plan starts executing: its bar units and task counts; {@code progress} on the wire is derived from the units (see {@link EngineProtocol#BUILDPLAN_START}). */
public record PlanStartEvent(
        String dir,
        String planName,
        long numerator,
        long denominator,
        int tasksTotal,
        int tasksComplete,
        boolean cancelled) {
    public String encode() {
        return RequestJson.event(EngineProtocol.BUILDPLAN_START)
                .string("dir", dir)
                .string("planName", planName)
                .number("numerator", numerator)
                .number("denominator", denominator)
                .token("progress", ProtoEvents.progressPercent(numerator, denominator))
                .number("tasksTotal", tasksTotal)
                .number("tasksComplete", tasksComplete)
                .bool("cancelled", cancelled)
                .finish();
    }

    public static PlanStartEvent decode(String json) {
        return new PlanStartEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "planName"),
                Jsonl.longValue(json, "numerator", 0),
                Jsonl.longValue(json, "denominator", 0),
                Jsonl.intValue(json, "tasksTotal", 0),
                Jsonl.intValue(json, "tasksComplete", 0),
                Jsonl.bool(json, "cancelled", false));
    }
}
