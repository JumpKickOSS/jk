// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A lock/update module's terminal with its written-lockfile counts (see {@link EngineProtocol#BUILDPLAN_FINISH}). */
public record PlanFinishLockEvent(String dir, boolean success, long packages, long sources, long plugins) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "lock")
                .string("dir", dir)
                .bool("success", success)
                .number("lockPackages", packages)
                .number("lockSources", sources)
                .number("lockPlugins", plugins)
                .finish();
    }

    public static PlanFinishLockEvent decode(String json) {
        return new PlanFinishLockEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "lockPackages", 0),
                Jsonl.longValue(json, "lockSources", 0),
                Jsonl.longValue(json, "lockPlugins", 0));
    }
}
