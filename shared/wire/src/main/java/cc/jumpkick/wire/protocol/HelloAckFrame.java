// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Server → client: version, pid, start time, protocol, drain state and the engine's content identity ({@code buildId}, empty for releases; see {@link EngineProtocol#HELLO_ACK}). */
public record HelloAckFrame(
        String version,
        long pid,
        long startedAt,
        boolean draining,
        @Nullable String buildId) {
    public String encode() {
        return RequestJson.request(EngineProtocol.HELLO_ACK)
                .string("version", version)
                .number("pid", pid)
                .number("startedAt", startedAt)
                .number("proto", EngineProtocol.PROTOCOL)
                .bool("draining", draining)
                .string("buildId", buildId, "")
                .finish();
    }

    public static HelloAckFrame decode(String json) {
        return new HelloAckFrame(
                Jsonl.str(json, "version"),
                Jsonl.longValue(json, "pid", 0),
                Jsonl.longValue(json, "startedAt", 0),
                Jsonl.bool(json, "draining", false),
                Jsonl.str(json, "buildId"));
    }
}
