// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A module's event scope opens (see {@link EngineProtocol#MODULE_START}). */
public record ModuleStartEvent(String dir) {
    public String encode() {
        return RequestJson.request(EngineProtocol.MODULE_START)
                .string("dir", dir)
                .finish();
    }

    public static ModuleStartEvent decode(String json) {
        return new ModuleStartEvent(Jsonl.requiredStr(json, "dir"));
    }
}
