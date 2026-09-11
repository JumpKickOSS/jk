// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** The {@link EngineProtocol#ERR_ALREADY_RUNNING} error: the same fingerprint is in flight, with the holder's build number and job id (see {@link EngineProtocol#ERROR}). */
public record AlreadyRunningFrame(long buildNumber, long holderRequestId, String message) {
    public String encode() {
        return RequestJson.request(EngineProtocol.ERROR)
                .string("code", EngineProtocol.ERR_ALREADY_RUNNING)
                .string("message", message)
                .number("buildNumber", buildNumber)
                .number("jid", holderRequestId)
                .finish();
    }

    public static AlreadyRunningFrame decode(String json) {
        return new AlreadyRunningFrame(
                Jsonl.longValue(json, "buildNumber", 0),
                Jsonl.longValue(json, "jid", 0),
                Jsonl.requiredStr(json, "message"));
    }
}
