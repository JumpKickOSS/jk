// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Client → server: exit; {@code force} abandons in-flight jobs, else the engine drains (see {@link EngineProtocol#SHUTDOWN}). */
public record ShutdownFrame(boolean force) {
    public String encode() {
        return RequestJson.request(EngineProtocol.SHUTDOWN).bool("force", force).finish();
    }

    public static ShutdownFrame decode(String json) {
        return new ShutdownFrame(Jsonl.bool(json, "force", false));
    }
}
