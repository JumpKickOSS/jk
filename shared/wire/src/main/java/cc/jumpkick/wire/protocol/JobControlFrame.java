// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/**
 * Client → server: suspend or resume a live job by {@code jid} (see {@link
 * EngineProtocol#JOB_CONTROL_REQUEST}). {@code suspend} true holds the job's new steps and stops
 * its forked workers; false lets them go on.
 */
public record JobControlFrame(long jid, boolean suspend) {
    public String encode() {
        return RequestJson.request(EngineProtocol.JOB_CONTROL_REQUEST)
                .number("jid", jid)
                .string("action", suspend ? "suspend" : "resume")
                .finish();
    }

    public static JobControlFrame decode(String json) {
        return new JobControlFrame(Jsonl.longValue(json, "jid", -1), "suspend".equals(Jsonl.str(json, "action")));
    }
}
