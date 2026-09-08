// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** One module's event scope opens in a lock/update cascade (see {@link EngineProtocol#LOCK_MODULE}). */
public record LockModuleEvent(String dir, String coord) {
    public String encode() {
        return RequestJson.request(EngineProtocol.LOCK_MODULE)
                .string("dir", dir)
                .string("coord", coord)
                .finish();
    }

    public static LockModuleEvent decode(String json) {
        return new LockModuleEvent(Jsonl.str(json, "dir"), Jsonl.str(json, "coord"));
    }
}
