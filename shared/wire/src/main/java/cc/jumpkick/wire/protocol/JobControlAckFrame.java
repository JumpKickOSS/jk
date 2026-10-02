// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Server → client: whether a {@link JobControlFrame} reached a live job (see {@link EngineProtocol#JOB_CONTROL_ACK}). */
public record JobControlAckFrame(long jid, boolean applied) {
    public String encode() {
        return RequestJson.request(EngineProtocol.JOB_CONTROL_ACK)
                .number("jid", jid)
                .bool("applied", applied)
                .finish();
    }

    public static JobControlAckFrame decode(String json) {
        return new JobControlAckFrame(Jsonl.longValue(json, "jid", -1), Jsonl.bool(json, "applied", false));
    }
}
