// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: cancel the job with this id (see {@link EngineProtocol#CANCEL_REQUEST}). */
public record CancelRequestFrame(long jid) {
    public String encode() {
        return RequestJson.request(EngineProtocol.CANCEL_REQUEST)
                .number("jid", jid)
                .finish();
    }

    public static CancelRequestFrame decode(String json) {
        return new CancelRequestFrame(Jsonl.longValue(json, "jid", 0));
    }
}
