// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Objects;

/** A module's event scope opens (see {@link EngineProtocol#MODULE_START}); {@code coord} when known. */
public record ModuleStartEvent(String dir, String coord) {

    public ModuleStartEvent(String dir) {
        this(dir, "");
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.MODULE_START)
                .string("dir", dir)
                .optionalNonEmptyString("coord", coord)
                .finish();
    }

    public static ModuleStartEvent decode(String json) {
        return new ModuleStartEvent(
                Jsonl.requiredStr(json, "dir"), Objects.requireNonNullElse(Jsonl.str(json, "coord"), ""));
    }
}
