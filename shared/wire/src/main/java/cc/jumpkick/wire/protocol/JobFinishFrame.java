// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Server → client: the job is over and everything under {@code target/} is written (see {@link EngineProtocol#JOB_FINISH}). */
public record JobFinishFrame(long jid) {
    public String encode() {
        return RequestJson.request(EngineProtocol.JOB_FINISH).number("jid", jid).finish();
    }

    public static JobFinishFrame decode(String json) {
        return new JobFinishFrame(Jsonl.longValue(json, "jid", 0));
    }
}
