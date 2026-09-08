// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Server → client: ack for shutdown with the in-flight job count and whether a drain is underway (see {@link EngineProtocol#BYE}). */
public record ByeFrame(int plans, boolean draining) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BYE)
                .number("plans", plans)
                .bool("draining", draining)
                .finish();
    }

    public static ByeFrame decode(String json) {
        return new ByeFrame(Jsonl.intValue(json, "plans", 0), Jsonl.bool(json, "draining", false));
    }
}
