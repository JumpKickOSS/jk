// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Predecessor → successor: in-flight job count after yielding its listeners (see {@link EngineProtocol#DRAIN_STATUS}). */
public record DrainStatusFrame(long pid, int plans, String version) {
    public String encode() {
        return RequestJson.request(EngineProtocol.DRAIN_STATUS)
                .number("pid", pid)
                .number("plans", plans)
                .string("version", version, "")
                .finish();
    }

    public static DrainStatusFrame decode(String json) {
        return new DrainStatusFrame(
                Jsonl.longValue(json, "pid", 0), Jsonl.intValue(json, "plans", 0), Jsonl.requiredStr(json, "version"));
    }
}
