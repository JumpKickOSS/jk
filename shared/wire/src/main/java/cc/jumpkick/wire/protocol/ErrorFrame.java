// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** The one error envelope: a code from {@link EngineProtocol}'s vocabulary and a message (see {@link EngineProtocol#ERROR}). */
public record ErrorFrame(String code, String message) {
    public String encode() {
        return RequestJson.request(EngineProtocol.ERROR)
                .string("code", code)
                .string("message", message)
                .finish();
    }

    public static ErrorFrame decode(String json) {
        return new ErrorFrame(Jsonl.requiredStr(json, "code"), Jsonl.requiredStr(json, "message"));
    }
}
