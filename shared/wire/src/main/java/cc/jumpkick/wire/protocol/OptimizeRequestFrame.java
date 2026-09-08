// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: train worker AOT caches (see {@link EngineProtocol#OPTIMIZE_REQUEST}). */
public record OptimizeRequestFrame(boolean force) {
    public String encode() {
        return RequestJson.request(EngineProtocol.OPTIMIZE_REQUEST)
                .bool("force", force)
                .finish();
    }

    public static OptimizeRequestFrame decode(String json) {
        return new OptimizeRequestFrame(Jsonl.bool(json, "force", false));
    }
}
