// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** The lock's current phase line for one module of a lock/update cascade (see {@link EngineProtocol#LOCK_PHASE}). */
public record LockPhaseEvent(String dir, String label) {
    public String encode() {
        return RequestJson.request(EngineProtocol.LOCK_PHASE)
                .string("dir", dir)
                .string("label", label)
                .finish();
    }

    public static LockPhaseEvent decode(String json) {
        return new LockPhaseEvent(Jsonl.requiredStr(json, "dir"), Jsonl.requiredStr(json, "label"));
    }
}
