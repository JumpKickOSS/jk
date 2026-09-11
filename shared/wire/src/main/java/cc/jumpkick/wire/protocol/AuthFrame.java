// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: the socket token (see {@link EngineProtocol#AUTH}). */
public record AuthFrame(String token) {
    public String encode() {
        return RequestJson.request(EngineProtocol.AUTH).string("token", token).finish();
    }

    public static AuthFrame decode(String json) {
        return new AuthFrame(Jsonl.requiredStr(json, "token"));
    }
}
