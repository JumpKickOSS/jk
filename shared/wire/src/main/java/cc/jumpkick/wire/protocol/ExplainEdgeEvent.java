// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** One workspace edge in a {@code jk explain} burst (see {@link EngineProtocol#EXPLAIN_EDGE}). */
public record ExplainEdgeEvent(String dir, String dependsOnDir) {
    public String encode() {
        return RequestJson.request(EngineProtocol.EXPLAIN_EDGE)
                .string("dir", dir)
                .string("dependsOnDir", dependsOnDir)
                .finish();
    }

    public static ExplainEdgeEvent decode(String json) {
        return new ExplainEdgeEvent(Jsonl.requiredStr(json, "dir"), Jsonl.requiredStr(json, "dependsOnDir"));
    }
}
