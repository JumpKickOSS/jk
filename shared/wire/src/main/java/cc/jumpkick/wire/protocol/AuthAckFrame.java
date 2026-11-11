// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Server → client: the engine's nonce and its proof that it holds the token (see {@link EngineProtocol#AUTH_ACK}). */
public record AuthAckFrame(String nonce, String proof) {
    public String encode() {
        return RequestJson.request(EngineProtocol.AUTH_ACK)
                .string("nonce", nonce)
                .string("proof", proof)
                .finish();
    }

    public static AuthAckFrame decode(String json) {
        return new AuthAckFrame(Jsonl.requiredStr(json, "nonce"), Jsonl.requiredStr(json, "proof"));
    }
}
