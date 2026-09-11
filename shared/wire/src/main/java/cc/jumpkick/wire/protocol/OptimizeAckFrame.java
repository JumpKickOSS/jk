// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Server → client: optimize result; {@code trained}/{@code skipped} are comma-separated tool tags, {@code summary} human text (see {@link EngineProtocol#OPTIMIZE_ACK}). */
public record OptimizeAckFrame(boolean ok, String trained, String skipped, String summary) {
    public String encode() {
        return RequestJson.request(EngineProtocol.OPTIMIZE_ACK)
                .bool("ok", ok)
                .string("trained", trained, "")
                .string("skipped", skipped, "")
                .string("summary", summary, "")
                .finish();
    }

    public static OptimizeAckFrame decode(String json) {
        return new OptimizeAckFrame(
                Jsonl.bool(json, "ok", false),
                Jsonl.requiredStr(json, "trained"),
                Jsonl.requiredStr(json, "skipped"),
                Jsonl.requiredStr(json, "summary"));
    }
}
