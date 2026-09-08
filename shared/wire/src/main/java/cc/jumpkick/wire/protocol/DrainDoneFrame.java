// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Predecessor → successor: no in-flight jobs remain; this process is exiting (see {@link EngineProtocol#DRAIN_DONE}). */
public record DrainDoneFrame(long pid) {
    public String encode() {
        return RequestJson.request(EngineProtocol.DRAIN_DONE).number("pid", pid).finish();
    }

    public static DrainDoneFrame decode(String json) {
        return new DrainDoneFrame(Jsonl.longValue(json, "pid", 0));
    }
}
