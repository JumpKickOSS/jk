// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk guard freeze <id> --reason "…"} / {@code --retire} / {@code --accept-scope}: grow the
 * baseline for one rule, drop a retired rule's entries, or accept a rule's smaller population as its
 * floor. Refused engine-side without a reason or under CI.
 */
public record GuardFreezeRequest(
        @Nullable String dir,
        @Nullable String ruleId,
        @Nullable String reason,
        boolean retire,
        boolean acceptScope) {

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_FREEZE_REQUEST)
                .string("dir", dir)
                .string("ruleId", ruleId)
                .string("reason", reason)
                .bool("retire", retire)
                .bool("acceptScope", acceptScope)
                .finish();
    }

    public static GuardFreezeRequest decode(String json) {
        return new GuardFreezeRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "ruleId"),
                Jsonl.str(json, "reason"),
                Jsonl.bool(json, "retire", false),
                Jsonl.bool(json, "acceptScope", false));
    }
}
