// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Terminal of a cache maintenance plan: the files and bytes it touched (kind {@code cache}). */
public record PlanFinishCacheEvent(String dir, boolean success, long cacheFiles, long cacheBytes) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "cache")
                .string("dir", dir)
                .bool("success", success)
                .number("cacheFiles", cacheFiles)
                .number("cacheBytes", cacheBytes)
                .finish();
    }

    public static PlanFinishCacheEvent decode(String json) {
        return new PlanFinishCacheEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "cacheFiles", 0),
                Jsonl.longValue(json, "cacheBytes", 0));
    }
}
