// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A prune is waiting on in-flight plans, or on another process holding the lock (see {@link EngineProtocol#PRUNE_WAIT}). */
public record PruneWaitEvent(int plans, boolean external) {
    public String encode() {
        return RequestJson.request(EngineProtocol.PRUNE_WAIT)
                .number("plans", plans)
                .bool("external", external)
                .finish();
    }

    public static PruneWaitEvent decode(String json) {
        return new PruneWaitEvent(Jsonl.intValue(json, "plans", 0), Jsonl.bool(json, "external", false));
    }
}
