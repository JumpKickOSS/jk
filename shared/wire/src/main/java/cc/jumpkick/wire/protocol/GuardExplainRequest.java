// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk guard explain}: {@code ruleId} for one card, {@code schema} for a kind's keys and
 * example (or {@code guard-test} for the {@code @Guard} skeleton), neither for the catalog.
 */
public record GuardExplainRequest(
        @Nullable String dir,
        @Nullable String ruleId,
        @Nullable String schema) {
    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_EXPLAIN_REQUEST)
                .string("dir", dir)
                .string("ruleId", ruleId)
                .string("schema", schema)
                .finish();
    }

    public static GuardExplainRequest decode(String json) {
        return new GuardExplainRequest(Jsonl.str(json, "dir"), Jsonl.str(json, "ruleId"), Jsonl.str(json, "schema"));
    }
}
