// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: version, protocol and purpose ({@code connect} or {@code probe}; see {@link EngineProtocol#HELLO}). */
public record HelloFrame(String version, String purpose) {
    public String encode() {
        return RequestJson.request(EngineProtocol.HELLO)
                .string("version", version)
                .number("proto", EngineProtocol.PROTOCOL)
                .string("purpose", purpose)
                .finish();
    }

    public static HelloFrame decode(String json) {
        return new HelloFrame(Jsonl.requiredStr(json, "version"), Jsonl.requiredStr(json, "purpose"));
    }
}
