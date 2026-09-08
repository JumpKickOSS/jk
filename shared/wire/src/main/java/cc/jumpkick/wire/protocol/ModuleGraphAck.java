// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Terminal for {@link EngineProtocol#MODULE_GRAPH_REQUEST}. */
public record ModuleGraphAck(@Nullable String error, String graph) {

    public static ModuleGraphAck error(@Nullable String message) {
        return new ModuleGraphAck(message, "");
    }

    public static ModuleGraphAck of(@Nullable String graph) {
        return new ModuleGraphAck(null, graph == null ? "" : graph);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.MODULE_GRAPH_ACK)
                .string("error", error)
                .string("graph", graph)
                .finish();
    }

    public static ModuleGraphAck decode(String line) {
        return new ModuleGraphAck(Jsonl.str(line, "error"), orEmpty(Jsonl.str(line, "graph")));
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
