// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: the client's handshake nonce (see {@link EngineProtocol#AUTH}). */
public record AuthFrame(String nonce) {
    public String encode() {
        return RequestJson.request(EngineProtocol.AUTH).string("nonce", nonce).finish();
    }

    public static AuthFrame decode(String json) {
        return new AuthFrame(Jsonl.requiredStr(json, "nonce"));
    }
}
