// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** The {@code jk explain} burst is complete: the widest ready set and the module count (see {@link EngineProtocol#EXPLAIN_DONE}). */
public record ExplainDoneEvent(int maxReadyWidth, int moduleCount) {
    public String encode() {
        return RequestJson.request(EngineProtocol.EXPLAIN_DONE)
                .number("maxReadyWidth", maxReadyWidth)
                .number("moduleCount", moduleCount)
                .finish();
    }

    public static ExplainDoneEvent decode(String json) {
        return new ExplainDoneEvent(Jsonl.intValue(json, "maxReadyWidth", 0), Jsonl.intValue(json, "moduleCount", 0));
    }
}
