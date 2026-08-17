// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Terminal for {@link EngineProtocol#MODULE_GRAPH_REQUEST}. */
public record ModuleGraphAck(String error, String graph) {

    public static ModuleGraphAck error(String message) {
        return new ModuleGraphAck(message, "");
    }

    public static ModuleGraphAck of(String graph) {
        return new ModuleGraphAck(null, graph == null ? "" : graph);
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.MODULE_GRAPH_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"graph\":" + Jsonl.quote(graph)
                + "}";
    }

    public static ModuleGraphAck decode(String line) {
        return new ModuleGraphAck(Jsonl.str(line, "error"), orEmpty(Jsonl.str(line, "graph")));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
