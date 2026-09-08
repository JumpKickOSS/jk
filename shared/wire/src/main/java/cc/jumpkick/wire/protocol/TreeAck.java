// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** The rendered dependency tree, or the error that stopped it (see {@link EngineProtocol#TREE_ACK}). */
public record TreeAck(@Nullable String error, String rendered) {
    public String encode() {
        return RequestJson.request(EngineProtocol.TREE_ACK)
                .string("error", error)
                .string("rendered", rendered)
                .finish();
    }

    public static TreeAck decode(String json) {
        String rendered = Jsonl.str(json, "rendered");
        return new TreeAck(Jsonl.str(json, "error"), rendered == null ? "" : rendered);
    }
}
