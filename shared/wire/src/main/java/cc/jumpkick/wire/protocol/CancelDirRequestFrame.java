// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: cancel every live job under a project dir — no jid (see {@link EngineProtocol#CANCEL_REQUEST}). */
public record CancelDirRequestFrame(String dir) {
    public String encode() {
        return RequestJson.request(EngineProtocol.CANCEL_REQUEST)
                .string("dir", dir, "")
                .finish();
    }

    public static CancelDirRequestFrame decode(String json) {
        return new CancelDirRequestFrame(Jsonl.str(json, "dir"));
    }
}
