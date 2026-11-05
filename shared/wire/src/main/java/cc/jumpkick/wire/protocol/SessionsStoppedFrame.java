// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Predecessor → successor: it writes no session record from now on (see {@link EngineProtocol#SESSIONS_STOPPED}). */
public record SessionsStoppedFrame(long pid) {
    public String encode() {
        return RequestJson.request(EngineProtocol.SESSIONS_STOPPED)
                .number("pid", pid)
                .finish();
    }

    public static SessionsStoppedFrame decode(String json) {
        return new SessionsStoppedFrame(Jsonl.longValue(json, "pid", 0));
    }
}
