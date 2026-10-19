// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Server → client: the job is over and everything under {@code target/} is written (see {@link
 * EngineProtocol#JOB_FINISH}). {@code noop} is additive: the run proved the tree up to date and has
 * a line in the project's run log rather than a run directory, so {@code report} carries the agent
 * verdict its {@code jk-agent.txt} would have.
 */
public record JobFinishFrame(long jid, @Nullable String noop) {
    public JobFinishFrame(long jid) {
        this(jid, null);
    }

    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.JOB_FINISH).number("jid", jid);
        if (noop != null) json.string("noop", noop);
        return json.finish();
    }

    public static JobFinishFrame decode(String json) {
        return new JobFinishFrame(Jsonl.longValue(json, "jid", 0), Jsonl.str(json, "noop"));
    }
}
