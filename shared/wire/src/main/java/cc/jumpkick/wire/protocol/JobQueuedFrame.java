// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/**
 * Server → client: the job waits for coordinator memory behind {@code ahead} earlier jobs (see
 * {@link EngineProtocol#JOB_QUEUED}). {@code reason} names what it waits for; {@code "memory"} today.
 */
public record JobQueuedFrame(long jid, int ahead, String reason) {

    public static final String MEMORY = "memory";

    public String encode() {
        return RequestJson.request(EngineProtocol.JOB_QUEUED)
                .number("jid", jid)
                .number("ahead", ahead)
                .string("reason", reason, MEMORY)
                .finish();
    }

    public static JobQueuedFrame decode(String json) {
        String reason = Jsonl.str(json, "reason");
        return new JobQueuedFrame(
                Jsonl.longValue(json, "jid", 0), Jsonl.intValue(json, "ahead", 0), reason == null ? MEMORY : reason);
    }
}
