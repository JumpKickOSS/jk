// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: the client's proof that it holds the token (see {@link EngineProtocol#AUTH_PROOF}). */
public record AuthProofFrame(String proof) {
    public String encode() {
        return RequestJson.request(EngineProtocol.AUTH_PROOF)
                .string("proof", proof)
                .finish();
    }

    public static AuthProofFrame decode(String json) {
        return new AuthProofFrame(Jsonl.requiredStr(json, "proof"));
    }
}
