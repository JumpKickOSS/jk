// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Server → client: whether the job was cancelled, with an optional note (see {@link EngineProtocol#CANCEL_ACK}). */
public record CancelAckFrame(
        long jid, boolean cancelled, @Nullable String note) {
    public String encode() {
        return RequestJson.request(EngineProtocol.CANCEL_ACK)
                .number("jid", jid)
                .bool("cancelled", cancelled)
                .optionalNonBlankString("note", note)
                .finish();
    }

    public static CancelAckFrame decode(String json) {
        return new CancelAckFrame(
                Jsonl.longValue(json, "jid", 0), Jsonl.bool(json, "cancelled", false), Jsonl.str(json, "note"));
    }
}
