// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Keep-alive line during long jobs (see {@link EngineProtocol#HEARTBEAT}). */
public record HeartbeatFrame(long elapsedMillis) {
    public String encode() {
        return RequestJson.request(EngineProtocol.HEARTBEAT)
                .number("elapsedMillis", elapsedMillis)
                .finish();
    }

    public static HeartbeatFrame decode(String json) {
        return new HeartbeatFrame(Jsonl.longValue(json, "elapsedMillis", 0));
    }
}
